package com.smartpay.sync

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var smsInboxObserver: ContentObserver? = null
    private var dynamicSmsReceiver: SmsReceiver? = null

    companion object {
        const val CHANNEL_ID = "sms_gateway_service"
        const val NOTIF_ID = 7601
        const val ACTION_RETRY_NOW = "com.smartpay.sync.ACTION_RETRY_NOW"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "SMS Gateway Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Incoming Payment SMS will be monitored."
                    setShowBadge(false)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
        }

        fun start(context: Context, retryNow: Boolean = false) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                if (retryNow) action = ACTION_RETRY_NOW
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try { context.stopService(Intent(context, SyncForegroundService::class.java)) } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()

        val (recent, success, failed) = AppStorage.getTodayCounters(this)
        val initText = "Incoming Payment SMS will be monitored.\nRecent: $recent | Success: $success | Failed: $failed"
        startForeground(NOTIF_ID, buildNotification("SmartPay Sync is Running", initText))

        registerDynamicSmsReceiver()
        registerSmsDatabaseObserver()

        serviceScope.launch {
            while (isActive) {
                scanInboxForOfficialBkashSms()
                retryPendingSms()
                AppStorage.runAutoCleanupIfNeeded(this@SyncForegroundService)
                delay(10_000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RETRY_NOW) {
            serviceScope.launch {
                scanInboxForOfficialBkashSms()
                retryPendingSms()
            }
        }
        return START_STICKY
    }

    private fun registerDynamicSmsReceiver() {
        try {
            dynamicSmsReceiver = SmsReceiver()
            val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
                priority = 999
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dynamicSmsReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(dynamicSmsReceiver, filter)
            }
        } catch (_: Exception) {}
    }

    private fun registerSmsDatabaseObserver() {
        try {
            smsInboxObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    serviceScope.launch {
                        delay(800L)
                        scanInboxForOfficialBkashSms()
                    }
                }
            }
            contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                smsInboxObserver!!
            )
        } catch (_: Exception) {}
    }

    private fun scanInboxForOfficialBkashSms() {
        if (!AppStorage.isLoggedIn(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return

        try {
            val cutoffTime = System.currentTimeMillis() - (60 * 60 * 1000L)
            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date"),
                "date >= ?",
                arrayOf(cutoffTime.toString()),
                "date DESC"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                val bodyIdx = c.getColumnIndex("body")
                var count = 0
                while (c.moveToNext() && count < 25) {
                    count++
                    val address = if (addrIdx >= 0) c.getString(addrIdx) else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx) else ""

                    if (SmsParser.isStrictOfficialBkashHeader(address)) {
                        val parsed = SmsParser.parseIncomingOrManual(address ?: "bKash", body ?: "", "Inbox")
                        if (parsed != null) {
                            val added = AppStorage.insertIfNew(this, parsed)
                            if (added) {
                                SmsReceiver.uploadRecordImmediately(applicationContext, parsed)
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun retryPendingSms() {
        if (!AppStorage.isLoggedIn(this)) return
        val endpoint = AppStorage.getEndpoint(this)
        val deviceKey = AppStorage.getDeviceKey(this)
        if (endpoint.isBlank() || deviceKey.length < 16) return

        val records = AppStorage.getRecords(this)
        val pendingList = records.filter { it.status == "Pending" }
        for (item in pendingList) {
            val res = ApiClient.uploadSms(endpoint, deviceKey, item)
            if (res.success || res.httpCode in 200..299) {
                val st = if (res.status.equals("flagged", ignoreCase = true)) "Flagged" else "Success"
                AppStorage.updateStatusByIdOrTrx(this, item.id, st, "Synced")
                SmsReceiver.updateLiveNotification(this, "Last SMS: ${item.sender} payment uploaded")
            } else if (res.httpCode in 400..499) {
                AppStorage.updateStatusByIdOrTrx(this, item.id, "Failed", "Code: ${res.httpCode}")
            }
        }
        sendBroadcast(Intent(SmsReceiver.ACTION_UI_REFRESH).setPackage(packageName))
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartPaySync::WakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(shortText: String, bigText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SmartPay Sync is Running")
            .setContentText(shortText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            dynamicSmsReceiver?.let { unregisterReceiver(it) }
            smsInboxObserver?.let { contentResolver.unregisterContentObserver(it) }
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
        if (AppStorage.isLoggedIn(this)) start(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
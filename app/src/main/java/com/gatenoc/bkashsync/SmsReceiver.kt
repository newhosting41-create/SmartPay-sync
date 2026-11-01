package com.smartpay.sync

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_UI_REFRESH = "com.smartpay.sync.ACTION_UI_REFRESH"

        fun updateLiveNotification(context: Context, lastEventText: String) {
            try {
                SyncForegroundService.ensureChannel(context)
                val (recent, success, failed) = AppStorage.getTodayCounters(context)
                val summary = "$lastEventText\nRecent: $recent | Success: $success | Failed: $failed"

                val pendingIntent = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, SyncForegroundService.CHANNEL_ID)
                    .setContentTitle("SmartPay Sync is Running")
                    .setContentText(lastEventText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(pendingIntent)
                    .build()

                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(SyncForegroundService.NOTIF_ID, notification)
            } catch (_: Exception) {}
        }

        /**
         * Instant Background Uploader (Exact logic from base.apk / LD0/k thread):
         * Captures SMS instantly using WakeLock and uploads to server via HttpURLConnection in background thread.
         */
        fun uploadRecordImmediately(
            context: Context,
            record: SmsRecord,
            pendingResult: PendingResult? = null
        ) {
            Thread {
                var wl: PowerManager.WakeLock? = null
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartPaySync:UploadWakeLock")?.apply {
                        acquire(30_000L)
                    }

                    val endpoint = AppStorage.getEndpoint(context)
                    val deviceKey = AppStorage.getDeviceKey(context)

                    if (endpoint.isNotBlank() && deviceKey.length >= 16) {
                        val res = ApiClient.uploadSms(endpoint, deviceKey, record)
                        Log.d("SMS_UPLOAD", "Code: ${res.httpCode} | ${res.message}")

                        if (res.success || res.httpCode in 200..299) {
                            val newSt = if (res.status.equals("flagged", ignoreCase = true)) "Flagged" else "Success"
                            AppStorage.updateStatusByIdOrTrx(context, record.id, newSt, "Synced")
                            updateLiveNotification(context, "Last SMS: ${record.sender} payment uploaded")
                        } else {
                            val failSt = if (res.httpCode in 400..499) "Failed" else "Pending"
                            AppStorage.updateStatusByIdOrTrx(context, record.id, failSt, "Code: ${res.httpCode}")
                            updateLiveNotification(context, "Last SMS: ${record.sender} upload failed")
                        }
                    }
                    context.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(context.packageName))
                } catch (e: Exception) {
                    Log.e("SMS_UPLOAD_ERROR", e.toString())
                    AppStorage.updateStatusByIdOrTrx(context, record.id, "Failed", e.localizedMessage ?: "Network error")
                    updateLiveNotification(context, "Last SMS: ${record.sender} upload failed")
                } finally {
                    try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
                    try { pendingResult?.finish() } catch (_: Exception) {}
                }
            }.start()
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppStorage.isLoggedIn(context)) return

        var senderAddress = ""
        val messageBuilder = StringBuilder()

        val msgs = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { null }
        if (!msgs.isNullOrEmpty()) {
            for (sms in msgs) {
                if (senderAddress.isEmpty()) {
                    senderAddress = sms.displayOriginatingAddress ?: sms.originatingAddress ?: ""
                }
                messageBuilder.append(sms.displayMessageBody ?: sms.messageBody ?: "")
            }
        } else {
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            for (pdu in pdus) {
                val bytes = pdu as? ByteArray ?: continue
                val sms = SmsMessage.createFromPdu(bytes) ?: continue
                if (senderAddress.isEmpty()) {
                    senderAddress = sms.displayOriginatingAddress ?: ""
                }
                messageBuilder.append(sms.displayMessageBody ?: "")
            }
        }

        val fullBody = messageBuilder.toString()
        if (!SmsParser.isStrictOfficialBkashHeader(senderAddress)) return

        val record = SmsParser.parseIncomingOrManual(senderAddress, fullBody, "Auto") ?: return
        val isNew = AppStorage.insertIfNew(context, record)
        if (isNew) {
            context.sendBroadcast(Intent(ACTION_UI_REFRESH).setPackage(context.packageName))
            val pendingResult = goAsync()
            uploadRecordImmediately(context.applicationContext, record, pendingResult)
        }
    }
}
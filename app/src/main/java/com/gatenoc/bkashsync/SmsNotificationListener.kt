package com.gatenoc.bkashsync

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * When Google Messages posts a notification, NEVER trust the notification title alone
 * (because someone could save a fake contact as "bKash").
 * Instead, use this event as an instant 0.5-second trigger to read the real hardware
 * Telephony SMS Inbox (`content://sms/inbox`), which strictly verifies the real network `address == "bKash"`!
 */
class SmsNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null || sbn.packageName == packageName) return
        if (!AppStorage.isLoggedIn(this)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        // If a notification looks like bKash, trigger an immediate hardware SMS Inbox check
        // so only real network sender "bKash" from Telephony DB is accepted!
        if (title.equals("bKash", ignoreCase = true) || text.contains("TrxID", ignoreCase = true)) {
            SyncForegroundService.start(applicationContext, retryNow = true)
        }
    }
}

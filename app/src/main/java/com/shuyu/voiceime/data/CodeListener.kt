package com.shuyu.voiceime.data

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads verification codes from notifications (SMS apps, banking apps, …).
 * This keeps working on systems whose "verification code protection" hides SMS from other apps,
 * because the messaging app still posts a notification containing the text.
 */
class CodeListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val ex = sbn.notification?.extras ?: return
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE),
            ex.getCharSequence(Notification.EXTRA_TEXT),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val code = Otp.extract(text) ?: return
        Otp.offer(code)
    }

    companion object {
        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, CodeListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}

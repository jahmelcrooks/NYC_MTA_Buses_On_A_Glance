package com.dngro.transit.alerts

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dngro.transit.data.AppPrefs
import com.dngro.transit.presentation.MainActivity

/**
 * "Buzz me shortly before this bus arrives." One alarm per starred-style key, set from the
 * bus's predicted time when the user taps the bell. The prediction can change afterwards, so
 * the notification says "around" and this is a heads-up, not a guarantee. Alarms don't survive
 * a reboot.
 */
object ArrivalAlerts {
    private const val CHANNEL_ID = "arrival_alerts"
    private const val EXTRA_KEY = "alert_key"
    private const val EXTRA_TITLE = "alert_title"
    private const val EXTRA_TEXT = "alert_text"

    fun schedule(context: Context, key: String, title: String, text: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context, key, title, text)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
        AppPrefs.saveAlerts(context, AppPrefs.loadAlerts(context) + (key to triggerAtMillis))
    }

    fun cancel(context: Context, key: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(pendingIntent(context, key, "", ""))
        AppPrefs.saveAlerts(context, AppPrefs.loadAlerts(context) - key)
    }

    /** Keys of alerts that are still in the future. Expired entries are dropped from storage. */
    fun activeKeys(context: Context, nowMillis: Long = System.currentTimeMillis()): Set<String> {
        val all = AppPrefs.loadAlerts(context)
        val active = all.filterValues { it > nowMillis }
        if (active.size != all.size) AppPrefs.saveAlerts(context, active)
        return active.keys
    }

    private fun pendingIntent(context: Context, key: String, title: String, text: String): PendingIntent {
        val intent = Intent(context, ArrivalAlertReceiver::class.java)
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun show(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        AppPrefs.saveAlerts(context, AppPrefs.loadAlerts(context) - key)

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Bus arrival alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            },
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
        } catch (e: SecurityException) {
            // Notification permission not granted: nothing to show.
        }
    }
}

class ArrivalAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ArrivalAlerts.show(context, intent)
    }
}

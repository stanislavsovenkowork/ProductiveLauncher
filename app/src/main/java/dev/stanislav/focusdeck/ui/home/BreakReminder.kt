package dev.stanislav.focusdeck.ui.home

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.MainActivity
import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.repo.BREAK_MILLIS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A local, inexact reminder only. It never resumes a timer or changes restrictions. */
internal object BreakReminder {
    private const val CHANNEL = "focusdeck_break_reminders"
    private const val NOTIFICATION_ID = 48735
    private const val ACTION = "dev.stanislav.focusdeck.BREAK_REMINDER"
    private const val SESSION = "session"
    private const val PAUSE = "pause"

    fun update(context: Context, block: FocusBlock?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, BreakReminderReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pending)
        if (block?.sessionId == null || block.breakStartedAt == null || block.breakStartedElapsedMillis == null) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val now = SystemClock.elapsedRealtime()
        val deadline = if (boot >= 0 && block.breakBootCount == boot && now >= block.breakStartedElapsedMillis)
            block.breakStartedElapsedMillis + BREAK_MILLIS else now
        val keyed = Intent(context, BreakReminderReceiver::class.java).setAction(ACTION)
            .putExtra(SESSION, block.sessionId).putExtra(PAUSE, block.breakStartedAt)
        val alarm = PendingIntent.getBroadcast(context, 0, keyed, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline.coerceAtLeast(now), alarm)
    }

    suspend fun deliver(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val block = (context.applicationContext as FocusDeckApp).focusBlockRepository.observeActive().first() ?: return
        val pause = block.breakStartedAt ?: return
        if (block.sessionId != intent.getStringExtra(SESSION) || pause != intent.getLongExtra(PAUSE, -1)) return
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val elapsed = block.breakStartedElapsedMillis
        if (boot >= 0 && block.breakBootCount == boot && elapsed != null &&
            SystemClock.elapsedRealtime() >= elapsed && SystemClock.elapsedRealtime() - elapsed < BREAK_MILLIS) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val key = "${block.sessionId}:$pause"
        val prefs = context.getSharedPreferences("break-reminders", Context.MODE_PRIVATE)
        if (prefs.getString("delivered", null) == key) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Напоминание о возвращении", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Перерыв закончился").setContentText("Можно вернуться к блоку, когда будешь готов.")
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs.edit().putString("delivered", key).apply()
    }
}

class BreakReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { BreakReminder.deliver(context, intent) } finally { pending.finish() }
        }
    }
}

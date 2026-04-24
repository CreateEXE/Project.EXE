package com.projectexe.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.projectexe.MainActivity
import com.projectexe.ProjectEXEApplication
import com.projectexe.R

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.projectexe.action.REMINDER_FIRE"
        const val EXTRA_ID    = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY  = "body"
        private const val CHANNEL = "exe_reminders_channel"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id    = intent.getIntExtra(EXTRA_ID, 1)
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.reminder_default_title)
        val body  = intent.getStringExtra(EXTRA_BODY).orEmpty()

        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "EXE Reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Reminders set by your companion." })
        } catch (_: Exception) {}

        val tap = PendingIntent.getActivity(ctx, id,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(body.ifEmpty { ctx.getString(R.string.reminder_default_title) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try { nm.notify(id, n) } catch (_: Exception) {}

        // Keep the application class warm if possible; ignore if not yet initialized.
        try { ProjectEXEApplication.instance } catch (_: Throwable) {}
    }
}

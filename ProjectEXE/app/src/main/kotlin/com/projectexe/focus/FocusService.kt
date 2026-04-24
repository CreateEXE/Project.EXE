package com.projectexe.focus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.projectexe.MainActivity
import com.projectexe.R

class FocusService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.projectexe.focus.START"
        const val ACTION_STOP  = "com.projectexe.focus.STOP"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_LABEL   = "label"
        private const val CHANNEL = "exe_focus_channel"
        private const val NOTIF_ID = 4242
        @Volatile var isRunning = false; private set
        @Volatile var endsAt: Long = 0L; private set
    }

    private var timer: CountDownTimer? = null
    private var label: String = ""

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { stopFocus(); return START_NOT_STICKY }
            else -> {
                val minutes = (intent?.getIntExtra(EXTRA_MINUTES, 25) ?: 25).coerceIn(1, 240)
                label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
                ensureChannel()
                startForeground(NOTIF_ID, buildNotif("$minutes min remaining"))
                isRunning = true
                endsAt = System.currentTimeMillis() + minutes * 60_000L
                timer?.cancel()
                timer = object : CountDownTimer(minutes * 60_000L, 30_000L) {
                    override fun onTick(ms: Long) { updateNotif("${(ms / 60_000L) + 1} min remaining") }
                    override fun onFinish() {
                        try { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID + 1,
                            buildNotif(getString(R.string.focus_done))) } catch (_: Exception) {}
                        stopFocus()
                    }
                }.start()
            }
        }
        return START_STICKY
    }

    private fun stopFocus() {
        timer?.cancel(); timer = null
        isRunning = false; endsAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { timer?.cancel(); isRunning = false; super.onDestroy() }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "EXE Focus", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false); setSound(null, null); enableVibration(false) })
        } catch (_: Exception) {}
    }

    private fun buildNotif(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, FocusService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = if (label.isNotEmpty()) "Focus: $label" else getString(R.string.focus_running)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
    }

    private fun updateNotif(text: String) {
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotif(text)) }
        catch (_: Exception) {}
    }
}

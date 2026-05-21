package com.android.exe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.android.exe.R
import com.android.exe.data.PetDatabase
import com.android.exe.overlay.PetOverlayManager
import com.android.exe.util.PetFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PetForegroundService : Service() {

    companion object {
        private const val TAG = "PetForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "pet_channel"

        const val ACTION_START          = "com.android.exe.action.START"
        const val ACTION_STOP           = "com.android.exe.action.STOP"
        const val ACTION_RELOAD_AVATAR  = "com.android.exe.action.RELOAD_AVATAR"
        const val EXTRA_AVATAR_URI      = "avatar_uri"
        const val EXTRA_MODEL_URI       = "model_uri"
    }

    private var overlayManager: PetOverlayManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val db by lazy { PetDatabase.getInstance(this) }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        // Always post the foreground notification immediately
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> {
                // Extra URIs from the old simple MainActivity (SetupActivity path)
                val avatarUriStr = intent.getStringExtra(EXTRA_AVATAR_URI)
                val modelUriStr  = intent.getStringExtra(EXTRA_MODEL_URI)
                if (!avatarUriStr.isNullOrBlank() || !modelUriStr.isNullOrBlank()) {
                    // Persist into DB then start overlay
                    scope.launch {
                        persistUrisToDb(avatarUriStr, modelUriStr)
                        startOverlayFromDb()
                    }
                } else {
                    // Started without extras — read paths from DB (normal path)
                    scope.launch { startOverlayFromDb() }
                }
            }

            ACTION_RELOAD_AVATAR -> {
                Log.d(TAG, "Reloading avatar from DB")
                scope.launch {
                    val profile = db.petProfileDao().getActive()
                    val path = profile?.avatarPath
                    if (!path.isNullOrBlank()) {
                        overlayManager?.loadAvatar(path)
                    } else {
                        Log.w(TAG, "RELOAD_AVATAR: no avatar path in DB")
                    }
                }
            }

            ACTION_STOP, null -> {
                Log.d(TAG, "Stopping service")
                stopOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // ── Overlay management ────────────────────────────────────────────────────

    private suspend fun startOverlayFromDb() {
        if (overlayManager != null) {
            Log.w(TAG, "Overlay already running")
            return
        }
        val profile = db.petProfileDao().getActive()
        val avatarPath = profile?.avatarPath
        Log.d(TAG, "Starting overlay. avatarPath=$avatarPath")
        overlayManager = PetOverlayManager(this)
        overlayManager!!.attach(avatarPath)
    }

    private fun stopOverlay() {
        try {
            overlayManager?.detach()
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching overlay", e)
        }
        overlayManager = null
    }

    // ── Persist content:// URIs to app-private storage and DB ─────────────────

    private suspend fun persistUrisToDb(avatarUriStr: String?, modelUriStr: String?) {
        val profile = db.petProfileDao().getActive() ?: run {
            Log.e(TAG, "No active pet profile — cannot persist URIs")
            return
        }

        if (!avatarUriStr.isNullOrBlank()) {
            try {
                val uri  = Uri.parse(avatarUriStr)
                val path = PetFileManager.importFile(this, uri, "avatar.vrm")
                if (path != null) {
                    db.petProfileDao().setAvatarPath(profile.id, path)
                    Log.d(TAG, "Avatar persisted to $path")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist avatar URI", e)
            }
        }

        if (!modelUriStr.isNullOrBlank()) {
            try {
                val uri  = Uri.parse(modelUriStr)
                val path = PetFileManager.importFile(this, uri, "model.gguf")
                if (path != null) {
                    db.petProfileDao().setModelPath(profile.id, path)
                    Log.d(TAG, "Model persisted to $path")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist model URI", e)
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android.EXE")
            .setContentText("Your pet is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Pet Companion",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps your AI pet running"
                    setShowBadge(false)
                }
            )
        }
    }
}

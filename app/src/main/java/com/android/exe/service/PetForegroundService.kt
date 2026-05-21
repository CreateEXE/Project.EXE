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
import com.android.exe.data.PetDatabase
import com.android.exe.overlay.PetOverlayManager
import com.android.exe.util.PetFileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PetForegroundService : Service() {

    companion object {
        private const val TAG = "PetForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "pet_channel"

        const val ACTION_START         = "com.android.exe.action.START"
        const val ACTION_STOP          = "com.android.exe.action.STOP"
        const val ACTION_RELOAD_AVATAR = "com.android.exe.action.RELOAD_AVATAR"
        const val EXTRA_AVATAR_URI     = "avatar_uri"
        const val EXTRA_MODEL_URI      = "model_uri"
    }

    private var overlayManager: PetOverlayManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val db by lazy { PetDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> {
                val avatarUriStr = intent.getStringExtra(EXTRA_AVATAR_URI)
                val modelUriStr  = intent.getStringExtra(EXTRA_MODEL_URI)

                scope.launch {
                    // Try to resolve an avatar file path by any means available
                    val avatarPath = resolveAvatarPath(avatarUriStr)
                    Log.d(TAG, "Resolved avatarPath=$avatarPath")

                    // Persist to DB in background (best-effort, don't block startup)
                    if (avatarPath != null) {
                        try {
                            val profile = db.petProfileDao().getActive()
                            if (profile != null) db.petProfileDao().setAvatarPath(profile.id, avatarPath)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not persist avatar path to DB", e)
                        }
                    }
                    if (!modelUriStr.isNullOrBlank()) {
                        try {
                            val profile = db.petProfileDao().getActive()
                            if (profile != null) {
                                val mPath = copyUriToCache(Uri.parse(modelUriStr), "model.gguf")
                                if (mPath != null) db.petProfileDao().setModelPath(profile.id, mPath)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not persist model path to DB", e)
                        }
                    }

                    startOverlay(avatarPath)
                }
            }

            ACTION_RELOAD_AVATAR -> {
                scope.launch {
                    val path = db.petProfileDao().getActive()?.avatarPath
                    if (!path.isNullOrBlank()) {
                        overlayManager?.loadAvatar(path)
                    } else {
                        Log.w(TAG, "RELOAD_AVATAR: no path in DB")
                    }
                }
            }

            ACTION_STOP, null -> {
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

    // ── Path resolution — tries multiple strategies ───────────────────────────

    private suspend fun resolveAvatarPath(uriStr: String?): String? {
        // 1. Try the URI from the intent (copy content:// to cache)
        if (!uriStr.isNullOrBlank()) {
            val path = copyUriToCache(Uri.parse(uriStr), "avatar.vrm")
            if (path != null) return path
            Log.w(TAG, "URI copy failed for: $uriStr")
        }

        // 2. Fall back to path already stored in DB
        val dbPath = db.petProfileDao().getActive()?.avatarPath
        if (!dbPath.isNullOrBlank() && File(dbPath).exists()) {
            Log.d(TAG, "Using DB avatar path: $dbPath")
            return dbPath
        }

        // 3. Fall back to the standard file location PetFileManager uses
        val stdFile = File(filesDir, "avatar.vrm")
        if (stdFile.exists()) {
            Log.d(TAG, "Using standard avatar.vrm from filesDir")
            return stdFile.absolutePath
        }

        Log.w(TAG, "No avatar path could be resolved")
        return null
    }

    /**
     * Copy a content:// (or file://) URI to app cache and return the path.
     * Must be called on a background dispatcher.
     */
    private suspend fun copyUriToCache(uri: Uri, fileName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val dest = File(cacheDir, fileName)
                val input = contentResolver.openInputStream(uri)
                    ?: return@withContext null
                FileOutputStream(dest).use { out -> input.use { it.copyTo(out) } }
                Log.d(TAG, "Copied $uri → ${dest.absolutePath} (${dest.length()} bytes)")
                dest.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "copyUriToCache failed for $uri", e)
                null
            }
        }

    // ── Overlay management ────────────────────────────────────────────────────

    private fun startOverlay(avatarPath: String?) {
        if (overlayManager != null) {
            Log.w(TAG, "Overlay already running")
            return
        }
        Log.d(TAG, "startOverlay avatarPath=$avatarPath")
        overlayManager = PetOverlayManager(this)
        overlayManager!!.attach(avatarPath)
    }

    private fun stopOverlay() {
        try { overlayManager?.detach() } catch (e: Exception) { Log.e(TAG, "detach error", e) }
        overlayManager = null
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
                NotificationChannel(CHANNEL_ID, "Pet Companion", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }
}

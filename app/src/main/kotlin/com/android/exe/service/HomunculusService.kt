package com.android.exe.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.android.exe.R
import com.android.exe.jni.ZeroClawBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HomunculusService: Long-lived foreground service hosting the JNI backend.
 *
 * Responsibilities:
 * 1. Foreground service with persistent notification (evade Low Memory Killer)
 * 2. Initialize and manage JNI lifecycle
 * 3. Host a headless TYPE_APPLICATION_OVERLAY SurfaceView for avatar rendering
 * 4. Handle permission checks and system bridge integration
 *
 * Target device: 6GB RAM, Snapdragon 6 Gen 1 (Android 14)
 */
class HomunculusService : Service() {

    companion object {
        private const val TAG = "HomunculusService"
        private const val NOTIFICATION_ID = 1337
        private const val CHANNEL_ID = "homunculus_service"
    }

    private val isRunning = AtomicBoolean(false)
    private var windowManager: WindowManager? = null
    private var overlayContainer: FrameLayout? = null
    private var jniReady = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HomunculusService.onCreate()")
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "HomunculusService.onStartCommand()")

        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Service already running, ignoring restart")
            return START_STICKY
        }

        // Verify Android 14+ SPECIAL_USE permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!canDrawOverlays()) {
                Log.e(TAG, "SYSTEM_ALERT_WINDOW permission denied")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Start as foreground service (API 26+)
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Foreground service started with notification")

        // Initialize overlay container
        try {
            initializeOverlayContainer()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize overlay container", e)
        }

        // Initialize JNI backend
        try {
            jniReady = ZeroClawBridge.initialize(this)
            Log.i(TAG, "JNI backend initialized: $jniReady")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize JNI backend", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Start inference worker (stub until PersonAI3 integrated)
        startInferenceWorker()

        return START_STICKY
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            checkSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun buildNotification(): Notification {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "AI Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Companion service running"
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open main activity when notification tapped
        val mainActivityIntent = Intent(this, Class.forName("com.android.exe.ui.MainActivity"))
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Companion Active")
            .setContentText("Tap to manage")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVibrate(LongArray(0))  // No vibration
            .setSound(null)  // No sound
            .build()
    }

    /**
     * Initialize headless TYPE_APPLICATION_OVERLAY container.
     * This is a transparent SurfaceView that will host the avatar renderer.
     * Hidden by default; visibility controlled by Mobility oni.
     */
    private fun initializeOverlayContainer() {
        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            )
        }

        layoutParams.gravity = Gravity.BOTTOM or Gravity.END
        layoutParams.x = 0
        layoutParams.y = 0

        overlayContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            // Avatar renderer (WebView or Filament) will be added here
        }

        windowManager?.addView(overlayContainer, layoutParams)
        Log.d(TAG, "Overlay container initialized and added to window manager")
    }

    private fun startInferenceWorker() {
        // Stub: Will be replaced with PersonAI3 SoulSpark.wireInferenceQueue()
        Log.d(TAG, "Inference worker stub (integrate PersonAI3 SoulSpark here)")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // No binder needed for this service
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "HomunculusService.onDestroy()")
        isRunning.set(false)

        // Remove overlay from window manager
        if (overlayContainer != null) {
            try {
                windowManager?.removeView(overlayContainer)
                Log.d(TAG, "Overlay container removed")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }

        // Shutdown JNI backend
        if (jniReady) {
            try {
                ZeroClawBridge.shutdown()
                Log.i(TAG, "JNI backend shutdown")
            } catch (e: Exception) {
                Log.e(TAG, "Error during JNI shutdown", e)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

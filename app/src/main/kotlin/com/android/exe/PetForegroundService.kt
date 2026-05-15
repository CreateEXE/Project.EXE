package com.android.exe

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.net.Uri
import androidx.core.app.NotificationCompat

class PetForegroundService : Service() {

    companion object {
        private const val TAG = "PetForegroundService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "pet_channel"
        const val ACTION_START = "com.android.exe.action.START"
        const val ACTION_STOP = "com.android.exe.action.STOP"
        const val EXTRA_AVATAR_URI = "avatar_uri"
        const val EXTRA_MODEL_URI = "model_uri"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null
    private var webView: WebView? = null
    private var avatarUri: String = ""
    private var modelUri: String = ""

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                avatarUri = intent.getStringExtra(EXTRA_AVATAR_URI) ?: ""
                modelUri = intent.getStringExtra(EXTRA_MODEL_URI) ?: ""
                Log.d(TAG, "Starting overlay with avatar=$avatarUri, model=$modelUri")
                startOverlay()
                startForeground(NOTIFICATION_ID, createNotification())
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stopping overlay")
                stopOverlay()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startOverlay() {
        try {
            if (overlayView != null) {
                Log.w(TAG, "Overlay already exists")
                return
            }

            overlayView = FrameLayout(this)
            overlayView!!.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            // Create WebView for 3D avatar rendering
            webView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "WebView page loaded")
                        if (avatarUri.isNotEmpty()) {
                            loadAvatarIntoWebView(avatarUri)
                        }
                    }
                }
                webChromeClient = WebChromeClient()
            }

            overlayView!!.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                x = 0
                y = 0
            }

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay view added to window manager")

            // Load the avatar renderer HTML
            webView?.loadUrl("file:///android_asset/avatar_renderer.html")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting overlay", e)
        }
    }

    private fun loadAvatarIntoWebView(avatarUri: String) {
        try {
            val contentUri = Uri.parse(avatarUri)
            val js = "window.AvatarAPI.loadModel('$contentUri');"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "Avatar load result: $result")
                }
            } else {
                webView?.loadUrl("javascript:$js")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
        }
    }

    private fun stopOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
                webView = null
                Log.d(TAG, "Overlay view removed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping overlay", e)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android.EXE Pet")
            .setContentText("Your pet is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopOverlay()
        super.onDestroy()
    }
}

package com.android.exe

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

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
    private var statusTextView: TextView? = null
    private var avatarUri: String = ""
    private var modelUri: String = ""
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== Service onCreate() ===")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "=== onStartCommand action=${intent?.action} ===")

        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "✓ Notification posted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "✗ Error posting notification: ${e.message}", e)
        }

        when (intent?.action) {
            ACTION_START -> {
                avatarUri = intent.getStringExtra(EXTRA_AVATAR_URI) ?: ""
                modelUri = intent.getStringExtra(EXTRA_MODEL_URI) ?: ""
                Log.d(TAG, "Avatar URI: $avatarUri")
                Log.d(TAG, "Model URI: $modelUri")
                
                try {
                    startOverlay()
                } catch (e: Exception) {
                    Log.e(TAG, "✗ FATAL ERROR: ${e.message}", e)
                    e.printStackTrace()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received")
                stopOverlay()
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping foreground", e)
                }
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startOverlay() {
        Log.d(TAG, ">>> startOverlay() called")

        if (overlayView != null) {
            Log.w(TAG, "Overlay already running")
            return
        }

        try {
            Log.d(TAG, "Creating FrameLayout...")
            overlayView = FrameLayout(this)
            overlayView!!.setBackgroundColor(Color.TRANSPARENT)

            Log.d(TAG, "Creating status TextView...")
            statusTextView = TextView(this)
            statusTextView!!.text = "🐱 Pet Loading...\n\nPress HOME to minimize"
            statusTextView!!.setTextColor(Color.WHITE)
            statusTextView!!.textSize = 20f
            statusTextView!!.setPadding(32, 64, 32, 64)
            statusTextView!!.setBackgroundColor(Color.argb(200, 30, 30, 40))
            statusTextView!!.setOnClickListener {
                Log.d(TAG, "Status text clicked")
            }

            overlayView!!.addView(statusTextView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))

            Log.d(TAG, "Creating WindowManager params...")
            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                x = 0
                y = 0
            }

            Log.d(TAG, "Adding overlay to window manager...")
            windowManager?.addView(overlayView!!, params)
            Log.d(TAG, "✓ Overlay added successfully!")

            updateStatus("🐱 Pet Active!\n\nAvatar: Loading...\nPress HOME to minimize")

            // Try to load WebView after a short delay
            Thread {
                Thread.sleep(500)
                try {
                    loadWebView()
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading webview", e)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "✗ Exception in startOverlay: ${e.message}", e)
            e.printStackTrace()
            updateStatus("ERROR:\n${e.localizedMessage}")
        }
    }

    private fun loadWebView() {
        Log.d(TAG, ">>> loadWebView() called")
        
        try {
            Log.d(TAG, "Creating WebView...")
            val webView = WebView(this)
            
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "✓ WebView page finished: $url")
                    updateStatus("🐱 Pet Ready!\n\nAvatar: ${File(avatarUri).name}")
                }

                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    Log.d(TAG, "JS: ${consoleMessage?.message()}")
                    return true
                }
            }

            Log.d(TAG, "Adding WebView to overlay...")
            overlayView?.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            Log.d(TAG, "Loading HTML...")
            webView.loadUrl("file:///android_asset/avatar_renderer.html")

        } catch (e: Exception) {
            Log.e(TAG, "✗ Error in loadWebView: ${e.message}", e)
            updateStatus("WebView Error:\n${e.localizedMessage}")
        }
    }

    private fun updateStatus(message: String) {
        try {
            statusTextView?.post {
                statusTextView?.text = message
            }
            Log.d(TAG, "Status: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status", e)
        }
    }

    private fun stopOverlay() {
        Log.d(TAG, ">>> stopOverlay() called")
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                Log.d(TAG, "✓ Overlay removed")
            }
            overlayView = null
            statusTextView = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping overlay", e)
        }
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PetForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐱 Android.EXE Pet Running")
            .setContentText("Tap to return to app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Android.EXE Pet"
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "=== onDestroy() ===")
        stopOverlay()
        super.onDestroy()
    }
}

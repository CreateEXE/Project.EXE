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
    private var webView: WebView? = null
    private var statusTextView: TextView? = null
    private var avatarUri: String = ""
    private var modelUri: String = ""
    private var notificationManager: NotificationManager? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        // Show notification immediately
        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_START -> {
                avatarUri = intent.getStringExtra(EXTRA_AVATAR_URI) ?: ""
                modelUri = intent.getStringExtra(EXTRA_MODEL_URI) ?: ""
                Log.d(TAG, "START_ACTION: avatar=$avatarUri")
                
                try {
                    startOverlay()
                    isRunning = true
                } catch (e: Exception) {
                    Log.e(TAG, "Fatal error in startOverlay", e)
                    isRunning = false
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "STOP_ACTION")
                stopOverlay()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startOverlay() {
        Log.d(TAG, "startOverlay() called")

        try {
            if (overlayView != null) {
                Log.w(TAG, "Overlay already running, skipping")
                return
            }

            // Create container
            overlayView = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Create status text view (fallback if WebView fails)
            statusTextView = TextView(this).apply {
                text = "Loading avatar..."
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                setPadding(16, 16, 16, 16)
                setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
            }
            overlayView!!.addView(statusTextView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))

            // Create WebView
            webView = WebView(this).apply {
                Log.d(TAG, "Creating WebView...")
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    setGeolocationEnabled(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportMultipleWindows(false)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        Log.d(TAG, "WebView onPageStarted: $url")
                        updateStatus("Loading HTML...")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "WebView onPageFinished: $url")
                        updateStatus("HTML loaded, loading avatar...")
                        if (avatarUri.isNotEmpty()) {
                            loadAvatarIntoWebView(avatarUri)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        Log.e(TAG, "WebView error: ${error?.description} URL: ${request?.url}")
                        updateStatus("WebView Error: ${error?.description}")
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        Log.d(TAG, "JS Console [${consoleMessage?.messageLevel()}]: ${consoleMessage?.message()}")
                        return true
                    }
                }
            }

            overlayView!!.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // Add to window manager
            val params = WindowManager.LayoutParams().apply {
                type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                format = PixelFormat.TRANSLUCENT
                flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                x = 0
                y = 0
            }

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay added to window manager successfully")

            // Load HTML
            updateStatus("Loading renderer...")
            webView?.loadUrl("file:///android_asset/avatar_renderer.html")

        } catch (e: Exception) {
            Log.e(TAG, "Exception in startOverlay", e)
            e.printStackTrace()
            updateStatus("Error: ${e.message}")
        }
    }

    private fun loadAvatarIntoWebView(avatarUri: String) {
        try {
            Log.d(TAG, "loadAvatarIntoWebView: $avatarUri")
            
            val uri = Uri.parse(avatarUri)
            val cacheFile = copyUriToCache(uri)
            
            if (cacheFile == null) {
                updateStatus("Error: Cannot access avatar file")
                Log.e(TAG, "Failed to copy avatar to cache")
                return
            }

            val fileUri = Uri.fromFile(cacheFile).toString()
            Log.d(TAG, "Avatar at: $fileUri")
            updateStatus("Avatar ready: ${cacheFile.name}")

            // Call JavaScript
            val js = "if(window.AvatarAPI) { window.AvatarAPI.loadModel('$fileUri'); } else { console.error('AvatarAPI not ready'); }"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "JS result: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                webView?.loadUrl("javascript:$js")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAvatarIntoWebView", e)
            e.printStackTrace()
            updateStatus("Error loading avatar: ${e.message}")
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            Log.d(TAG, "copyUriToCache: $uri")
            
            val cacheDir = cacheDir
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "avatar_${System.currentTimeMillis()}.model")
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "File copied: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            tempFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to cache", e)
            e.printStackTrace()
            null
        }
    }

    private fun updateStatus(message: String) {
        try {
            statusTextView?.post {
                statusTextView?.text = message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status: $e")
        }
    }

    private fun stopOverlay() {
        Log.d(TAG, "stopOverlay()")
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
                Log.d(TAG, "Overlay removed")
            }
            overlayView = null
            webView = null
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
            .setContentTitle("🐱 Android.EXE Pet")
            .setContentText("Your pet is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(0, "Stop Pet", stopPendingIntent)
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
                description = "Android.EXE Pet Overlay"
                enableVibration(false)
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        stopOverlay()
        super.onDestroy()
    }
}

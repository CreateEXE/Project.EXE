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
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate()")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")

        // CRITICAL: Show notification immediately for foreground service
        try {
            val notification = createNotification()
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground service started with notification")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing notification", e)
        }

        when (intent?.action) {
            ACTION_START -> {
                avatarUri = intent.getStringExtra(EXTRA_AVATAR_URI) ?: ""
                modelUri = intent.getStringExtra(EXTRA_MODEL_URI) ?: ""
                Log.d(TAG, "START_ACTION: avatar=$avatarUri")
                
                try {
                    startOverlay()
                } catch (e: Exception) {
                    Log.e(TAG, "Fatal error in startOverlay", e)
                    e.printStackTrace()
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "STOP_ACTION")
                stopOverlay()
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
                Log.w(TAG, "Overlay already running")
                return
            }

            overlayView = FrameLayout(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // Status text
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

            // WebView
            webView = WebView(this).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "HTML loaded: $url")
                        updateStatus("HTML ready, loading avatar...")
                        if (avatarUri.isNotEmpty()) {
                            loadAvatarIntoWebView(avatarUri)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        Log.e(TAG, "WebView error: ${error?.description}")
                        updateStatus("Error: ${error?.description}")
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        Log.d(TAG, "JS: ${consoleMessage?.message()}")
                        return true
                    }
                }
            }

            overlayView!!.addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

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
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
            }

            windowManager?.addView(overlayView, params)
            Log.d(TAG, "Overlay added to window")

            updateStatus("Loading renderer...")
            webView?.loadUrl("file:///android_asset/avatar_renderer.html")

        } catch (e: Exception) {
            Log.e(TAG, "Error in startOverlay", e)
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
                updateStatus("Error: Cannot read avatar file")
                return
            }

            Log.d(TAG, "Avatar file: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            updateStatus("Rendering avatar...")

            // Read file and convert to data URL for WebView
            val base64 = Base64.getEncoder().encodeToString(cacheFile.readBytes())
            val dataUrl = "data:model/gltf-binary;base64,$base64"
            
            val js = """
                setTimeout(() => {
                    if(window.AvatarAPI) {
                        console.log('Loading model from data URL...');
                        window.AvatarAPI.loadModel('$dataUrl');
                    } else {
                        console.error('AvatarAPI not available');
                    }
                }, 500);
            """.trimIndent()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView?.evaluateJavascript(js) { result ->
                    Log.d(TAG, "JS executed: $result")
                }
            } else {
                @Suppress("DEPRECATION")
                webView?.loadUrl("javascript:$js")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
            e.printStackTrace()
            updateStatus("Error: ${e.localizedMessage}")
        }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val cacheDir = cacheDir
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "avatar_${System.currentTimeMillis()}")
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "File cached: ${tempFile.absolutePath}")
            tempFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error copying to cache", e)
            null
        }
    }

    private fun updateStatus(message: String) {
        statusTextView?.post {
            statusTextView?.text = message
            Log.d(TAG, "Status: $message")
        }
    }

    private fun stopOverlay() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager?.removeView(overlayView)
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
            .setContentText("Your pet is running in the background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Android.EXE Pet overlay notifications"
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }
}

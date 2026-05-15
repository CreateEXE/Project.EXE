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
import android.widget.Toast
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
    private var avatarUri: String = ""
    private var modelUri: String = ""
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                avatarUri = intent?.getStringExtra(EXTRA_AVATAR_URI) ?: ""
                modelUri = intent?.getStringExtra(EXTRA_MODEL_URI) ?: ""
                Log.d(TAG, "Starting overlay with avatar=$avatarUri, model=$modelUri")
                
                // MUST show notification immediately for foreground service
                startForeground(NOTIFICATION_ID, createNotification())
                
                startOverlay()
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
                    allowFileAccess = true
                    allowContentAccess = true
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d(TAG, "WebView page loaded: $url")
                        if (avatarUri.isNotEmpty()) {
                            loadAvatarIntoWebView(avatarUri)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        Log.e(TAG, "WebView error: ${error?.description} for ${request?.url}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                        Log.d(TAG, "WebView console: ${consoleMessage?.message()}")
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
            val htmlPath = "file:///android_asset/avatar_renderer.html"
            Log.d(TAG, "Loading HTML from: $htmlPath")
            webView?.loadUrl(htmlPath)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting overlay", e)
            e.printStackTrace()
        }
    }

    private fun loadAvatarIntoWebView(avatarUri: String) {
        try {
            Log.d(TAG, "Loading avatar: $avatarUri")
            
            val uri = Uri.parse(avatarUri)
            val file = copyUriToTempFile(uri)
            
            if (file != null && file.exists()) {
                val fileUri = "file://" + file.absolutePath
                Log.d(TAG, "Avatar copied to: $fileUri")
                
                val js = "window.AvatarAPI.loadModel('$fileUri');"
                Log.d(TAG, "Executing JS: $js")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    webView?.evaluateJavascript(js) { result ->
                        Log.d(TAG, "Avatar load result: $result")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    webView?.loadUrl("javascript:$js")
                }
            } else {
                Log.e(TAG, "Failed to copy avatar file to temp")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading avatar", e)
            e.printStackTrace()
        }
    }

    private fun copyUriToTempFile(uri: Uri): File? {
        return try {
            val cacheDir = cacheDir
            val tempFile = File(cacheDir, "avatar_temp_${System.currentTimeMillis()}.vrm")
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "File copied to cache: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to temp file", e)
            e.printStackTrace()
            null
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

        val stopIntent = Intent(this, PetForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android.EXE Pet")
            .setContentText("Your pet is running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pet Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for the Android.EXE Pet service"
                enableVibration(false)
                enableLights(false)
            }
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopOverlay()
        super.onDestroy()
    }
}

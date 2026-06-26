package com.android.exe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.android.exe.jni.ZeroClawBridge

/**
 * Application class: initializes JNI, notification channels, and crash handlers.
 * Runs once per process lifecycle.
 */
class HomunculusApplication : Application() {

    companion object {
        private const val TAG = "HomunculusApplication"
        private const val CHANNEL_ID = "homunculus_service"
        private const val CHANNEL_NAME = "AI Companion"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HomunculusApplication.onCreate()")

        // Load native library
        try {
            System.loadLibrary("zeroclaw")
            Log.i(TAG, "libzeroclaw.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libzeroclaw.so: ${e.message}")
            throw RuntimeException("Native JNI library not found", e)
        }

        // Initialize JNI bridge
        try {
            ZeroClawBridge.initialize(this)
            Log.i(TAG, "ZeroClawBridge initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ZeroClawBridge: ${e.message}", e)
        }

        // Create notification channels for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannels()
        }

        // Install global exception handler
        installCrashHandler()
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Main foreground service channel
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AI companion active"
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: $CHANNEL_ID")
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            // Call native cleanup before crash
            try {
                ZeroClawBridge.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "Error during crash cleanup", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

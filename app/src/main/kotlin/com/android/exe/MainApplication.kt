// File: app/src/main/kotlin/com/android/exe/MainApplication.kt
package com.android.exe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.android.exe.ai.LlamaBridge

class MainApplication : Application() {

    companion object {
        private const val TAG = "MainApplication"
        lateinit var instance: MainApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        // Warm up the native library; LlamaBridge handles the actual loadLibrary call
        LlamaBridge.ensureLibrary()
        Log.d(TAG, "MainApplication initialized")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    AndroidExeApp.CHANNEL_ID_PET,
                    "Pet Companion",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps your AI pet running"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    AndroidExeApp.CHANNEL_ID_ALERT,
                    "Pet Reactions",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Pet speech bubbles and alerts"
                }
            )
        }
    }

    override fun onTerminate() {
        Log.d(TAG, "MainApplication terminating")
        super.onTerminate()
    }
}

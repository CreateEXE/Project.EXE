package com.android.exe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class AndroidExeApp : Application() {

    companion object {
        const val CHANNEL_ID_PET    = "android_exe_pet"
        const val CHANNEL_ID_ALERT  = "android_exe_alert"
        lateinit var instance: AndroidExeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_PET,
                    "Pet Companion",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps your AI pet running"
                    setShowBadge(false)
                }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_ALERT,
                    "Pet Reactions",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Pet speech bubbles and alerts"
                }
            )
        }
    }
}

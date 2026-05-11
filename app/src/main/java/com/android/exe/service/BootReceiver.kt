package com.android.exe.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // Only auto-start if overlay permission is granted
        if (!Settings.canDrawOverlays(context)) {
            Log.w("BootReceiver", "Overlay permission not granted — skipping auto-start")
            return
        }

        val serviceIntent = Intent(context, PetForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        Log.i("BootReceiver", "Started PetForegroundService after boot")
    }
}

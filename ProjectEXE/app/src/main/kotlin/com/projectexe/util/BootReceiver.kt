package com.projectexe.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.projectexe.overlay.OverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("EXE.Boot", "Boot — restarting overlay")
            try { ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(OverlayService.ACTION_START)) }
            catch (e: Exception) { Log.e("EXE.Boot", "Failed: ${e.message}") }
        }
    }
}

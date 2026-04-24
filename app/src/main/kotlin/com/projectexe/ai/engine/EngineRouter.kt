package com.projectexe.ai.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.projectexe.util.UserPreferenceManager

/** Picks online vs offline engine based on user preference + network state. */
class EngineRouter(
    private val appCtx: Context,
    private val online: LlmEngine,
    private val offline: LlmEngine,
    private val prefs: UserPreferenceManager
) {
    companion object { private const val TAG = "EXE.EngineRouter" }

    suspend fun pick(): LlmEngine {
        return when (prefs.engineMode) {
            UserPreferenceManager.MODE_ONLINE  -> online
            UserPreferenceManager.MODE_OFFLINE -> offline
            else -> {
                val net = networkAvailable()
                val onlineOk = net && online.isAvailable()
                if (onlineOk) online
                else {
                    Log.i(TAG, "Online unavailable (net=$net) — falling back to offline")
                    offline
                }
            }
        }
    }

    private fun networkAvailable(): Boolean = try {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n  = cm.activeNetwork ?: return false
        val c  = cm.getNetworkCapabilities(n) ?: return false
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }
}

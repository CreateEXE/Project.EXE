package com.projectexe.ai.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.projectexe.util.UserPreferenceManager

/**
 * Two roles (Persona, Factual). Each role has its own pair of (online, offline)
 * engine instances and its own engine-mode preference. The router picks the
 * right one at the right time.
 */
class EngineRouter(
    private val appCtx: Context,
    private val prefs: UserPreferenceManager,
    private val onlinePersona:  OnlineEngine,
    private val onlineFactual:  OnlineEngine,
    private val offlinePersona: OfflineEngine,
    private val offlineFactual: OfflineEngine
) {
    companion object { private const val TAG = "EXE.EngineRouter" }

    fun online(role: UserPreferenceManager.Role): OnlineEngine =
        if (role == UserPreferenceManager.Role.PERSONA) onlinePersona else onlineFactual

    fun offline(role: UserPreferenceManager.Role): OfflineEngine =
        if (role == UserPreferenceManager.Role.PERSONA) offlinePersona else offlineFactual

    suspend fun pick(role: UserPreferenceManager.Role): LlmEngine {
        val mode = prefs.engineMode(role)
        return when (mode) {
            UserPreferenceManager.MODE_ONLINE  -> online(role)
            UserPreferenceManager.MODE_OFFLINE -> offline(role)
            else -> {
                val net = networkAvailable()
                val on  = online(role)
                if (net && on.isAvailable()) on
                else {
                    Log.i(TAG, "[$role] online unusable (net=$net), using offline")
                    offline(role)
                }
            }
        }
    }

    /** Backwards-compat: defaults to PERSONA. */
    suspend fun pick(): LlmEngine = pick(UserPreferenceManager.Role.PERSONA)

    private fun networkAvailable(): Boolean = try {
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n  = cm.activeNetwork ?: return false
        val c  = cm.getNetworkCapabilities(n) ?: return false
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) { false }
}

package com.projectexe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.projectexe.ai.soul.SoulHemisphere
import com.projectexe.api.OpenRouterClient
import com.projectexe.memory.MemoryDatabase
import com.projectexe.util.UserPreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ProjectEXEApplication : Application() {
    companion object {
        const val TAG             = "EXE.Application"
        const val CHANNEL_OVERLAY = "exe_overlay_channel"
        const val CHANNEL_AI      = "exe_ai_status_channel"
        val applicationScope      = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var instance: ProjectEXEApplication
            private set
    }

    val memoryDatabase: MemoryDatabase    by lazy { MemoryDatabase.getInstance(this) }
    val openRouterClient: OpenRouterClient by lazy { OpenRouterClient.create() }
    val userPrefs: UserPreferenceManager  by lazy { UserPreferenceManager(this) }
    val soulHemisphere: SoulHemisphere    by lazy {
        SoulHemisphere(memoryDao = memoryDatabase.memoryDao(), scope = applicationScope)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Project EXE starting")
        registerNotificationChannels()
        applicationScope.launch {
            try { soulHemisphere.initialize() }
            catch (e: Exception) { Log.e(TAG, "Soul warm-up failed", e) }
        }
    }

    private fun registerNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_OVERLAY, "EXE Companion Overlay",
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); enableVibration(false); setSound(null, null)
            },
            NotificationChannel(CHANNEL_AI, "EXE AI Status",
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        ))
    }

    override fun onLowMemory()              { super.onLowMemory();  soulHemisphere.trimMemoryCache() }
    override fun onTrimMemory(level: Int)   { super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE)    soulHemisphere.trimMemoryCache() }
}

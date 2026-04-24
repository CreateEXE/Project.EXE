package com.projectexe

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.projectexe.ai.engine.EngineRouter
import com.projectexe.ai.engine.OfflineEngine
import com.projectexe.ai.engine.OnlineEngine
import com.projectexe.ai.engine.local.LlamaCpp
import com.projectexe.ai.soul.SoulHemisphere
import com.projectexe.ai.tools.Tool
import com.projectexe.ai.tools.ToolRegistry
import com.projectexe.ai.tools.impl.CalendarTool
import com.projectexe.ai.tools.impl.ConnectivityTool
import com.projectexe.ai.tools.impl.EmailTool
import com.projectexe.ai.tools.impl.FocusTool
import com.projectexe.ai.tools.impl.GuideTool
import com.projectexe.ai.tools.impl.ReminderTool
import com.projectexe.ai.tools.impl.SettingsTool
import com.projectexe.ai.tools.impl.VirusScanTool
import com.projectexe.ai.tools.impl.WeatherTool
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

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(listOf<Tool>(
            ConnectivityTool(this),
            WeatherTool(this, userPrefs),
            EmailTool(this),
            CalendarTool(this),
            ReminderTool(this),
            FocusTool(this),
            SettingsTool(this),
            GuideTool(this),
            VirusScanTool(this)
        ))
    }

    /** One shared native llama.cpp instance — internally manages two slots. */
    val llamaCpp: LlamaCpp by lazy { LlamaCpp(this) }

    /**
     * The router holds two pairs of engines (Persona + Factual), each pair
     * having an online and an offline variant. Per-role engine mode is read
     * from prefs at pick() time.
     */
    val engineRouter: EngineRouter by lazy {
        openRouterClient.applyOverrides(userPrefs.apiKeyOverride, userPrefs.modelOverride(UserPreferenceManager.Role.PERSONA))
        EngineRouter(
            appCtx = this,
            prefs  = userPrefs,
            onlinePersona  = OnlineEngine(openRouterClient,
                modelOverride      = userPrefs.modelOverride(UserPreferenceManager.Role.PERSONA),
                defaultTemperature = 0.80, defaultMaxTokens = 512),
            onlineFactual  = OnlineEngine(openRouterClient,
                modelOverride      = userPrefs.modelOverride(UserPreferenceManager.Role.FACTUAL),
                defaultTemperature = 0.20, defaultMaxTokens = 700),
            offlinePersona = OfflineEngine(this, userPrefs, UserPreferenceManager.Role.PERSONA, llamaCpp),
            offlineFactual = OfflineEngine(this, userPrefs, UserPreferenceManager.Role.FACTUAL, llamaCpp)
        )
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

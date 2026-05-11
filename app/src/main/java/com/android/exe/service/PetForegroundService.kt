package com.android.exe.service

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.android.exe.AndroidExeApp
import com.android.exe.R
import com.android.exe.accessibility.PetAccessibilityService
import com.android.exe.accessibility.ScreenContext
import com.android.exe.ai.LlamaBridge
import com.android.exe.ai.PetEmotion
import com.android.exe.ai.PetReactionEngine
import com.android.exe.data.PetDatabase
import com.android.exe.data.entities.PetProfile
import com.android.exe.data.entities.PersonalityTraits
import com.android.exe.overlay.PetOverlayManager
import com.android.exe.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class PetForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "PetService"
        const val ACTION_STOP          = "com.android.exe.STOP"
        const val ACTION_RELOAD_AVATAR = "com.android.exe.RELOAD_AVATAR"
        const val ACTION_RELOAD_MODEL  = "com.android.exe.RELOAD_MODEL"
        const val NOTIF_ID = 1001
        private const val REACTION_COOLDOWN_MS = 15_000L
    }

    private val db by lazy { PetDatabase.getInstance(this) }
    private val llama by lazy { LlamaBridge() }
    private val reactionEngine by lazy { PetReactionEngine(llama) }
    private lateinit var overlay: PetOverlayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var profile: PetProfile? = null
    private var traits: PersonalityTraits? = null
    private var lastReactionMs = 0L

    override fun onCreate() {
        super.onCreate()
        overlay = PetOverlayManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP          -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RELOAD_AVATAR -> { lifecycleScope.launch { reloadAvatar() }; return START_STICKY }
            ACTION_RELOAD_MODEL  -> { lifecycleScope.launch { reloadModel()  }; return START_STICKY }
        }
        startForeground(NOTIF_ID, buildNotification())
        lifecycleScope.launch { initialize() }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        overlay.detach()
        lifecycleScope.launch(NonCancellable) { llama.free() }
        super.onDestroy()
    }

    private suspend fun initialize() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping"); stopSelf(); return
        }

        var p = db.petProfileDao().getActive()
        if (p == null) {
            val id = db.petProfileDao().insert(PetProfile(petName = "Exe"))
            p = db.petProfileDao().getActive()!!
            db.personalityTraitsDao().insert(PersonalityTraits(petId = id))
        }
        profile = p
        traits  = db.personalityTraitsDao().getForPet(p.id)

        withContext(Dispatchers.Main) { overlay.attach(p.avatarPath) }

        p.llmModelPath?.let { path ->
            val ok = llama.load(path)
            Log.i(TAG, "LLM load=$ok  path=$path")
        }

        subscribeToScreenEvents()
        Log.i(TAG, "Ready — pet=${p.petName}")
    }

    private suspend fun reloadAvatar() {
        val p = db.petProfileDao().getActive() ?: return
        profile = p
        val path = p.avatarPath ?: return
        withContext(Dispatchers.Main) { overlay.loadAvatar(path) }
    }

    private suspend fun reloadModel() {
        val p = db.petProfileDao().getActive() ?: return
        profile = p
        p.llmModelPath?.let { llama.load(it) }
    }

    private fun subscribeToScreenEvents() {
        PetAccessibilityService.screenFlow
            .onEach { ctx -> handleScreenContext(ctx) }
            .catch  { e   -> Log.e(TAG, "screenFlow error", e) }
            .launchIn(lifecycleScope)
    }

    private suspend fun handleScreenContext(ctx: ScreenContext) {
        val now = System.currentTimeMillis()
        if (now - lastReactionMs < REACTION_COOLDOWN_MS) return
        lastReactionMs = now

        val p = profile ?: return

        if (!llama.isLoaded()) {
            val idle = listOf(PetEmotion.HAPPY, PetEmotion.RELAXED, PetEmotion.SURPRISED)
            withContext(Dispatchers.Main) { overlay.playExpression(idle.random()) }
            return
        }

        val memories = db.petMemoryDao().getRecent(p.id)
        val history  = db.interactionHistoryDao().getRecent(p.id)

        try {
            var accumulated = ""
            val reaction = reactionEngine.react(
                profile       = p,
                traits        = traits,
                memories      = memories,
                recentHistory = history,
                screenCtx     = ctx,
                // Called from IO/native thread — post to main
                onToken = { token ->
                    accumulated += token
                    val snap = accumulated
                    mainHandler.post { overlay.showSpeechBubble(snap, 8000L) }
                }
            )
            withContext(Dispatchers.Main) {
                overlay.playExpression(reaction.emotion)
                overlay.showSpeechBubble(reaction.text, 6000L)
            }
            db.interactionHistoryDao().insert(reaction.record)
            db.petProfileDao().bumpInteractionCount(p.id)
            db.interactionHistoryDao().pruneOld(p.id)
        } catch (e: Exception) {
            Log.e(TAG, "Reaction failed", e)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AndroidExeApp.CHANNEL_ID_PET)
            .setContentTitle("Exe is active")
            .setContentText("Your AI companion is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }
}

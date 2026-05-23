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
import com.android.exe.MainActivity
import com.android.exe.R
import com.android.exe.accessibility.PetAccessibilityService
import com.android.exe.ai.*
import com.android.exe.data.PetDatabase
import com.android.exe.data.entities.PetProfile
import com.android.exe.data.entities.PersonalityTraits
import com.android.exe.overlay.PetOverlayManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

class PetForegroundService : LifecycleService() {

    companion object {
        private const val TAG                 = "PetService"
        const val ACTION_START                = "com.android.exe.action.START"
        const val ACTION_STOP                 = "com.android.exe.action.STOP"
        const val ACTION_RELOAD_AVATAR        = "com.android.exe.RELOAD_AVATAR"
        const val ACTION_RELOAD_MODEL         = "com.android.exe.RELOAD_MODEL"
        const val EXTRA_AVATAR_URI            = "avatar_uri"
        const val EXTRA_MODEL_URI             = "model_uri"
        const val NOTIFICATION_ID             = 1001
        private const val REACTION_COOLDOWN_MS = 15_000L
    }

    private val db by lazy { PetDatabase.getInstance(this) }
    private val llama by lazy { LlamaBridge() }
    private val soulManager by lazy { FaitSoulManager(this) }

    private lateinit var droneSwarm:     DroneSwarm
    private lateinit var emotionDaemon:  EmotionDaemon
    private lateinit var reactionEngine: PetReactionEngine

    private var overlayManager: PetOverlayManager? = null
    private val scope       = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var profile:       PetProfile?        = null
    private var traits:        PersonalityTraits? = null
    private var lastReactionMs = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_START -> {
                val avatarUriStr = intent.getStringExtra(EXTRA_AVATAR_URI)
                val modelUriStr  = intent.getStringExtra(EXTRA_MODEL_URI)
                scope.launch {
                    val avatarPath = resolveAvatarPath(avatarUriStr)
                    if (avatarPath != null) {
                        try {
                            val p = db.petProfileDao().getActive()
                            if (p != null) db.petProfileDao().setAvatarPath(p.id, avatarPath)
                        } catch (e: Exception) { Log.w(TAG, "Avatar path persist failed", e) }
                    }
                    if (!modelUriStr.isNullOrBlank()) {
                        try {
                            val p = db.petProfileDao().getActive()
                            if (p != null) {
                                val mPath = copyUriToCache(android.net.Uri.parse(modelUriStr), "model.gguf")
                                if (mPath != null) db.petProfileDao().setModelPath(p.id, mPath)
                            }
                        } catch (e: Exception) { Log.w(TAG, "Model path persist failed", e) }
                    }
                    startOverlay(avatarPath)
                }
            }

            ACTION_RELOAD_AVATAR -> scope.launch {
                val path = db.petProfileDao().getActive()?.avatarPath
                if (!path.isNullOrBlank()) overlayManager?.loadAvatar(path)
                else Log.w(TAG, "RELOAD_AVATAR: no path in DB")
            }

            ACTION_RELOAD_MODEL -> scope.launch {
                val path = db.petProfileDao().getActive()?.llmModelPath
                if (!path.isNullOrBlank()) { llama.load(path); Log.i(TAG, "Model reloaded: $path") }
                else Log.w(TAG, "RELOAD_MODEL: no path in DB")
            }

            ACTION_STOP -> {
                teardownDaemons(); stopOverlay(); stopSelf()
            }

            null -> {
                Log.d(TAG, "Normal startup — loading from database")
                scope.launch { initialize() }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        teardownDaemons()
        stopOverlay()
        lifecycleScope.launch(NonCancellable) { llama.free() }
        super.onDestroy()
    }

    private suspend fun initialize() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping"); stopSelf(); return
        }

        var p = db.petProfileDao().getActive()
        if (p == null) {
            val id = db.petProfileDao().insert(PetProfile(petName = "Fait"))
            p = db.petProfileDao().getActive()!!
            db.personalityTraitsDao().insert(PersonalityTraits(petId = id))
        }
        profile = p
        traits  = db.personalityTraitsDao().getForPet(p.id)

        withContext(Dispatchers.IO) { soulManager.initialize() }

        droneSwarm = DroneSwarm(
            llama              = llama,
            memoryDao          = db.petMemoryDao(),
            onExpressionUpdate = { name, _, _ ->
                mainHandler.post { overlayManager?.playExpression(PetEmotion.fromTag(name)) }
            }
        )

        val initialMood = MoodVector(
            valence   = p.currentMood,
            arousal   = p.energyLevel,
            dominance = 0.55f
        )
        emotionDaemon = EmotionDaemon(
            petProfileDao = db.petProfileDao(),
            droneSwarm    = droneSwarm
        )
        emotionDaemon.start(petId = p.id, initialMood = initialMood)

        reactionEngine = PetReactionEngine(llama, soulManager)

        withContext(Dispatchers.Main) { startOverlay(p.avatarPath) }

        p.llmModelPath?.let { path ->
            if (File(path).exists()) {
                val ok = llama.load(path)
                Log.i(TAG, "LLM load=$ok  path=$path")
            } else {
                Log.w(TAG, "Model path in DB but file missing: $path")
            }
        }

        subscribeToScreenEvents()
        Log.i(TAG, "Ready — ${p.petName}  mood=${emotionDaemon.currentMood.toDebugString()}")
    }

    private fun teardownDaemons() {
        if (::emotionDaemon.isInitialized) emotionDaemon.stop()
        if (::droneSwarm.isInitialized)    droneSwarm.cancel()
        Log.i(TAG, "Daemons torn down")
    }

    private fun subscribeToScreenEvents() {
        PetAccessibilityService.screenFlow
            .onEach  { ctx -> handleScreenContext(ctx) }
            .catch   { e   -> Log.e(TAG, "screenFlow error", e) }
            .launchIn(lifecycleScope)
    }

    private suspend fun handleScreenContext(ctx: com.android.exe.accessibility.ScreenContext) {
        val now = System.currentTimeMillis()

        // Phase 1: Sentiment + mood update — no LLM, no cooldown, always fires
        val sentimentScore = droneSwarm.fireSentimentDrone(ctx.summary)
        if (::emotionDaemon.isInitialized) {
            emotionDaemon.onEvent(MoodEvent.UserSentiment(sentimentScore))
            emotionDaemon.onEvent(MoodEvent.NewApp)
        }

        // Phase 2: Live-tier LLM reaction — cooldown-gated
        if (now - lastReactionMs < REACTION_COOLDOWN_MS) return
        lastReactionMs = now

        val p = profile ?: return

        if (!llama.isLoaded()) {
            val idleEmotion = if (::emotionDaemon.isInitialized)
                emotionDaemon.currentMood.toPetEmotion() else PetEmotion.NEUTRAL
            withContext(Dispatchers.Main) { overlayManager?.playExpression(idleEmotion) }
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
                mood          = if (::emotionDaemon.isInitialized)
                                    emotionDaemon.currentMood else MoodVector(),
                onToken = { token ->
                    accumulated += token
                    val snap = accumulated
                    mainHandler.post { overlayManager?.showSpeechBubble(snap, 8000L) }
                }
            )

            withContext(Dispatchers.Main) {
                overlayManager?.playExpression(reaction.emotion)
                overlayManager?.showSpeechBubble(reaction.text, 6000L)
            }

            if (::emotionDaemon.isInitialized) emotionDaemon.onEvent(MoodEvent.ReactionComplete)

            db.interactionHistoryDao().insert(reaction.record)
            db.petProfileDao().bumpInteractionCount(p.id)
            db.interactionHistoryDao().pruneOld(p.id)

        } catch (e: Exception) {
            Log.e(TAG, "Reaction failed", e)
            if (::emotionDaemon.isInitialized) emotionDaemon.onEvent(MoodEvent.InferenceError)
        }
    }

    private fun startOverlay(avatarPath: String?) {
        if (overlayManager != null) { Log.w(TAG, "Overlay already running"); return }
        overlayManager = PetOverlayManager(this)
        overlayManager!!.attach(avatarPath)
    }

    private fun stopOverlay() {
        try { overlayManager?.detach() } catch (e: Exception) { Log.e(TAG, "detach error", e) }
        overlayManager = null
    }

    private suspend fun resolveAvatarPath(uriStr: String?): String? {
        if (!uriStr.isNullOrBlank()) {
            val path = copyUriToCache(android.net.Uri.parse(uriStr), "avatar.vrm")
            if (path != null) return path
        }
        val dbPath = db.petProfileDao().getActive()?.avatarPath
        if (!dbPath.isNullOrBlank() && File(dbPath).exists()) return dbPath
        val stdFile = File(filesDir, "avatar.vrm")
        if (stdFile.exists()) return stdFile.absolutePath
        return null
    }

    private suspend fun copyUriToCache(uri: android.net.Uri, fileName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val dest  = File(cacheDir, fileName)
                val input = contentResolver.openInputStream(uri) ?: return@withContext null
                java.io.FileOutputStream(dest).use { out -> input.use { it.copyTo(out) } }
                dest.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "copyUriToCache failed for $uri", e)
                null
            }
        }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AndroidExeApp.CHANNEL_ID_PET,
                "Pet Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PetForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, AndroidExeApp.CHANNEL_ID_PET)
            .setContentTitle("Fait is active")
            .setContentText("Tap to open  •  Swipe to stop")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }
}

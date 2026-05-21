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
import java.io.File

class PetForegroundService : LifecycleService() {

    companion object {
        private const val TAG = "PetService"
        const val ACTION_START          = "com.android.exe.action.START"
        const val ACTION_STOP           = "com.android.exe.action.STOP"
        const val ACTION_RELOAD_AVATAR  = "com.android.exe.RELOAD_AVATAR"
        const val ACTION_RELOAD_MODEL   = "com.android.exe.RELOAD_MODEL"
        const val EXTRA_AVATAR_URI      = "avatar_uri"
        const val EXTRA_MODEL_URI       = "model_uri"
        const val NOTIFICATION_ID       = 1001
        private const val REACTION_COOLDOWN_MS = 15_000L
    }

    private val db by lazy { PetDatabase.getInstance(this) }
    private val llama by lazy { LlamaBridge() }
    private val reactionEngine by lazy { PetReactionEngine(llama) }
    private var overlayManager: PetOverlayManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var profile: PetProfile? = null
    private var traits: PersonalityTraits? = null
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
                    Log.d(TAG, "Resolved avatarPath=$avatarPath")

                    if (avatarPath != null) {
                        try {
                            val profile = db.petProfileDao().getActive()
                            if (profile != null) db.petProfileDao().setAvatarPath(profile.id, avatarPath)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not persist avatar path to DB", e)
                        }
                    }
                    if (!modelUriStr.isNullOrBlank()) {
                        try {
                            val profile = db.petProfileDao().getActive()
                            if (profile != null) {
                                val mPath = copyUriToCache(android.net.Uri.parse(modelUriStr), "model.gguf")
                                if (mPath != null) db.petProfileDao().setModelPath(profile.id, mPath)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not persist model path to DB", e)
                        }
                    }

                    startOverlay(avatarPath)
                }
            }

            ACTION_RELOAD_AVATAR -> {
                scope.launch {
                    val path = db.petProfileDao().getActive()?.avatarPath
                    if (!path.isNullOrBlank()) {
                        overlayManager?.loadAvatar(path)
                    } else {
                        Log.w(TAG, "RELOAD_AVATAR: no path in DB")
                    }
                }
            }

            ACTION_RELOAD_MODEL -> {
                scope.launch {
                    val path = db.petProfileDao().getActive()?.llmModelPath
                    if (!path.isNullOrBlank()) {
                        llama.load(path)
                        Log.i(TAG, "Model reloaded from DB: $path")
                    } else {
                        Log.w(TAG, "RELOAD_MODEL: no path in DB")
                    }
                }
            }

            ACTION_STOP, null -> {
                if (intent?.action == null) {
                    Log.d(TAG, "Normal startup - loading from database")
                    scope.launch {
                        initialize()
                    }
                    return START_STICKY
                }
                
                Log.i(TAG, "Received STOP action")
                stopOverlay()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        stopOverlay()
        lifecycleScope.launch(NonCancellable) { llama.free() }
        super.onDestroy()
    }

    private suspend fun initialize() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping")
            stopSelf()
            return
        }

        var p = db.petProfileDao().getActive()
        if (p == null) {
            val id = db.petProfileDao().insert(PetProfile(petName = "Exe"))
            p = db.petProfileDao().getActive()!!
            db.personalityTraitsDao().insert(PersonalityTraits(petId = id))
        }
        profile = p
        traits  = db.personalityTraitsDao().getForPet(p.id)

        Log.d(TAG, "Profile: ${p.petName}, avatar=${p.avatarPath}, model=${p.llmModelPath}")

        val avatarPath = p.avatarPath
        if (!avatarPath.isNullOrBlank()) {
            Log.i(TAG, "Loading persisted avatar: $avatarPath")
            withContext(Dispatchers.Main) { startOverlay(avatarPath) }
        } else {
            Log.w(TAG, "No persisted avatar path - overlay will show 'no path'")
            withContext(Dispatchers.Main) { startOverlay(null) }
        }

        p.llmModelPath?.let { path ->
            if (File(path).exists()) {
                Log.i(TAG, "Loading persisted model: $path")
                val ok = llama.load(path)
                Log.i(TAG, "LLM load=$ok  path=$path")
            } else {
                Log.w(TAG, "Model path in DB but file doesn't exist: $path")
            }
        }

        subscribeToScreenEvents()
        Log.i(TAG, "Ready — pet=${p.petName}")
    }

    private suspend fun resolveAvatarPath(uriStr: String?): String? {
        if (!uriStr.isNullOrBlank()) {
            val path = copyUriToCache(android.net.Uri.parse(uriStr), "avatar.vrm")
            if (path != null) return path
            Log.w(TAG, "URI copy failed for: $uriStr")
        }

        val dbPath = db.petProfileDao().getActive()?.avatarPath
        if (!dbPath.isNullOrBlank() && File(dbPath).exists()) {
            Log.d(TAG, "Using DB avatar path: $dbPath")
            return dbPath
        }

        val stdFile = File(filesDir, "avatar.vrm")
        if (stdFile.exists()) {
            Log.d(TAG, "Using standard avatar.vrm from filesDir")
            return stdFile.absolutePath
        }

        Log.w(TAG, "No avatar path could be resolved")
        return null
    }

    private suspend fun copyUriToCache(uri: android.net.Uri, fileName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val dest = File(cacheDir, fileName)
                val input = contentResolver.openInputStream(uri)
                    ?: return@withContext null
                java.io.FileOutputStream(dest).use { out -> input.use { it.copyTo(out) } }
                Log.d(TAG, "Copied $uri → ${dest.absolutePath} (${dest.length()} bytes)")
                dest.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "copyUriToCache failed for $uri", e)
                null
            }
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
            withContext(Dispatchers.Main) { overlayManager?.playExpression(idle.random()) }
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
            db.interactionHistoryDao().insert(reaction.record)
            db.petProfileDao().bumpInteractionCount(p.id)
            db.interactionHistoryDao().pruneOld(p.id)
        } catch (e: Exception) {
            Log.e(TAG, "Reaction failed", e)
        }
    }

    private fun startOverlay(avatarPath: String?) {
        if (overlayManager != null) {
            Log.w(TAG, "Overlay already running")
            return
        }
        Log.d(TAG, "startOverlay avatarPath=$avatarPath")
        overlayManager = PetOverlayManager(this)
        overlayManager!!.attach(avatarPath)
    }

    private fun stopOverlay() {
        try { overlayManager?.detach() } catch (e: Exception) { Log.e(TAG, "detach error", e) }
        overlayManager = null
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
            .setContentText("Tap to open settings • Swipe to stop")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .build()
    }
}

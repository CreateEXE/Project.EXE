package com.android.exe.ai

import android.util.Log
import com.android.exe.data.dao.PetProfileDao
import kotlinx.coroutines.*

class EmotionDaemon(
    private val petProfileDao: PetProfileDao,
    private val droneSwarm: DroneSwarm
) {
    companion object {
        private const val TAG                = "EmotionDaemon"
        private const val DECAY_INTERVAL_MS  = 30_000L
        private const val DB_WRITE_INTERVAL_MS = 30_000L
        private const val DECAY_ALPHA        = 0.03f
        val BASELINE = MoodVector(valence = 0.60f, arousal = 0.45f, dominance = 0.55f)
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    var currentMood: MoodVector = BASELINE
        private set

    private var petId: Long = 0L
    private var running = false

    fun start(petId: Long, initialMood: MoodVector = BASELINE) {
        if (running) return
        this.petId = petId
        this.currentMood = initialMood
        running = true
        startDecayLoop()
        startDbWriteLoop()
        Log.i(TAG, "EmotionDaemon started. Initial: ${currentMood.toDebugString()}")
    }

    fun stop() {
        running = false
        scope.cancel()
        Log.i(TAG, "EmotionDaemon stopped")
    }

    fun onEvent(event: MoodEvent) {
        val previous = currentMood
        currentMood = applyEvent(event)
        Log.d(TAG, "${event::class.simpleName}: ${previous.toDebugString()} → ${currentMood.toDebugString()}")
        droneSwarm.fireAnimationDrone(currentMood)
    }

    private fun startDecayLoop() = scope.launch {
        while (isActive) {
            delay(DECAY_INTERVAL_MS)
            val before = currentMood
            currentMood = currentMood.lerpTo(BASELINE, DECAY_ALPHA)
            if (before.distanceTo(currentMood) > 0.005f) {
                Log.v(TAG, "Decay: ${currentMood.toDebugString()}")
                droneSwarm.fireAnimationDrone(currentMood)
            }
        }
    }

    private fun startDbWriteLoop() = scope.launch {
        while (isActive) {
            delay(DB_WRITE_INTERVAL_MS)
            try {
                petProfileDao.updateMoodEnergy(petId, currentMood.valence, currentMood.arousal)
                Log.v(TAG, "Mood persisted to DB")
            } catch (e: Exception) {
                Log.e(TAG, "DB mood write failed", e)
            }
        }
    }

    private fun applyEvent(event: MoodEvent): MoodVector = when (event) {
        is MoodEvent.PositiveInteraction -> currentMood.applyDelta(dv = +0.12f, da = +0.08f)
        is MoodEvent.NegativeInteraction -> currentMood.applyDelta(dv = -0.10f, da = +0.15f)
        is MoodEvent.LongIdle            -> currentMood.applyDelta(dv = -0.04f, da = -0.10f)
        is MoodEvent.NewApp              -> currentMood.applyDelta(da = +0.06f)
        is MoodEvent.UserSentiment       -> currentMood.applyDelta(dv = (event.score - 0.5f) * 0.20f)
        is MoodEvent.ReactionComplete    -> currentMood.applyDelta(da = -0.04f)
        is MoodEvent.InferenceError      -> currentMood.applyDelta(dv = -0.03f, da = +0.05f)
    }
}

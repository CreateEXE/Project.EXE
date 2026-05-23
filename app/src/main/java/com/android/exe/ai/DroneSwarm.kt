package com.android.exe.ai

import android.util.Log
import com.android.exe.data.dao.PetMemoryDao
import com.android.exe.data.entities.PetMemory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore

class DroneSwarm(
    private val llama: LlamaBridge,
    private val memoryDao: PetMemoryDao,
    private val onExpressionUpdate: (name: String, weight: Float, durationSec: Float) -> Unit
) {
    companion object {
        private const val TAG                = "DroneSwarm"
        private const val ANIMATION_THRESHOLD = 0.15f
    }

    private val swarmScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val llmSemaphore = Semaphore(1)
    private var lastFiredMood = MoodVector()

    // ── CLASS 0: Pure logic ───────────────────────────────────────────────────

    fun fireAnimationDrone(mood: MoodVector) {
        if (mood.distanceTo(lastFiredMood) < ANIMATION_THRESHOLD) return
        lastFiredMood = mood
        swarmScope.launch {
            val emotion = mood.toPetEmotion()
            onExpressionUpdate(emotion.vrmExpression, emotion.weight, emotion.durationSec)
            Log.d(TAG, "AnimationDrone → ${emotion.name} (${mood.toDebugString()})")
        }
    }

    // ── CLASS 1: DB query ─────────────────────────────────────────────────────

    suspend fun fireMemorySearchDrone(
        petId: Long,
        query: String,
        topK: Int = 5
    ): List<PetMemory> = withContext(Dispatchers.IO) {
        try {
            memoryDao.getRecent(petId, 50)
                .filter {
                    it.memoryKey.contains(query, ignoreCase = true) ||
                    it.memoryValue.contains(query, ignoreCase = true)
                }
                .take(topK)
                .also { Log.d(TAG, "MemorySearchDrone: '$query' → ${it.size} results") }
        } catch (e: Exception) {
            Log.e(TAG, "MemorySearchDrone failed", e)
            emptyList()
        }
    }

    suspend fun fireSentimentDrone(text: String): Float = withContext(Dispatchers.Default) {
        val lower    = text.lowercase()
        val posHits  = POSITIVE_WORDS.count { lower.contains(it) }
        val negHits  = NEGATIVE_WORDS.count { lower.contains(it) }
        val total    = (posHits + negHits).coerceAtLeast(1)
        (posHits.toFloat() / total)
            .also { Log.d(TAG, "SentimentDrone: $it (pos=$posHits neg=$negHits)") }
    }

    // ── CLASS 2: Micro-inference ──────────────────────────────────────────────

    suspend fun fireTagExtractionDrone(text: String): PetEmotion {
        llmSemaphore.acquire()
        return try {
            withContext(Dispatchers.IO) {
                val prompt = buildString {
                    appendLine("<|system|>")
                    appendLine("Extract ONE emotion. Reply ONLY with one tag:")
                    appendLine("[EMOTION:happy] [EMOTION:sad] [EMOTION:angry]")
                    appendLine("[EMOTION:surprised] [EMOTION:relaxed] [EMOTION:neutral]")
                    appendLine("<|end|>")
                    appendLine("<|user|>")
                    appendLine(text.take(200))
                    appendLine("<|end|>")
                    appendLine("<|assistant|>")
                }
                val result = llama.infer(prompt, maxNewTokens = 15)
                val match  = Regex("""\[EMOTION:(\w+)]""", RegexOption.IGNORE_CASE).find(result)
                PetEmotion.fromTag(match?.groupValues?.get(1) ?: "neutral")
                    .also { Log.d(TAG, "TagExtractionDrone → $it") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TagExtractionDrone failed", e)
            PetEmotion.NEUTRAL
        } finally {
            llmSemaphore.release()
        }
    }

    suspend fun fireIntentClassifierDrone(text: String): IntentCategory {
        llmSemaphore.acquire()
        return try {
            withContext(Dispatchers.IO) {
                val prompt = buildString {
                    appendLine("<|system|>")
                    appendLine("Classify the intent. Reply ONLY with one word:")
                    appendLine("QUESTION COMMAND COMPLAINT GREETING GRATITUDE OTHER")
                    appendLine("<|end|>")
                    appendLine("<|user|>")
                    appendLine(text.take(150))
                    appendLine("<|end|>")
                    appendLine("<|assistant|>")
                }
                val result = llama.infer(prompt, maxNewTokens = 5).trim().uppercase()
                IntentCategory.fromString(result)
                    .also { Log.d(TAG, "IntentClassifierDrone → $result") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "IntentClassifierDrone failed", e)
            IntentCategory.OTHER
        } finally {
            llmSemaphore.release()
        }
    }

    fun cancel() {
        swarmScope.cancel()
        Log.i(TAG, "DroneSwarm cancelled")
    }

    private val POSITIVE_WORDS = listOf(
        "good","great","thanks","love","happy","yes","nice","awesome","cool",
        "perfect","please","appreciate","excellent","amazing","wonderful","fantastic","glad"
    )
    private val NEGATIVE_WORDS = listOf(
        "bad","hate","no","wrong","ugh","stop","boring","terrible","annoying",
        "awful","stupid","broken","crash","fail","error","frustrated","angry"
    )
}

enum class IntentCategory {
    QUESTION, COMMAND, COMPLAINT, GREETING, GRATITUDE, OTHER;
    companion object {
        fun fromString(s: String) = entries.firstOrNull { it.name == s } ?: OTHER
    }
}

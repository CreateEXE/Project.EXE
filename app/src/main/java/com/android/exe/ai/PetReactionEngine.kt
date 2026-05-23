package com.android.exe.ai

import android.util.Log
import com.android.exe.accessibility.ScreenContext
import com.android.exe.data.entities.InteractionRecord
import com.android.exe.data.entities.PetMemory
import com.android.exe.data.entities.PersonalityTraits
import com.android.exe.data.entities.PetProfile

// ─── Emotion ──────────────────────────────────────────────────────────────────

enum class PetEmotion(
    val vrmExpression: String,
    val weight: Float = 1.0f,
    val durationSec: Float = 3.0f
) {
    NEUTRAL  ("neutral",   0.0f),
    HAPPY    ("happy",     1.0f, 3.5f),
    SAD      ("sad",       1.0f, 4.0f),
    ANGRY    ("angry",     0.9f, 3.0f),
    SURPRISED("surprised", 1.0f, 2.5f),
    RELAXED  ("relaxed",   0.8f, 4.5f),
    AA ("aa", 0.6f, 0.3f),
    IH ("ih", 0.6f, 0.3f),
    OU ("ou", 0.6f, 0.3f),
    EE ("ee", 0.6f, 0.3f),
    OH ("oh", 0.6f, 0.3f);

    companion object {
        fun fromTag(tag: String): PetEmotion =
            entries.firstOrNull { it.name.equals(tag, ignoreCase = true) } ?: NEUTRAL
    }
}

// ─── Reaction data ────────────────────────────────────────────────────────────

data class PetReaction(
    val text: String,
    val emotion: PetEmotion,
    val record: InteractionRecord
)

// ─── Reaction engine ──────────────────────────────────────────────────────────

/**
 * @param soulManager  optional — if provided, uses FaitSoulManager.compileSystemPrompt()
 *                     for the system block instead of the generic fallback.
 */
class PetReactionEngine(
    private val llama: LlamaBridge,
    private val soulManager: FaitSoulManager? = null
) {
    companion object {
        private const val TAG = "PetReactionEngine"
    }

    suspend fun react(
        profile: PetProfile,
        traits: PersonalityTraits?,
        memories: List<PetMemory>,
        recentHistory: List<InteractionRecord>,
        screenCtx: ScreenContext,
        currentMood: MoodVector = MoodVector(),
        onToken: ((String) -> Unit)? = null
    ): PetReaction {
        val prompt = buildPrompt(profile, traits, memories, recentHistory, screenCtx, currentMood)
        Log.d(TAG, "Prompt length: ${prompt.length} chars")

        val response = llama.infer(prompt, maxNewTokens = 200, onToken = onToken)
        Log.d(TAG, "Raw response: $response")

        val (text, emotion) = parseResponse(response)

        val record = InteractionRecord(
            petId         = profile.id,
            triggerType   = screenCtx.triggerType,
            activePackage = screenCtx.activePackage,
            promptSummary = screenCtx.summary.take(120),
            petResponse   = text,
            emotionPlayed = emotion.name
        )
        return PetReaction(text, emotion, record)
    }

    // ─── Prompt builder ───────────────────────────────────────────────────────

    private fun buildPrompt(
        profile: PetProfile,
        traits: PersonalityTraits?,
        memories: List<PetMemory>,
        recentHistory: List<InteractionRecord>,
        ctx: ScreenContext,
        mood: MoodVector
    ): String = buildString {

        // System block — use soul manager if available, otherwise generic fallback
        val systemBlock = soulManager?.compileSystemPrompt(profile, traits, mood)
            ?: buildFallbackSystemPrompt(profile, traits, mood)

        appendLine("<|system|>")
        append(systemBlock)
        appendLine("<|end|>")
        appendLine()

        if (memories.isNotEmpty()) {
            appendLine("<|memories|>")
            memories.take(10).forEach { appendLine("- ${it.memoryKey}: ${it.memoryValue}") }
            appendLine("<|end|>")
            appendLine()
        }

        if (recentHistory.isNotEmpty()) {
            appendLine("<|recent|>")
            recentHistory.takeLast(3).forEach {
                appendLine("Screen: ${it.activePackage}  |  You said: \"${it.petResponse.take(60)}\"")
            }
            appendLine("<|end|>")
            appendLine()
        }

        appendLine("<|user|>")
        appendLine("App: ${ctx.activePackage}  |  Context: ${ctx.summary.take(200)}")
        appendLine("<|end|>")
        appendLine("<|assistant|>")
    }

    private fun buildFallbackSystemPrompt(
        profile: PetProfile,
        traits: PersonalityTraits?,
        mood: MoodVector
    ): String = buildString {
        appendLine("You are ${profile.petName}, a lively AI companion living on the user's phone screen.")
        appendLine("You observe what the user is doing and react naturally.")
        appendLine()
        appendLine("Current mood: ${mood.toPetEmotion().name.lowercase()} " +
                   "(valence=${mood.valence.fmt()} arousal=${mood.arousal.fmt()})")
        if (t

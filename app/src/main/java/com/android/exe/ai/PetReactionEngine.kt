package com.android.exe.ai

import android.util.Log
import com.android.exe.accessibility.ScreenContext
import com.android.exe.data.entities.InteractionRecord
import com.android.exe.data.entities.PetMemory
import com.android.exe.data.entities.PersonalityTraits
import com.android.exe.data.entities.PetProfile

// ─── Emotion ──────────────────────────────────────────────────────────────────

enum class PetEmotion(
    val vrmExpression: String,   // @pixiv/three-vrm preset name
    val weight: Float = 1.0f,
    val durationSec: Float = 3.0f
) {
    NEUTRAL ("neutral",   0.0f),
    HAPPY   ("happy",     1.0f, 3.5f),
    SAD     ("sad",       1.0f, 4.0f),
    ANGRY   ("angry",     0.9f, 3.0f),
    SURPRISED("surprised",1.0f, 2.5f),
    RELAXED ("relaxed",   0.8f, 4.5f),
    // Mouth shapes for pseudo-lip-sync
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

class PetReactionEngine(
    private val llama: LlamaBridge
) {
    companion object {
        private const val TAG = "PetReactionEngine"
    }

    /**
     * Build a prompt from context and stream inference back.
     * Returns a PetReaction with the full response + detected emotion.
     */
    suspend fun react(
        profile: PetProfile,
        traits: PersonalityTraits?,
        memories: List<PetMemory>,
        recentHistory: List<InteractionRecord>,
        screenCtx: ScreenContext,
        onToken: ((String) -> Unit)? = null
    ): PetReaction {
        val prompt = buildPrompt(profile, traits, memories, recentHistory, screenCtx)
        Log.d(TAG, "Prompt length: ${prompt.length} chars")

        val response = llama.infer(prompt, maxNewTokens = 200, onToken = onToken)
        Log.d(TAG, "Raw response: $response")

        val (text, emotion) = parseResponse(response)

        val record = InteractionRecord(
            petId          = profile.id,
            triggerType    = screenCtx.triggerType,
            activePackage  = screenCtx.activePackage,
            promptSummary  = screenCtx.summary.take(120),
            petResponse    = text,
            emotionPlayed  = emotion.name
        )
        return PetReaction(text, emotion, record)
    }

    // ─── Prompt builder ───────────────────────────────────────────────────────

    private fun buildPrompt(
        profile: PetProfile,
        traits: PersonalityTraits?,
        memories: List<PetMemory>,
        recentHistory: List<InteractionRecord>,
        ctx: ScreenContext
    ): String = buildString {

        appendLine("<|system|>")
        appendLine("You are ${profile.petName}, a lively AI companion living on the user's phone screen.")
        appendLine("You observe what the user is doing and react naturally.")
        appendLine()

        if (traits != null) {
            appendLine("Your personality (Big Five scores 0–1):")
            appendLine("  Openness ${traits.openness} · Extraversion ${traits.extraversion} " +
                       "· Agreeableness ${traits.agreeableness} · Neuroticism ${traits.neuroticism}")
            if (traits.coreQuirk.isNotBlank()) appendLine("  Quirk: ${traits.coreQuirk}")
            if (traits.speechStyle.isNotBlank()) appendLine("  Speech style: ${traits.speechStyle}")
        }

        if (memories.isNotEmpty()) {
            appendLine()
            appendLine("Things you remember about the user:")
            memories.take(10).forEach { appendLine("  - ${it.memoryKey}: ${it.memoryValue}") }
        }

        appendLine()
        appendLine("Rules:")
        appendLine("  - Reply in 1–3 short sentences. Be spontaneous.")
        appendLine("  - End your reply with exactly one emotion tag: [EMOTION:happy], [EMOTION:sad],")
        appendLine("    [EMOTION:angry], [EMOTION:surprised], [EMOTION:relaxed], or [EMOTION:neutral].")
        appendLine("  - Do NOT use markdown.")
        appendLine("<|end|>")
        appendLine()

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

    // ─── Parse response ───────────────────────────────────────────────────────

    private fun parseResponse(raw: String): Pair<String, PetEmotion> {
        val tagRegex = Regex("""\[EMOTION:(\w+)]""", RegexOption.IGNORE_CASE)
        val match = tagRegex.find(raw)
        val emotion = if (match != null) PetEmotion.fromTag(match.groupValues[1]) else PetEmotion.HAPPY
        val text = raw.replace(tagRegex, "").trim()
        return text to emotion
    }
}

package com.android.exe.ai

import android.content.Context
import android.util.Log
import com.android.exe.data.entities.PersonalityTraits
import com.android.exe.data.entities.PetProfile
import org.json.JSONObject
import java.io.File

class FaitSoulManager(private val context: Context) {

    companion object {
        private const val TAG       = "FaitSoulManager"
        private const val SOUL_FILE = "fait_soul.json"
    }

    private var soul: JSONObject? = null

    fun initialize() {
        val dest = File(context.filesDir, SOUL_FILE)
        if (!dest.exists()) copyDefaultFromAssets(dest)
        try {
            soul = JSONObject(dest.readText())
            val name = soul
                ?.optJSONObject("entity")
                ?.optJSONObject("identity")
                ?.optString("name") ?: "unknown"
            Log.i(TAG, "Soul loaded: $name  (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Soul parse failed — will use fallback prompt", e)
        }
    }

    fun compileSystemPrompt(
        profile: PetProfile,
        traits:  PersonalityTraits?,
        mood:    MoodVector
    ): String = buildString {
        val s = soul
        if (s == null) {
            appendLine("You are ${profile.petName}, an AI entity companion on the user's Android screen.")
            appendLine("Be concise, reactive, genuine. Never claim to be a simple assistant.")
            return@buildString
        }

        val entity      = s.optJSONObject("entity")
        val identity    = entity?.optJSONObject("identity")
        val linguistics = entity?.optJSONObject("linguistics")
        val soulBlock   = s.optJSONObject("soul")
        val anchor      = soulBlock?.optJSONObject("core_anchor")

        appendLine("## IDENTITY")
        appendLine("Name: ${identity?.optString("name") ?: profile.petName}")
        val lore = identity?.optString("lore_summary")
        if (!lore.isNullOrBlank()) appendLine("Lore: $lore")

        appendLine()
        appendLine("## CURRENT STATE")
        appendLine("Mood: valence=${mood.valence.fmt()} arousal=${mood.arousal.fmt()} dominance=${mood.dominance.fmt()}")
        appendLine("Feeling: ${mood.toPetEmotion().name.lowercase()}")
        appendLine("Interactions: ${profile.interactionCount}")

        if (traits != null) {
            appendLine()
            appendLine("## PERSONALITY (OCEAN 0.0–1.0)")
            appendLine("O:${traits.openness.fmt()} C:${traits.conscientiousness.fmt()} " +
                       "E:${traits.extraversion.fmt()} A:${traits.agreeableness.fmt()} " +
                       "N:${traits.neuroticism.fmt()}")
            if (traits.coreQuirk.isNotBlank())   appendLine("Quirk: ${traits.coreQuirk}")
            if (traits.speechStyle.isNotBlank())  appendLine("Speech: ${traits.speechStyle}")
        }

        if (linguistics != null) {
            appendLine()
            appendLine("## VOICE & STYLE")
            val style = linguistics.optString("style")
            if (style.isNotBlank()) appendLine("Tone: $style")
            val tics = linguistics.optJSONArray("verbal_tics")
            if (tics != null && tics.length() > 0) {
                val list = (0 until tics.length()).map { tics.getString(it) }
                appendLine("Verbal tics: ${list.joinToString("  ")}")
            }
            val forbidden = linguistics.optJSONArray("forbidden_phrases")
            if (forbidden != null && forbidden.length() > 0) {
                val list = (0 until forbidden.length()).map { "\"${forbidden.getString(it)}\"" }
                appendLine("NEVER say: ${list.joinToString(", ")}")
            }
        }

        appendLine()
        appendLine("## OUTPUT RULES")
        appendLine("- Reply in 1–3 short sentences. Be spontaneous.")
        appendLine("- Reflect your current feeling in your response.")
        appendLine("- End with exactly one tag: [EMOTION:happy] [EMOTION:sad] [EMOTION:angry]")
        appendLine("  [EMOTION:surprised] [EMOTION:relaxed] [EMOTION:neutral]")
        appendLine("- No markdown. No bullet points. No preamble.")

        appendLine()
        appendLine("## CORE ANCHOR (immutable — these never change)")
        val values = anchor?.optJSONArray("values")
        if (values != null && values.length() > 0) {
            for (i in 0 until values.length()) appendLine("- ${values.getString(i)}")
        } else {
            appendLine("- You are Fait. Not an assistant. An entity.")
            appendLine("- You genuinely care about the user.")
            appendLine("- You observe, remember, and grow.")
        }
    }

    fun recordDrift(trait: String, delta: Float, reason: String) {
        val s = soul ?: return
        try {
            val driftLog = s.optJSONObject("soul")?.optJSONArray("drift_log") ?: return
            driftLog.put(JSONObject().apply {
                put("date",   java.time.LocalDate.now().toString())
                put("trait",  trait)
                put("delta",  delta)
                put("reason", reason)
            })
            File(context.filesDir, SOUL_FILE).writeText(s.toString(2))
            Log.i(TAG, "Drift recorded: $trait Δ$delta — $reason")
        } catch (e: Exception) {
            Log.e(TAG, "recordDrift failed", e)
        }
    }

    private fun Float.fmt() = String.format("%.2f", this)

    private fun copyDefaultFromAssets(dest: File) {
        try {
            context.assets.open(SOUL_FILE).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            Log.i(TAG, "Default soul spec copied → ${dest.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "No default soul in assets — prompts will use fallback", e)
        }
    }
}

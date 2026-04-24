package com.projectexe.character

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * A character "card" — name, personality, scenario, expressions, etc.
 * The application has no hardcoded character: it ships a generic placeholder
 * card and the user uploads a real one through Settings → Character card.
 *
 * Personality strings can use the `{character_name}` token, which is replaced
 * with the card's `name` at prompt-build time.
 */
data class CharacterCard(
    val name: String, val version: String, val vrmFile: String,
    val description: String, val personality: String, val scenario: String,
    val firstMessage: String, val exampleDialogue: List<Pair<String,String>>,
    val expressions: List<String>, val animations: List<String>,
    val creator: String, val tags: List<String>
) {
    companion object {
        private const val TAG = "EXE.CharCard"
        private val DEF_EXPR = listOf("neutral","joy","sadness","anger","surprise","thinking")
        private val DEF_ANIM = listOf("idle","wave","nod","shake","thinking","greeting")
        const val PLACEHOLDER_NAME = "Companion"

        /** Generic, no-personality fallback used when no card has been uploaded. */
        fun placeholder() = CharacterCard(
            name = PLACEHOLDER_NAME, version = "1.0", vrmFile = "",
            description  = "{character_description}",
            personality  = "A helpful AI companion. Stay in character once a card is uploaded.",
            scenario     = "{character_scenario}",
            firstMessage = "Hi. Upload a character card in Settings to give me a personality.",
            exampleDialogue = emptyList(),
            expressions = DEF_EXPR, animations = DEF_ANIM,
            creator = "Project EXE", tags = listOf("placeholder")
        )

        fun loadFromAssets(ctx: Context, file: String): CharacterCard? = try {
            fromJson(JSONObject(ctx.assets.open("characters/$file").bufferedReader().use { it.readText() }))
        } catch (e: Exception) { Log.e(TAG, "Asset load failed '$file': ${e.message}"); null }

        fun loadFromUri(ctx: Context, uri: Uri): CharacterCard? = try {
            val text = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return null
            fromJson(JSONObject(text))
        } catch (e: Exception) { Log.e(TAG, "URI load failed '$uri': ${e.message}"); null }

        fun listAvailable(ctx: Context): List<String> =
            try { ctx.assets.list("characters")?.filter { it.endsWith(".json") } ?: emptyList() }
            catch (_: Exception) { emptyList() }

        private fun fromJson(j: JSONObject): CharacterCard {
            fun strList(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val dlg = mutableListOf<Pair<String,String>>()
            j.optJSONArray("example_dialogue")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { dlg += it.optString("user","") to it.optString("char","") }
            }
            return CharacterCard(
                j.optString("name", PLACEHOLDER_NAME), j.optString("version","1.0"),
                j.optString("vrm_file",""), j.optString("description",""),
                j.optString("personality",""), j.optString("scenario",""),
                j.optString("first_message",""), dlg,
                strList("expressions").ifEmpty { DEF_EXPR }, strList("animations").ifEmpty { DEF_ANIM },
                j.optString("creator",""), strList("tags"))
        }
    }

    /** Persona prompt — what this character "is". Placeholders are interpolated. */
    fun personaPrompt(): String = buildString {
        append("You are ").append(name).append(". ").append(interp(description)).append('\n')
        if (personality.isNotEmpty()) { append(interp(personality)).append('\n') }
    }.trim()

    fun buildSystemPrompt(memoryContext: String? = null, userName: String = ""): String = buildString {
        appendLine(personaPrompt()); appendLine()
        if (scenario.isNotEmpty())    { appendLine("## Scenario");    appendLine(interp(scenario));    appendLine() }
        if (userName.isNotEmpty())    { appendLine("## User"); appendLine("The user's name is $userName."); appendLine() }
        if (exampleDialogue.isNotEmpty()) {
            appendLine("## Example Dialogue")
            exampleDialogue.take(3).forEach { (u,c) -> appendLine("User: $u"); appendLine("$name: $c"); appendLine() }
        }
        if (memoryContext != null) { appendLine("## Memory"); appendLine(memoryContext); appendLine() }
        appendLine("## Response Format")
        appendLine("""
You MUST respond with a single valid JSON object — no markdown, no extra text outside the JSON.
{
  "text": "<your in-character spoken response (1-4 sentences)>",
  "expression": "<one of: ${expressions.joinToString(", ")}>",
  "animation_trigger": "<one of: ${animations.joinToString(", ")}>"
}
Do NOT include markdown code fences. Output only the JSON object.
        """.trimIndent())
    }.trim()

    private fun interp(s: String) = s
        .replace("{character_name}",        name)
        .replace("{character_description}", description)
        .replace("{character_scenario}",    scenario)
        .replace("{character_personality}", "")  // avoid recursion
}

data class CharacterResponse(val text: String, val expression: String, val animationTrigger: String) {
    companion object {
        fun fallback(raw: String = "") = CharacterResponse(raw.ifEmpty { "…" }, "neutral", "idle")
    }
}

object CharacterResponseParser {
    private val BLOCK_RE = Regex("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", RegexOption.DOT_MATCHES_ALL)

    fun parse(raw: String, validExpressions: List<String>, validAnimations: List<String>): CharacterResponse {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val json = try { JSONObject(cleaned) } catch (_: Exception) {
            BLOCK_RE.find(cleaned)?.value?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        }
        if (json == null) {
            // No JSON object detected — wrap the cleaned text as the spoken line.
            return CharacterResponse(cleaned.take(800).ifEmpty { "…" }, "neutral", "idle")
        }
        val text  = json.optString("text", "").trim().ifEmpty { "…" }
        val expr  = json.optString("expression", "neutral").lowercase().trim()
        val anim  = json.optString("animation_trigger", "idle").lowercase().trim()
        return CharacterResponse(text,
            if (expr in validExpressions) expr else "neutral",
            if (anim in validAnimations)  anim else "idle")
    }
}

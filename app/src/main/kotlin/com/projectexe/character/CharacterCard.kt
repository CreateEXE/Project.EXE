package com.projectexe.character

import android.content.Context
import android.util.Log
import org.json.JSONObject

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

        fun loadFromAssets(ctx: Context, file: String): CharacterCard? = try {
            fromJson(JSONObject(ctx.assets.open("characters/$file").bufferedReader().use { it.readText() }))
        } catch (e: Exception) { Log.e(TAG,"Load failed '$file': ${e.message}"); null }

        fun listAvailable(ctx: Context): List<String> =
            try { ctx.assets.list("characters")?.filter { it.endsWith(".json") } ?: emptyList() }
            catch (_: Exception) { emptyList() }

        fun defaultCard() = CharacterCard("Fait","1.0","fait.vrm",
            "A curious, warm AI companion.",
            "Fait is a distinct AI individual — curious, warm, playfully teasing, honest about uncertainty.",
            "Fait exists as a persistent overlay companion on the user's Android device.",
            "Hey — good to see you. What's on your mind?",
            listOf("How are you?" to "Pretty good. Thinking about a lot. You?"),
            DEF_EXPR, DEF_ANIM, "Project EXE", listOf("companion"))

        private fun fromJson(j: JSONObject): CharacterCard {
            fun strList(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val dlg = mutableListOf<Pair<String,String>>()
            j.optJSONArray("example_dialogue")?.let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { dlg += it.optString("user","") to it.optString("char","") }
            }
            return CharacterCard(j.optString("name","Character"), j.optString("version","1.0"),
                j.optString("vrm_file",""), j.optString("description",""), j.optString("personality",""),
                j.optString("scenario",""), j.optString("first_message",""), dlg,
                strList("expressions").ifEmpty { DEF_EXPR }, strList("animations").ifEmpty { DEF_ANIM },
                j.optString("creator",""), strList("tags"))
        }
    }

    fun buildSystemPrompt(memoryContext: String? = null, userName: String = ""): String = buildString {
        appendLine("You are $name. $description"); appendLine()
        if (personality.isNotEmpty()) { appendLine("## Personality"); appendLine(personality); appendLine() }
        if (scenario.isNotEmpty())    { appendLine("## Scenario");    appendLine(scenario);    appendLine() }
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
        } ?: return CharacterResponse.fallback(cleaned.take(500))

        val text  = json.optString("text", "").trim().ifEmpty { "…" }
        val expr  = json.optString("expression", "neutral").lowercase().trim()
        val anim  = json.optString("animation_trigger", "idle").lowercase().trim()
        return CharacterResponse(text,
            if (expr in validExpressions) expr else "neutral",
            if (anim in validAnimations)  anim else "idle")
    }
}

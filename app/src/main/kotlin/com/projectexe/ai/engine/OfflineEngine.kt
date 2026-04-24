package com.projectexe.ai.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.projectexe.ai.engine.local.LlamaCpp
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.api.ChatMessage
import com.projectexe.util.UserPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline engine via llama.cpp (GGUF). Tool / function-calling is NOT advertised
 * (small local models on phones reliably break tool-call JSON), so we ignore the
 * tools list and produce a plain text response that the existing
 * CharacterResponseParser can parse.
 */
class OfflineEngine(
    private val appCtx: Context,
    private val prefs: UserPreferenceManager,
    private val llama: LlamaCpp = LlamaCpp(appCtx)
) : LlmEngine {

    companion object { private const val TAG = "EXE.OfflineEngine" }
    override val id = "llama.cpp"

    override suspend fun isAvailable(): Boolean =
        LlamaCpp.nativeAvailable() && prefs.ggufUri.isNotEmpty()

    override suspend fun chat(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        jsonMode: Boolean
    ): EngineResponse = withContext(Dispatchers.IO) {
        if (!LlamaCpp.nativeAvailable())
            return@withContext EngineResponse.Text(errText("Offline engine isn't included in this build."))
        val uriStr = prefs.ggufUri
        if (uriStr.isEmpty())
            return@withContext EngineResponse.Text(errText("No local GGUF model selected. Open Settings → Offline."))

        try {
            llama.ensureModelLoaded(Uri.parse(uriStr), prefs.ggufContextSize).getOrThrow()
        } catch (e: Throwable) {
            Log.e(TAG, "Model load failed", e)
            return@withContext EngineResponse.Text(errText("Couldn't load the local model: ${e.message}"))
        }

        val prompt = buildString {
            append("<|system|>\n").append(systemPrompt).append("\n")
            for (m in history) {
                val role = when (m.role.value) { "user" -> "user"; "assistant" -> "assistant"; else -> "system" }
                append("<|").append(role).append("|>\n").append(m.content).append("\n")
            }
            append("<|assistant|>\n")
        }
        val res = llama.generate(prompt, maxTokens = 256, temperature = 0.8f)
        val text = res.getOrElse { return@withContext EngineResponse.Text(errText("Generation failed: ${it.message}")) }
        EngineResponse.Text(if (jsonMode) wrapAsJson(text) else text)
    }

    private fun wrapAsJson(text: String): String {
        // The downstream parser tolerates plain text, but if upstream asked for json
        // we wrap the cleaned line so it still parses cleanly.
        val clean = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: text
        val esc = clean.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"text\":\"$esc\",\"expression\":\"neutral\",\"animation\":\"idle\"}"
    }

    private fun errText(msg: String) =
        "{\"text\":\"$msg\",\"expression\":\"sadness\",\"animation\":\"idle\"}"
}

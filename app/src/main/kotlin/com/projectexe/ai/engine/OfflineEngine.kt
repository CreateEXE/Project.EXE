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
 * Offline engine via llama.cpp (GGUF). Each instance is tied to one of the two
 * native slots (SLOT_PERSONA or SLOT_FACTUAL) so both can be loaded at once.
 *
 * Tool / function-calling is intentionally NOT advertised — small local models
 * reliably break tool-call JSON.
 */
class OfflineEngine(
    private val appCtx: Context,
    private val prefs: UserPreferenceManager,
    private val role:  UserPreferenceManager.Role,
    private val llama: LlamaCpp
) : LlmEngine {

    companion object { private const val TAG = "EXE.OfflineEngine" }
    override val id = "llama.cpp"

    override suspend fun isAvailable(): Boolean =
        LlamaCpp.nativeAvailable() && prefs.ggufUri(role).isNotEmpty()

    override suspend fun chat(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        jsonMode: Boolean
    ): EngineResponse = withContext(Dispatchers.IO) {
        ensureLoaded()?.let { return@withContext EngineResponse.Text(errText(it)) }

        val prompt = buildString {
            append("<|system|>\n").append(systemPrompt).append("\n")
            for (m in history) {
                val r = when (m.role.value) { "user" -> "user"; "assistant" -> "assistant"; else -> "system" }
                append("<|").append(r).append("|>\n").append(m.content).append("\n")
            }
            append("<|assistant|>\n")
        }
        val res = llama.generate(role.slot, prompt, maxTokens = 384, temperature = 0.8f)
        val text = res.getOrElse { return@withContext EngineResponse.Text(errText("Generation failed: ${it.message}")) }
        EngineResponse.Text(if (jsonMode) wrapAsJson(text) else text)
    }

    /** Per-stage one-shot generation used by the dual pipeline. */
    suspend fun generateText(
        systemPrompt: String, userPrompt: String,
        temperature: Float, maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        ensureLoaded()?.let { throw IllegalStateException(it) }
        val prompt = "<|system|>\n$systemPrompt\n<|user|>\n$userPrompt\n<|assistant|>\n"
        llama.generate(role.slot, prompt, maxTokens, temperature).getOrThrow()
    }

    /** Returns a human error or null if the model is loaded. */
    private fun ensureLoaded(): String? {
        if (!LlamaCpp.nativeAvailable())
            return "Offline engine isn't included in this build."
        val uriStr = prefs.ggufUri(role)
        if (uriStr.isEmpty())
            return "No GGUF picked for the ${role.key} slot. Open Settings → ${role.key}."
        return try {
            llama.ensureModelLoaded(role.slot, Uri.parse(uriStr), prefs.ggufContextSize(role)).getOrThrow()
            null
        } catch (e: Throwable) {
            Log.e(TAG, "Model load failed (slot=${role.slot})", e)
            "Couldn't load the ${role.key} model: ${e.message}"
        }
    }

    private fun wrapAsJson(text: String): String {
        val clean = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: text
        val esc = clean.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{\"text\":\"$esc\",\"expression\":\"neutral\",\"animation\":\"idle\"}"
    }
    private fun errText(msg: String) =
        "{\"text\":\"$msg\",\"expression\":\"sadness\",\"animation\":\"idle\"}"
}

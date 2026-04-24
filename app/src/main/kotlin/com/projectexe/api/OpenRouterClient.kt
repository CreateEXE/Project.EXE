package com.projectexe.api

import android.util.Log
import com.projectexe.BuildConfig
import com.projectexe.ai.engine.EngineResponse
import com.projectexe.ai.tools.ToolCall
import com.projectexe.ai.tools.ToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenRouterClient private constructor(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private var apiKey: String,
    private var model: String
) {
    companion object {
        private const val TAG           = "EXE.OpenRouter"
        private const val CONTENT_TYPE  = "application/json; charset=utf-8"
        const val DEFAULT_MODEL         = "meta-llama/llama-3.1-8b-instruct:free"
        private const val TEMPERATURE   = 0.80
        private const val TOP_P         = 0.90
        private const val MAX_TOKENS    = 512
        private const val MAX_RETRIES   = 2
        private val RETRY_DELAYS        = longArrayOf(1_000L, 3_000L)

        fun create(): OpenRouterClient {
            val key = BuildConfig.OPENROUTER_API_KEY.trim()
            if (key.isEmpty() || key.startsWith("sk-or-REPLACE"))
                Log.w(TAG, "OPENROUTER_API_KEY not set in local.properties — relying on user override.")
            val model = BuildConfig.OPENROUTER_MODEL.trim().ifEmpty { DEFAULT_MODEL }
            val logging = HttpLoggingInterceptor { l ->
                if (!l.startsWith("Authorization", ignoreCase = true)) Log.v(TAG, l)
            }.apply { level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE }
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90,    TimeUnit.SECONDS)
                .writeTimeout(20,   TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(3, 5, TimeUnit.MINUTES))
                .addInterceptor(logging).build()
            return OpenRouterClient(http, "https://openrouter.ai/api/v1/", key, model)
        }
    }

    /** Allow user-provided overrides from Settings to take effect at runtime. */
    fun applyOverrides(apiKeyOverride: String, modelOverride: String) {
        if (apiKeyOverride.isNotBlank()) apiKey = apiKeyOverride.trim()
        if (modelOverride.isNotBlank())  model  = modelOverride.trim()
    }

    fun hasUsableKey(): Boolean = apiKey.isNotBlank() && !apiKey.startsWith("sk-or-REPLACE")

    /** Backwards-compatible streaming text completion (used by character JSON path). */
    suspend fun chatCompletion(systemPrompt: String, messages: List<ChatMessage>, jsonMode: Boolean = false): String =
        withContext(Dispatchers.IO) {
            var lastEx: Exception? = null
            for (attempt in 0..MAX_RETRIES) {
                if (attempt > 0) delay(RETRY_DELAYS.getOrElse(attempt - 1) { 5_000L })
                try { return@withContext stream(systemPrompt, messages, jsonMode) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: OpenRouterApiException) { if (e.statusCode in 400..499 && e.statusCode != 429) throw e; lastEx = e }
                catch (e: IOException) { lastEx = e }
            }
            throw lastEx ?: IOException("Unknown error")
        }

    /** Tool-aware non-streaming variant — returns either text OR a list of tool calls. */
    suspend fun chatCompletionWithTools(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        jsonMode: Boolean
    ): EngineResponse = withContext(Dispatchers.IO) {
        // No tools → use existing streaming path for lower latency.
        if (tools.isEmpty()) return@withContext EngineResponse.Text(chatCompletion(systemPrompt, messages, jsonMode))

        var lastEx: Exception? = null
        for (attempt in 0..MAX_RETRIES) {
            if (attempt > 0) delay(RETRY_DELAYS.getOrElse(attempt - 1) { 5_000L })
            try { return@withContext oneShot(systemPrompt, messages, tools, jsonMode) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: OpenRouterApiException) { if (e.statusCode in 400..499 && e.statusCode != 429) throw e; lastEx = e }
            catch (e: IOException) { lastEx = e }
        }
        throw lastEx ?: IOException("Unknown error")
    }

    private fun oneShot(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        jsonMode: Boolean
    ): EngineResponse {
        val msgsArr = JSONArray().apply {
            put(JSONObject().apply { put("role","system"); put("content",systemPrompt) })
            for (m in messages) {
                if (m.role.value == "tool") {
                    // Encoded as `tool_call_id|content`
                    val (cid, content) = m.content.split('|', limit = 2).let {
                        if (it.size == 2) it[0] to it[1] else "" to m.content
                    }
                    put(JSONObject().apply {
                        put("role","tool"); put("tool_call_id", cid); put("content", content)
                    })
                } else {
                    put(JSONObject().apply { put("role", m.role.value); put("content", m.content) })
                }
            }
        }
        val toolsArr = JSONArray().apply {
            for (t in tools) put(JSONObject().apply {
                put("type","function")
                put("function", JSONObject().apply {
                    put("name", t.name); put("description", t.description)
                    put("parameters", JSONObject(t.parametersJson))
                })
            })
        }
        val body = JSONObject().apply {
            put("model",model); put("messages",msgsArr); put("max_tokens",MAX_TOKENS)
            put("temperature",TEMPERATURE); put("top_p",TOP_P); put("stream",false)
            put("tools", toolsArr); put("tool_choice","auto")
            if (jsonMode) put("response_format", JSONObject().apply { put("type","json_object") })
        }.toString().toRequestBody(CONTENT_TYPE.toMediaType())

        val req = Request.Builder().url("${baseUrl}chat/completions").post(body)
            .header("Authorization","Bearer $apiKey")
            .header("Content-Type",CONTENT_TYPE).header("HTTP-Referer","https://github.com/projectexe")
            .header("X-Title","Project EXE").build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw OpenRouterApiException(resp.code, raw.take(300))
            val choice = JSONObject(raw).optJSONArray("choices")?.optJSONObject(0)
                ?: return EngineResponse.Text("")
            val msg = choice.optJSONObject("message") ?: return EngineResponse.Text("")
            val tc  = msg.optJSONArray("tool_calls")
            if (tc != null && tc.length() > 0) {
                val list = mutableListOf<ToolCall>()
                for (i in 0 until tc.length()) {
                    val o = tc.getJSONObject(i)
                    val fn = o.optJSONObject("function") ?: continue
                    list += ToolCall(
                        id = o.optString("id", "call_$i"),
                        name = fn.optString("name"),
                        argumentsJson = fn.optString("arguments", "{}")
                    )
                }
                return EngineResponse.ToolRequest(list, msg.toString())
            }
            return EngineResponse.Text(msg.optString("content", ""))
        }
    }

    private suspend fun stream(systemPrompt: String, messages: List<ChatMessage>, jsonMode: Boolean): String =
        suspendCancellableCoroutine { cont ->
            val msgsArr = JSONArray().apply {
                put(JSONObject().apply { put("role","system"); put("content",systemPrompt) })
                for (m in messages) put(JSONObject().apply { put("role",m.role.value); put("content",m.content) })
            }
            val body = JSONObject().apply {
                put("model",model); put("messages",msgsArr); put("max_tokens",MAX_TOKENS)
                put("temperature",TEMPERATURE); put("top_p",TOP_P); put("stream",true)
                if (jsonMode) put("response_format", JSONObject().apply { put("type","json_object") })
            }.toString().toRequestBody(CONTENT_TYPE.toMediaType())

            val req = Request.Builder().url("${baseUrl}chat/completions").post(body)
                .header("Authorization","Bearer $apiKey").header("Accept","text/event-stream")
                .header("Content-Type",CONTENT_TYPE).header("HTTP-Referer","https://github.com/projectexe")
                .header("X-Title","Project EXE").build()

            val buf = StringBuilder()
            val src = EventSources.createFactory(http).newEventSource(req, object : EventSourceListener() {
                override fun onOpen(es: EventSource, r: Response) {
                    if (!r.isSuccessful) { es.cancel()
                        if (cont.isActive) cont.resumeWithException(OpenRouterApiException(r.code, r.body?.string()?.take(300)?:"")) }
                }
                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") { if (cont.isActive) cont.resume(buf.toString().trim()); return }
                    try { JSONObject(data).optJSONArray("choices")?.getJSONObject(0)?.optJSONObject("delta")
                        ?.optString("content","")?.let { if (it.isNotEmpty()) buf.append(it) } } catch (_:Exception) {}
                }
                override fun onFailure(es: EventSource, t: Throwable?, r: Response?) {
                    val code = r?.code ?: -1
                    if (cont.isActive) cont.resumeWithException(
                        if (code in 400..599) OpenRouterApiException(code, r?.body?.string()?.take(200)?:"")
                        else IOException(t?.message ?: "SSE failure"))
                }
                override fun onClosed(es: EventSource) {
                    if (!cont.isActive) return
                    val acc = buf.toString().trim()
                    if (acc.isNotEmpty()) cont.resume(acc) else cont.resumeWithException(IOException("Empty stream"))
                }
            })
            cont.invokeOnCancellation { src.cancel() }
        }
}

data class ChatMessage(val role: ChatRole, val content: String)
enum class ChatRole(val value: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant"), TOOL("tool") }
class OpenRouterApiException(val statusCode: Int, body: String) : IOException("OpenRouter $statusCode: $body")

package com.projectexe.api

import android.util.Log
import com.projectexe.BuildConfig
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
    private val apiKey: String,
    private val model: String
) {
    companion object {
        private const val TAG           = "EXE.OpenRouter"
        private const val CONTENT_TYPE  = "application/json; charset=utf-8"
        private const val DEFAULT_MODEL = "meta-llama/llama-3.1-8b-instruct:free"
        private const val TEMPERATURE   = 0.80
        private const val TOP_P         = 0.90
        private const val MAX_TOKENS    = 512
        private const val MAX_RETRIES   = 2
        private val RETRY_DELAYS        = longArrayOf(1_000L, 3_000L)

        fun create(): OpenRouterClient {
            val key = BuildConfig.OPENROUTER_API_KEY.trim()
            if (key.isEmpty() || key.startsWith("sk-or-REPLACE"))
                Log.e(TAG, "OPENROUTER_API_KEY not set in local.properties!")
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
enum class ChatRole(val value: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }
class OpenRouterApiException(val statusCode: Int, body: String) : IOException("OpenRouter $statusCode: $body")

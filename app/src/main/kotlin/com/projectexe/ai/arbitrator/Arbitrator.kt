package com.projectexe.ai.arbitrator

import android.content.Context
import android.util.Log
import com.projectexe.BuildConfig
import com.projectexe.ProjectEXEApplication
import com.projectexe.ai.auditor.AuditorHemisphere
import com.projectexe.ai.engine.EngineResponse
import com.projectexe.ai.engine.EngineRouter
import com.projectexe.ai.engine.LlmEngine
import com.projectexe.ai.soul.EmotionalState
import com.projectexe.ai.soul.SoulHemisphere
import com.projectexe.ai.tools.ToolCall
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolRegistry
import com.projectexe.api.ChatMessage
import com.projectexe.api.ChatRole
import com.projectexe.api.OpenRouterClient
import com.projectexe.character.CharacterCard
import com.projectexe.character.CharacterResponseParser
import com.projectexe.memory.MemoryType
import com.projectexe.util.SessionGap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class Arbitrator(
    private val soul: SoulHemisphere,
    private val client: OpenRouterClient,                       // legacy, kept for compatibility
    private val scope: CoroutineScope,
    private val router: EngineRouter? = null,
    private val tools: ToolRegistry?  = null
) {
    companion object {
        private const val TAG = "EXE.Arbitrator"
        private const val MAX_TOOL_LOOPS = 3
    }

    private val _flow = MutableSharedFlow<ArbitratorResult>(replay=1, extraBufferCapacity=4, onBufferOverflow=BufferOverflow.DROP_OLDEST)
    val responseFlow: SharedFlow<ArbitratorResult> = _flow.asSharedFlow()

    private val history = ArrayDeque<ChatMessage>()
    private var job: Job? = null
    private val auditor = AuditorHemisphere()
    private var card: CharacterCard = CharacterCard.defaultCard()

    fun loadCharacter(ctx: Context, file: String) {
        CharacterCard.loadFromAssets(ctx, file)?.also { card = it; history.clear()
            Log.i(TAG, "Loaded: ${it.name}") } ?: Log.w(TAG, "Failed to load $file")
    }

    fun activeVrmFile() = card.vrmFile
    fun activeCharacterName() = card.name

    fun submitPrompt(userInput: String, isSystemEvent: Boolean = false) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { pipeline(userInput, isSystemEvent) }
    }

    private suspend fun pipeline(input: String, system: Boolean) {
        _flow.emit(ArbitratorResult.thinking(soul.currentEmotionalState.value.lerp(EmotionalState.THINKING, 0.7f).normalise()))
        try {
            val app: ProjectEXEApplication? = try { ProjectEXEApplication.instance } catch (_: Exception) { null }
            val mem    = soul.buildMemoryContextBlock()
            val sysPrompt = if (system) buildSysEvent(input, app, mem) else card.buildSystemPrompt(mem, app?.userPrefs?.userName ?: "")
            if (!system) history.addLast(ChatMessage(ChatRole.USER, input))

            val raw = generate(sysPrompt, system, input, app)
            if (raw.isBlank()) { emitFallback(); return }

            val parsed = CharacterResponseParser.parse(raw, card.expressions, card.animations)
            val audit  = auditor.audit(input, parsed.text, BuildConfig.ENABLE_AUDITOR_LOGGING)
            val text   = if (audit.isBlocked) audit.sanitisedText else parsed.text
            val state  = exprToState(parsed.expression)

            soul.applyEmotionalTransition(state)
            soul.decayEmotionalState()

            if (!system && text.length >= 60) {
                val trivial = input.length < 8 || input.lowercase() in setOf("ok","yes","no","thanks","bye","hi","hello")
                if (!trivial) soul.recordMemory(
                    "User: \"${input.take(120)}\" — ${card.name}: \"${text.take(180)}\"",
                    if (audit.confidence > 0.75f) MemoryType.FACTUAL_EXCHANGE else MemoryType.CONVERSATION_SUMMARY,
                    if (audit.confidence > 0.8f) 0.75f else 0.4f
                )
            }
            if (!system) history.addLast(ChatMessage(ChatRole.ASSISTANT, raw))
            while (history.size > 24) history.removeFirst()

            _flow.emit(ArbitratorResult(text, parsed.expression, parsed.animationTrigger, state, false, !audit.isBlocked, audit.confidence))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.e(TAG, "Pipeline error", e); emitError(e) }
    }

    /** Run the engine router (with tool loop) or fall back to the legacy single-shot client. */
    private suspend fun generate(sysPrompt: String, system: Boolean, input: String, app: ProjectEXEApplication?): String {
        val r = router
        if (r == null) {
            val msgs = if (system) listOf(ChatMessage(ChatRole.USER, input)) else history.toList()
            return client.chatCompletion(sysPrompt, msgs, jsonMode = true)
        }
        val engine: LlmEngine = r.pick()
        val toolDescs: List<ToolDescriptor> =
            if (engine.id == "openrouter" && app?.userPrefs?.toolsEnabled == true) tools?.descriptors().orEmpty()
            else emptyList()

        val convo = ArrayDeque<ChatMessage>().apply {
            if (system) add(ChatMessage(ChatRole.USER, input)) else addAll(history)
        }
        var loops = 0
        while (true) {
            val resp = engine.chat(sysPrompt, convo.toList(), toolDescs, jsonMode = toolDescs.isEmpty())
            when (resp) {
                is EngineResponse.Text -> return resp.content
                is EngineResponse.ToolRequest -> {
                    if (tools == null || ++loops > MAX_TOOL_LOOPS) {
                        return "{\"text\":\"I tried too many tools in a row. Let's try a simpler approach.\",\"expression\":\"thinking\",\"animation\":\"idle\"}"
                    }
                    convo.addLast(ChatMessage(ChatRole.ASSISTANT, resp.rawAssistantMessage))
                    for (call in resp.calls) {
                        val result = tools.execute(call)
                        Log.i(TAG, "Tool ${call.name}: ${result.userVisibleSummary ?: "(no summary)"}")
                        // ChatRole.TOOL is encoded "id|content" (see OpenRouterClient.oneShot).
                        convo.addLast(ChatMessage(ChatRole.TOOL, "${call.id}|${result.asJsonString()}"))
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") return ""
    }

    private suspend fun buildSysEvent(key: String, app: ProjectEXEApplication?, mem: String?): String {
        val base = card.buildSystemPrompt(mem, app?.userPrefs?.userName ?: "")
        val instr = when (key) {
            "__SYSTEM_INIT__" -> "\n\nFirst ever conversation. Use the character's first_message. expression \"joy\", animation \"greeting\"."
            "__SYSTEM_WAKE__" -> {
                val gap = app?.userPrefs?.getSessionGap() ?: SessionGap.TODAY
                val n   = app?.userPrefs?.userName?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
                "\n\n" + when (gap) {
                    SessionGap.RECENT       -> "Just resumed. 1-sentence casual welcome$n. neutral."
                    SessionGap.TODAY        -> "Resuming after a few hours. Warm greeting$n. joy."
                    SessionGap.FEW_DAYS     -> "Few days passed. Glad to see$n. joy."
                    SessionGap.LONG_ABSENCE -> "Over a week. Missed$n. Ask what's new. sadness into joy."
                    SessionGap.FIRST_TIME   -> "First meeting. Use first_message. joy."
                }
            }
            else -> "\n\nSystem: $key. Stay in character."
        }
        return base + instr
    }

    private fun exprToState(e: String) = when (e) {
        "joy"      -> EmotionalState(joy=0.9f, neutral=0.1f)
        "sadness"  -> EmotionalState(sadness=0.9f, neutral=0.1f)
        "anger"    -> EmotionalState(anger=0.85f, neutral=0.15f)
        "surprise" -> EmotionalState(surprise=0.85f, joy=0.2f, neutral=0.1f)
        "thinking" -> EmotionalState(thinking=0.9f, neutral=0.1f)
        else       -> EmotionalState.NEUTRAL
    }

    private suspend fun emitFallback() = _flow.emit(ArbitratorResult(
        "Hmm, lost my train of thought. What were we saying?","thinking","idle",
        EmotionalState.THINKING, false, true, 1f))

    private suspend fun emitError(e: Exception) = _flow.emit(ArbitratorResult(
        when {
            e.message?.contains("ENGINE_UNAVAILABLE",    true) == true -> "My offline brain isn't compiled into this build yet."
            e.message?.contains("NO_MODEL",              true) == true -> "Pick a local GGUF model in Settings first."
            e.message?.contains("timeout",               true) == true -> "I took too long. Try again?"
            e.message?.contains("401",                   true) == true -> "API key issue — check it in Settings."
            else -> "Something went wrong. Try again?"
        }, "neutral", "idle", EmotionalState.NEUTRAL, false, true, 1f))
}

data class ArbitratorResult(
    val text: String, val expression: String, val animationTrigger: String,
    val emotionalState: EmotionalState, val isThinking: Boolean,
    val auditorPassed: Boolean, val confidence: Float
) {
    companion object {
        fun thinking(s: EmotionalState) = ArbitratorResult("","thinking","thinking",s,true,true,1f)
    }
}

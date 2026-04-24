package com.projectexe.ai.arbitrator

import android.content.Context
import android.net.Uri
import android.util.Log
import com.projectexe.BuildConfig
import com.projectexe.ProjectEXEApplication
import com.projectexe.ai.auditor.AuditorHemisphere
import com.projectexe.ai.engine.EngineResponse
import com.projectexe.ai.engine.EngineRouter
import com.projectexe.ai.engine.LlmEngine
import com.projectexe.ai.pipeline.DualLlmPipeline
import com.projectexe.ai.soul.EmotionalState
import com.projectexe.ai.soul.SoulHemisphere
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

/**
 * Top-level conversation orchestrator. Holds chat history + character card,
 * delegates generation to either:
 *   • the multi-stage [DualLlmPipeline] (Persona + Factual), or
 *   • a legacy single-shot single-engine path (when the user has disabled the
 *     pipeline in Settings).
 *
 * Contains zero hardcoded character data — the character is supplied entirely
 * by the loaded [CharacterCard].
 */
class Arbitrator(
    private val soul: SoulHemisphere,
    private val client: OpenRouterClient,        // single-shot fallback path
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
    private var card: CharacterCard = CharacterCard.placeholder()

    init { soul.personaName = card.name }

    fun loadCharacter(ctx: Context) {
        val app = try { ProjectEXEApplication.instance } catch (_: Exception) { null }
        // 1. user-uploaded card (Settings → Character card)
        val uri = app?.userPrefs?.characterCardUri.orEmpty()
        if (uri.isNotEmpty()) {
            CharacterCard.loadFromUri(ctx, Uri.parse(uri))?.also { setCard(it); return }
                ?: Log.w(TAG, "Could not load uploaded card at $uri — falling back.")
        }
        // 2. shipped placeholder
        CharacterCard.loadFromAssets(ctx, "default.json")?.also { setCard(it) }
            ?: setCard(CharacterCard.placeholder())
    }

    private fun setCard(c: CharacterCard) {
        card = c; history.clear(); soul.personaName = c.name
        Log.i(TAG, "Character loaded: ${c.name}")
    }

    fun activeVrmFile()       = card.vrmFile
    fun activeCharacterName() = card.name

    fun submitPrompt(userInput: String, isSystemEvent: Boolean = false) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) { pipeline(userInput, isSystemEvent) }
    }

    private suspend fun pipeline(input: String, system: Boolean) {
        _flow.emit(ArbitratorResult.thinking(soul.currentEmotionalState.value
            .lerp(EmotionalState.THINKING, 0.7f).normalise()))
        try {
            val app: ProjectEXEApplication? = try { ProjectEXEApplication.instance } catch (_: Exception) { null }
            val mem  = soul.buildMemoryContextBlock()
            val name = app?.userPrefs?.userName ?: ""
            val sysPrompt = if (system) buildSysEvent(input, app, mem) else card.buildSystemPrompt(mem, name)
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

    private suspend fun generate(sysPrompt: String, system: Boolean, input: String, app: ProjectEXEApplication?): String {
        val r = router ?: run {
            val msgs = if (system) listOf(ChatMessage(ChatRole.USER, input)) else history.toList()
            return client.chatCompletion(sysPrompt, msgs, jsonMode = true)
        }
        val useDual = app?.userPrefs?.useDualPipeline == true
        // System events go through the single-shot path so the card's first_message
        // / wake greeting still work as before — no need for a 5-stage greeting.
        if (useDual && !system) return runDualPipeline(input, app)
        return runSingleShot(r, sysPrompt, system, input, app)
    }

    private suspend fun runDualPipeline(input: String, app: ProjectEXEApplication?): String {
        val pipeline = DualLlmPipeline(router!!, tools)
        val ctx = soul.buildFullSystemPrompt()
        val result = pipeline.run(card, input, ctx)
        // P3 emits plain text — wrap into the JSON envelope the parser expects.
        return jsonEnvelope(result.finalText)
    }

    private fun jsonEnvelope(text: String): String {
        val esc = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"text\":\"$esc\",\"expression\":\"neutral\",\"animation_trigger\":\"idle\"}"
    }

    private suspend fun runSingleShot(r: EngineRouter, sysPrompt: String, system: Boolean, input: String, app: ProjectEXEApplication?): String {
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
                        return "{\"text\":\"I tried too many tools. Let's try a simpler approach.\",\"expression\":\"thinking\",\"animation_trigger\":\"idle\"}"
                    }
                    convo.addLast(ChatMessage(ChatRole.ASSISTANT, resp.rawAssistantMessage))
                    for (call in resp.calls) {
                        val result = tools.execute(call)
                        Log.i(TAG, "Tool ${call.name}: ${result.userVisibleSummary ?: "(no summary)"}")
                        convo.addLast(ChatMessage(ChatRole.TOOL, "${call.id}|${result.asJsonString()}"))
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") return ""
    }

    private suspend fun buildSysEvent(key: String, app: ProjectEXEApplication?, mem: String?): String {
        val base = card.buildSystemPrompt(mem, app?.userPrefs?.userName ?: "")
        val n   = app?.userPrefs?.userName?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""
        val instr = when (key) {
            "__SYSTEM_INIT__" -> "\n\nFirst ever conversation. Use the character's first_message. expression \"joy\", animation_trigger \"greeting\"."
            "__SYSTEM_WAKE__" -> {
                val gap = app?.userPrefs?.getSessionGap() ?: SessionGap.TODAY
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
        "I lost my train of thought. What were we saying?","thinking","idle",
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

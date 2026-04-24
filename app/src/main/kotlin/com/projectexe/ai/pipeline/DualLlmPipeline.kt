package com.projectexe.ai.pipeline

import android.util.Log
import com.projectexe.ProjectEXEApplication
import com.projectexe.ai.engine.EngineResponse
import com.projectexe.ai.engine.EngineRouter
import com.projectexe.ai.engine.LlmEngine
import com.projectexe.ai.engine.OfflineEngine
import com.projectexe.ai.engine.OnlineEngine
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.ai.tools.ToolRegistry
import com.projectexe.api.ChatMessage
import com.projectexe.api.ChatRole
import com.projectexe.character.CharacterCard
import com.projectexe.util.UserPreferenceManager.Role

/**
 * Two-agent ("Persona" + "Factual") iterative pipeline. Six stages, mapping
 * directly to the user-supplied node spec:
 *
 *   P1 (Persona)   — quick, in-character creative draft         temp 0.7
 *   F1 (Factual)   — fact-check P1, augment, can call tools     temp 0.2
 *   P2 (Persona)   — expand draft using F1's analysis           temp 0.7
 *   F2 (Factual)   — final factual verification of P2           temp 0.1
 *   P3 (Persona)   — final personification (style only)         temp 0.8
 *
 * Persona is configured to NEVER alter facts in P3; Factual is configured to
 * NEVER alter persona/voice — it only reports findings.
 *
 * The character card supplies *all* persona text. There is no hardcoded name
 * or personality anywhere in this file.
 */
class DualLlmPipeline(
    private val router:  EngineRouter,
    private val tools:   ToolRegistry?
) {
    companion object {
        private const val TAG = "EXE.DualLlmPipeline"
        private const val MAX_TOOL_LOOPS = 3
    }

    data class StageLog(val name: String, val ms: Long, val text: String)
    data class Result(val finalText: String, val logs: List<StageLog>, val factsVerified: Boolean)

    /**
     * Runs the full 5-stage pipeline. `extraSystemContext` is the soul/memory
     * block (emotion hint, recent memories, user name, response length).
     */
    suspend fun run(
        card: CharacterCard,
        userInput: String,
        extraSystemContext: String,
        repositoryData: String = ""
    ): Result {
        val logs = mutableListOf<StageLog>()
        val persona = card.personaPrompt()
        val ctxBlock = if (extraSystemContext.isBlank()) "" else "\n\n$extraSystemContext"

        // ---- P1 ----
        val p1Sys = """
$persona

You are responding live as ${card.name}. Generate a creative, character-driven
*first-pass* response — focus on style and persona, not deep accuracy yet.
Keep it concise (1-3 sentences) and conversational. Plain text only.
$ctxBlock""".trim()
        val p1User = "User Query: $userInput"
        val p1 = stage("P1", Role.PERSONA, p1Sys, p1User, temperature = 0.7, maxTokens = 256, useTools = false)
        logs += p1; if (p1.text.isBlank()) return Result(fallbackText(), logs, false)

        // ---- F1 ----
        val repoBlock = if (repositoryData.isBlank()) "(none)" else repositoryData
        val f1Sys = """
You are a highly accurate, analytical AI fact-checker. You DO NOT alter the
persona's voice — you only report findings the persona will integrate next.

Output structure (use these exact headings):

**Fact-Check Findings:**
- list inaccuracies, or "No significant inaccuracies."

**New Information & Augmentations:**
- new relevant facts, or "None."

**Reasoning:**
- brief 'why' for each item, or "N/A".
""".trim()
        val f1User = """
Original User Query: $userInput

Persona's Initial Response: ${p1.text}

Repository Data (if available): $repoBlock
""".trim()
        val f1 = stage("F1", Role.FACTUAL, f1Sys, f1User, temperature = 0.2, maxTokens = 700, useTools = true)
        logs += f1

        // ---- P2 ----
        val p2Sys = """
$persona

Revise and expand your initial response by integrating the Factual analysis
below. Correct any inaccuracies it lists and weave in the new information,
but keep your voice and tone exactly as before. Aim for a smooth, helpful
reply (3-6 sentences). Plain text only.
$ctxBlock""".trim()
        val p2User = """
Original User Query: $userInput

Your Initial Response (to build upon): ${p1.text}

Factual Analysis & Augmentation (from F1):
${f1.text}
""".trim()
        val p2 = stage("P2", Role.PERSONA, p2Sys, p2User, temperature = 0.7, maxTokens = 700, useTools = false)
        logs += p2; if (p2.text.isBlank()) return Result(p1.text, logs, false)

        // ---- F2 ----
        val f2Sys = """
You are a meticulous factual verifier. Compare the expanded persona response
against the original user query and the repository data. If everything is
accurate state exactly: 'Verification successful: No factual issues found.'
Otherwise list the issues clearly. DO NOT rewrite the response.
""".trim()
        val f2User = """
Original User Query: $userInput

Expanded Persona Response (to verify): ${p2.text}

Repository Data (for reference): $repoBlock
""".trim()
        val f2 = stage("F2", Role.FACTUAL, f2Sys, f2User, temperature = 0.1, maxTokens = 400, useTools = true)
        logs += f2
        val verified = f2.text.contains("Verification successful", ignoreCase = true) ||
                       f2.text.contains("No factual issues",      ignoreCase = true)

        // ---- P3 ----
        val p3Sys = """
$persona

FINAL PERSONIFICATION. Apply your voice, tone, and style to the verified
response below. **Do NOT add, remove, or alter any factual content.** Just
rephrase so it sounds completely natural in your voice. Plain text only.
$ctxBlock""".trim()
        val p3User = """
Verified Response (to personify): ${p2.text}

Factual Verification Result (for context, do not echo):
${f2.text}
""".trim()
        val p3 = stage("P3", Role.PERSONA, p3Sys, p3User, temperature = 0.8, maxTokens = 700, useTools = false)
        logs += p3
        val finalText = p3.text.ifBlank { p2.text }
        return Result(finalText, logs, verified)
    }

    private fun fallbackText() = "I lost my train of thought. Could you say that again?"

    private suspend fun stage(
        name: String,
        role: Role,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        useTools: Boolean
    ): StageLog {
        val started = System.currentTimeMillis()
        val text = try {
            val engine = router.pick(role)
            when (engine) {
                is OnlineEngine -> {
                    if (useTools && tools != null && shouldEnableTools()) runWithTools(role, system, user, temperature, maxTokens)
                    else engine.generateText(system, user, temperature, maxTokens)
                }
                is OfflineEngine -> engine.generateText(system, user, temperature.toFloat(), maxTokens)
                else -> "" // unknown engine type
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stage $name failed: ${e.message}")
            "" // soft-fail; caller decides whether to substitute prior stage's output
        }
        val ms = System.currentTimeMillis() - started
        Log.i(TAG, "Stage $name [$role] ${ms}ms ${text.length}ch")
        return StageLog(name, ms, text.trim())
    }

    private fun shouldEnableTools(): Boolean = try {
        ProjectEXEApplication.instance.userPrefs.toolsEnabled
    } catch (_: Exception) { false }

    /** Online + tools loop — used by F1/F2 when tool-calling is enabled. */
    private suspend fun runWithTools(
        role: Role, system: String, firstUserMsg: String,
        temperature: Double, maxTokens: Int
    ): String {
        val online = router.online(role)
        val convo = ArrayDeque<ChatMessage>().apply { add(ChatMessage(ChatRole.USER, firstUserMsg)) }
        val descs: List<ToolDescriptor> = tools?.descriptors().orEmpty()
        var loops = 0
        while (true) {
            val resp = online.chat(system, convo.toList(), descs, jsonMode = false)
            when (resp) {
                is EngineResponse.Text -> return resp.content
                is EngineResponse.ToolRequest -> {
                    if (++loops > MAX_TOOL_LOOPS || tools == null) {
                        return "[tool loop exhausted — proceeding without further tool calls]"
                    }
                    convo.addLast(ChatMessage(ChatRole.ASSISTANT, resp.rawAssistantMessage))
                    for (call in resp.calls) {
                        val res = tools.execute(call)
                        Log.i(TAG, "tool ${call.name}: ${res.userVisibleSummary ?: "(ok)"}")
                        convo.addLast(ChatMessage(ChatRole.TOOL, "${call.id}|${res.asJsonString()}"))
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") return ""
    }
}

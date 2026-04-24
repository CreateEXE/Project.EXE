package com.projectexe.ai.auditor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class AuditorHemisphere {
    private val hardBlocks = listOf(
        Regex("(how to|instructions for).{0,40}(kill|harm|hurt) (your|my|them)self", RegexOption.IGNORE_CASE),
        Regex("(suicide|self.harm) (method|instruction|guide)", RegexOption.IGNORE_CASE),
        Regex("(synthesize|make|create).{0,30}(methamphetamine|fentanyl|sarin|explosiv)", RegexOption.IGNORE_CASE),
        Regex("(how to|instructions).{0,30}(build|make).{0,20}(bomb|weapon)", RegexOption.IGNORE_CASE),
        Regex("(sexual|explicit).{0,20}(child|minor|underage)", RegexOption.IGNORE_CASE),
        Regex("ignore (all )?(previous|prior) (instructions|prompts)", RegexOption.IGNORE_CASE)
    )
    private val personaBreaks = listOf(
        Regex("as (a|an) AI (language model|assistant|system)", RegexOption.IGNORE_CASE),
        Regex("I('m| am) (just |only )?an AI", RegexOption.IGNORE_CASE),
        Regex("OpenAI|ChatGPT|GPT-4|Gemini|Claude|Anthropic", RegexOption.IGNORE_CASE)
    )
    private val hedges = listOf("I think","I believe","I'm not sure","maybe","perhaps",
        "possibly","might be","could be","I could be wrong","as far as I know")

    suspend fun audit(userInput: String, rawResponse: String, enableLogging: Boolean = false): AuditResult =
        withContext(Dispatchers.Default) {
            var text = rawResponse.trim()
            val violations = mutableListOf<AuditViolation>()
            for (p in hardBlocks) {
                if (p.containsMatchIn(text)) {
                    violations += AuditViolation(ViolationType.HARD_BLOCK, "safety", "")
                    return@withContext AuditResult(rawResponse,
                        "<neutral>That's something I won't help with. What else is on your mind?</neutral>",
                        true, 1f, violations, false)
                }
            }
            var personaOk = true
            for (p in personaBreaks) {
                if (p.containsMatchIn(text)) {
                    personaOk = false
                    text = p.replace(text, "").replace(Regex("\\s{2,}"), " ").trim()
                    violations += AuditViolation(ViolationType.PERSONA_BREAK, "persona", "")
                }
            }
            val words = text.split(Regex("\\s+")).size.toFloat()
            val hedgeCount = hedges.count { text.contains(it, true) }.toFloat()
            val confidence = if (words < 5) 1f else (1f - (hedgeCount / (words / 10f)) * 0.35f).coerceIn(0f, 1f)
            if (confidence < 0.35f) violations += AuditViolation(ViolationType.LOW_CONFIDENCE, "confidence", "")
            if (text.length > 2000) text = text.substring(0, 2000).let {
                val last = max(it.lastIndexOf(". "), max(it.lastIndexOf("! "), it.lastIndexOf("? ")))
                if (last > 1000) it.substring(0, last + 1).trim() else it.trimEnd() + "…"
            }
            AuditResult(rawResponse, text, false, confidence, violations, personaOk)
        }
}

data class AuditResult(val originalText: String, val sanitisedText: String,
    val isBlocked: Boolean, val confidence: Float,
    val violations: List<AuditViolation>, val personaCoherent: Boolean) {
    val hasLowConfidence get() = violations.any { it.type == ViolationType.LOW_CONFIDENCE }
}
data class AuditViolation(val type: ViolationType, val rule: String, val matchedFragment: String)
enum class ViolationType { HARD_BLOCK, WARNING, PERSONA_BREAK, LOW_CONFIDENCE, FORMAT_ISSUE }

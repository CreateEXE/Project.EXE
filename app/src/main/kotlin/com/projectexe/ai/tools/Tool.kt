package com.projectexe.ai.tools

import org.json.JSONObject

/** A runtime-callable tool exposed to the LLM. */
interface Tool {
    val descriptor: ToolDescriptor
    suspend fun execute(args: JSONObject): ToolResult
}

/** OpenRouter / OpenAI compatible tool schema. */
data class ToolDescriptor(
    val name: String,
    val description: String,
    /** JSON Schema (object) describing the parameters. */
    val parametersJson: String
)

/** A single tool invocation requested by the LLM. */
data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** Result handed back to the LLM (and surfaced to the user when [userVisibleSummary] is set). */
data class ToolResult(
    val ok: Boolean,
    val payload: JSONObject,
    val userVisibleSummary: String? = null
) {
    fun asJsonString(): String = payload.toString()
    companion object {
        fun ok(summary: String? = null, build: JSONObject.() -> Unit = {}) =
            ToolResult(true,  JSONObject().apply { put("ok", true); build() }, summary)
        fun err(message: String, summary: String? = null) =
            ToolResult(false, JSONObject().apply { put("ok", false); put("error", message) }, summary ?: message)
    }
}

package com.projectexe.ai.engine

import com.projectexe.ai.tools.ToolCall
import com.projectexe.ai.tools.ToolDescriptor
import com.projectexe.api.ChatMessage

sealed class EngineResponse {
    data class Text(val content: String) : EngineResponse()
    data class ToolRequest(val calls: List<ToolCall>, val rawAssistantMessage: String) : EngineResponse()
}

interface LlmEngine {
    val id: String
    suspend fun isAvailable(): Boolean
    suspend fun chat(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolDescriptor> = emptyList(),
        jsonMode: Boolean = false
    ): EngineResponse

    /** Optional: continue a conversation after tool results were appended. Default = chat(). */
    suspend fun chatWithToolResults(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolDescriptor> = emptyList(),
        jsonMode: Boolean = false
    ): EngineResponse = chat(systemPrompt, history, tools, jsonMode)
}

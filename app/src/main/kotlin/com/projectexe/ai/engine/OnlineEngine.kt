package com.projectexe.ai.engine

import com.projectexe.api.ChatMessage
import com.projectexe.api.OpenRouterClient
import com.projectexe.ai.tools.ToolDescriptor

class OnlineEngine(private val client: OpenRouterClient) : LlmEngine {
    override val id = "openrouter"
    override suspend fun isAvailable() = true   // network is checked by EngineRouter
    override suspend fun chat(
        systemPrompt: String,
        history: List<ChatMessage>,
        tools: List<ToolDescriptor>,
        jsonMode: Boolean
    ): EngineResponse = client.chatCompletionWithTools(systemPrompt, history, tools, jsonMode)
}

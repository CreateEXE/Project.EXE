package com.projectexe.ai.engine

import com.projectexe.api.ChatMessage
import com.projectexe.api.OpenRouterClient
import com.projectexe.ai.tools.ToolDescriptor

/**
 * OpenRouter-backed engine. The dual-agent pipeline creates two of these — one
 * per role — each carrying its own model id (Persona vs Factual) plus per-call
 * temperature / max_tokens tuned for that role's stage.
 */
class OnlineEngine(
    private val client: OpenRouterClient,
    private val modelOverride: String = "",
    private val defaultTemperature: Double = 0.80,
    private val defaultMaxTokens: Int = 512
) : LlmEngine {
    override val id = "openrouter"
    override suspend fun isAvailable() = client.hasUsableKey()

    override suspend fun chat(
        systemPrompt: String, history: List<ChatMessage>,
        tools: List<ToolDescriptor>, jsonMode: Boolean
    ): EngineResponse = client.chatCompletionWithTools(
        systemPrompt, history, tools, jsonMode,
        modelOverride = modelOverride,
        temperature   = defaultTemperature,
        maxTokens     = defaultMaxTokens
    )

    /** Per-stage one-shot generation used by [com.projectexe.ai.pipeline.DualLlmPipeline]. */
    suspend fun generateText(
        systemPrompt: String, userPrompt: String,
        temperature: Double, maxTokens: Int
    ): String = client.chatCompletion(
        systemPrompt   = systemPrompt,
        messages       = listOf(ChatMessage(com.projectexe.api.ChatRole.USER, userPrompt)),
        jsonMode       = false,
        modelOverride  = modelOverride,
        temperature    = temperature,
        maxTokens      = maxTokens
    )
}

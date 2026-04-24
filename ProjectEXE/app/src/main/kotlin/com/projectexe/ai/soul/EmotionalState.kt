package com.projectexe.ai.soul

data class EmotionalState(
    val joy: Float = 0f,      val sadness: Float  = 0f,
    val anger: Float = 0f,    val fear: Float     = 0f,
    val surprise: Float = 0f, val thinking: Float = 0f,
    val neutral: Float = 1f
) {
    companion object {
        val NEUTRAL  = EmotionalState(neutral = 1f)
        val THINKING = EmotionalState(thinking = 0.9f, neutral = 0.1f)
    }
    val dominant: String get() = mapOf(
        "joy" to joy, "sadness" to sadness, "anger" to anger,
        "fear" to fear, "surprise" to surprise, "thinking" to thinking, "neutral" to neutral
    ).maxByOrNull { it.value }?.key ?: "neutral"

    fun lerp(t: EmotionalState, a: Float): EmotionalState {
        val alpha = a.coerceIn(0f, 1f); val i = 1f - alpha
        return EmotionalState(
            joy=joy*i+t.joy*alpha, sadness=sadness*i+t.sadness*alpha,
            anger=anger*i+t.anger*alpha, fear=fear*i+t.fear*alpha,
            surprise=surprise*i+t.surprise*alpha, thinking=thinking*i+t.thinking*alpha,
            neutral=neutral*i+t.neutral*alpha
        )
    }
    fun normalise(): EmotionalState {
        val s = joy+sadness+anger+fear+surprise+thinking+neutral
        return if (s <= 0f) NEUTRAL else copy(
            joy=joy/s, sadness=sadness/s, anger=anger/s, fear=fear/s,
            surprise=surprise/s, thinking=thinking/s, neutral=neutral/s
        )
    }
}

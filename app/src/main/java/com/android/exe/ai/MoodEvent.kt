package com.android.exe.ai

sealed class MoodEvent {
    object PositiveInteraction : MoodEvent()
    object NegativeInteraction : MoodEvent()
    object LongIdle            : MoodEvent()
    object NewApp              : MoodEvent()
    data class UserSentiment(val score: Float) : MoodEvent()
    object ReactionComplete    : MoodEvent()
    object InferenceError      : MoodEvent()
}

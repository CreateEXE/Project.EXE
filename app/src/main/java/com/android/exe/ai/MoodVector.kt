package com.android.exe.ai

import kotlin.math.sqrt

data class MoodVector(
    val valence:   Float = 0.6f,
    val arousal:   Float = 0.45f,
    val dominance: Float = 0.55f
) {
    fun distanceTo(other: MoodVector): Float {
        val dv = valence   - other.valence
        val da = arousal   - other.arousal
        val dd = dominance - other.dominance
        return sqrt(dv * dv + da * da + dd * dd)
    }

    fun toPetEmotion(): PetEmotion = when {
        arousal   >  0.80f                          -> PetEmotion.SURPRISED
        valence   >  0.68f && arousal > 0.55f       -> PetEmotion.HAPPY
        valence   <  0.35f && arousal > 0.55f       -> PetEmotion.ANGRY
        valence   <  0.35f && arousal < 0.40f       -> PetEmotion.SAD
        valence   >  0.60f && arousal < 0.40f       -> PetEmotion.RELAXED
        else                                        -> PetEmotion.NEUTRAL
    }

    fun lerpTo(target: MoodVector, alpha: Float) = MoodVector(
        valence   = valence   + (target.valence   - valence)   * alpha,
        arousal   = arousal   + (target.arousal   - arousal)   * alpha,
        dominance = dominance + (target.dominance - dominance) * alpha
    )

    fun applyDelta(dv: Float = 0f, da: Float = 0f, dd: Float = 0f) = MoodVector(
        valence   = (valence   + dv).coerceIn(0f, 1f),
        arousal   = (arousal   + da).coerceIn(0f, 1f),
        dominance = (dominance + dd).coerceIn(0f, 1f)
    )

    fun toDebugString() =
        "V:${valence.fmt()} A:${arousal.fmt()} D:${dominance.fmt()} → ${toPetEmotion().name}"

    private fun Float.fmt() = String.format("%.2f", this)
}

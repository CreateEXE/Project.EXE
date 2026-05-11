package com.android.exe.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

// ─── Pet Profile ──────────────────────────────────────────────────────────────

@Entity(tableName = "pet_profile")
data class PetProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pet_name")       val petName: String,
    @ColumnInfo(name = "avatar_path")    val avatarPath: String? = null,
    @ColumnInfo(name = "llm_model_path") val llmModelPath: String? = null,
    @ColumnInfo(name = "created_at")     val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "interaction_count") val interactionCount: Int = 0,
    @ColumnInfo(name = "current_mood")   val currentMood: Float = 0.5f,   // 0=sad 1=happy
    @ColumnInfo(name = "energy_level")   val energyLevel: Float = 0.8f
)

// ─── Personality Traits ───────────────────────────────────────────────────────

@Entity(
    tableName = "personality_traits",
    foreignKeys = [ForeignKey(
        entity = PetProfile::class,
        parentColumns = ["id"],
        childColumns  = ["pet_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PersonalityTraits(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pet_id",           index = true) val petId: Long,
    @ColumnInfo(name = "openness")         val openness: Float         = 0.7f,
    @ColumnInfo(name = "conscientiousness") val conscientiousness: Float = 0.6f,
    @ColumnInfo(name = "extraversion")     val extraversion: Float     = 0.65f,
    @ColumnInfo(name = "agreeableness")    val agreeableness: Float    = 0.8f,
    @ColumnInfo(name = "neuroticism")      val neuroticism: Float      = 0.3f,
    @ColumnInfo(name = "core_quirk")       val coreQuirk: String       = "",
    @ColumnInfo(name = "speech_style")     val speechStyle: String     = "casual"
)

// ─── Pet Memory ───────────────────────────────────────────────────────────────

@Entity(
    tableName = "pet_memory",
    foreignKeys = [ForeignKey(
        entity = PetProfile::class,
        parentColumns = ["id"],
        childColumns  = ["pet_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PetMemory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pet_id",           index = true) val petId: Long,
    @ColumnInfo(name = "memory_key")       val memoryKey: String,
    @ColumnInfo(name = "memory_value")     val memoryValue: String,
    @ColumnInfo(name = "confidence")       val confidence: Float = 1.0f,
    @ColumnInfo(name = "last_reinforced")  val lastReinforced: Long = System.currentTimeMillis()
)

// ─── Interaction Record ───────────────────────────────────────────────────────

@Entity(
    tableName = "interaction_history",
    foreignKeys = [ForeignKey(
        entity = PetProfile::class,
        parentColumns = ["id"],
        childColumns  = ["pet_id"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class InteractionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "pet_id",           index = true) val petId: Long,
    @ColumnInfo(name = "trigger_type")     val triggerType: String,
    @ColumnInfo(name = "active_package")   val activePackage: String,
    @ColumnInfo(name = "prompt_summary")   val promptSummary: String,
    @ColumnInfo(name = "pet_response")     val petResponse: String,
    @ColumnInfo(name = "emotion_played")   val emotionPlayed: String,
    @ColumnInfo(name = "timestamp")        val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "user_acknowledged") val userAcknowledged: Boolean = false
)

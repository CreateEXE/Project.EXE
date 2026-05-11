package com.android.exe.data.dao

import androidx.room.*
import com.android.exe.data.entities.*
import kotlinx.coroutines.flow.Flow

// ─── PetProfile ───────────────────────────────────────────────────────────────

@Dao
interface PetProfileDao {
    @Query("SELECT * FROM pet_profile LIMIT 1")
    suspend fun getActive(): PetProfile?

    @Query("SELECT * FROM pet_profile LIMIT 1")
    fun observeActive(): Flow<PetProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: PetProfile): Long

    @Update
    suspend fun update(p: PetProfile)

    @Query("UPDATE pet_profile SET avatar_path = :path WHERE id = :id")
    suspend fun setAvatarPath(id: Long, path: String)

    @Query("UPDATE pet_profile SET llm_model_path = :path WHERE id = :id")
    suspend fun setModelPath(id: Long, path: String)

    @Query("UPDATE pet_profile SET current_mood = :mood, energy_level = :energy WHERE id = :id")
    suspend fun updateMoodEnergy(id: Long, mood: Float, energy: Float)

    @Query("UPDATE pet_profile SET interaction_count = interaction_count + 1 WHERE id = :id")
    suspend fun bumpInteractionCount(id: Long)
}

// ─── PersonalityTraits ────────────────────────────────────────────────────────

@Dao
interface PersonalityTraitsDao {
    @Query("SELECT * FROM personality_traits WHERE pet_id = :petId LIMIT 1")
    suspend fun getForPet(petId: Long): PersonalityTraits?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: PersonalityTraits): Long

    @Update
    suspend fun update(t: PersonalityTraits)
}

// ─── PetMemory ────────────────────────────────────────────────────────────────

@Dao
interface PetMemoryDao {
    @Query("SELECT * FROM pet_memory WHERE pet_id = :petId ORDER BY last_reinforced DESC LIMIT :limit")
    suspend fun getRecent(petId: Long, limit: Int = 20): List<PetMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: PetMemory): Long

    @Query("DELETE FROM pet_memory WHERE pet_id = :petId AND id NOT IN " +
           "(SELECT id FROM pet_memory WHERE pet_id = :petId ORDER BY last_reinforced DESC LIMIT 50)")
    suspend fun pruneOld(petId: Long)
}

// ─── InteractionHistory ───────────────────────────────────────────────────────

@Dao
interface InteractionHistoryDao {
    @Query("SELECT * FROM interaction_history WHERE pet_id = :petId " +
           "ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(petId: Long, limit: Int = 10): List<InteractionRecord>

    @Insert
    suspend fun insert(r: InteractionRecord): Long

    @Query("UPDATE interaction_history SET user_acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("DELETE FROM interaction_history WHERE pet_id = :petId AND " +
           "id NOT IN (SELECT id FROM interaction_history WHERE pet_id = :petId " +
           "ORDER BY timestamp DESC LIMIT 200)")
    suspend fun pruneOld(petId: Long)
}

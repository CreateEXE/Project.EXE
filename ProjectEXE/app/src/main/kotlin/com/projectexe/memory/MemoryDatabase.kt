package com.projectexe.memory

import android.content.Context
import androidx.room.*

@Entity(tableName="memories",
    indices=[Index("salience"),Index("type"),Index("timestamp")])
data class MemoryEntity(
    @PrimaryKey(autoGenerate=true) @ColumnInfo(name="id")        val id: Long = 0,
    @ColumnInfo(name="content")                                  val content: String,
    @ColumnInfo(name="type")                                     val type: MemoryType,
    @ColumnInfo(name="salience")                                 val salience: Float,
    @ColumnInfo(name="timestamp")                                val timestamp: Long
)

enum class MemoryType(val label: String) {
    CONVERSATION_SUMMARY("Conversation"), FACTUAL_EXCHANGE("Fact"),
    EMOTIONAL_MOMENT("Memory"), USER_DISCLOSURE("About User"),
    SYSTEM_EVENT("System"), USER_PREFERENCE("Preference")
}

class MemoryTypeConverters {
    @TypeConverter fun fromMemoryType(t: MemoryType): String = t.name
    @TypeConverter fun toMemoryType(v: String): MemoryType =
        try { MemoryType.valueOf(v) } catch (_: Exception) { MemoryType.CONVERSATION_SUMMARY }
}

@Dao interface MemoryDao {
    @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertMemory(m: MemoryEntity): Long
    @Query("SELECT * FROM memories ORDER BY salience DESC, timestamp DESC LIMIT :limit")
    suspend fun getMostRecentMemories(limit: Int): List<MemoryEntity>
    @Query("SELECT COUNT(*) FROM memories") suspend fun getMemoryCount(): Long
    @Query("DELETE FROM memories WHERE timestamp < :ts AND salience < :sal AND type != 'USER_DISCLOSURE' AND type != 'USER_PREFERENCE'")
    suspend fun pruneOld(ts: Long, sal: Float = 0.3f): Int
    @Query("DELETE FROM memories") suspend fun deleteAll()
}

@Database(entities=[MemoryEntity::class], version=1, exportSchema=true)
@TypeConverters(MemoryTypeConverters::class)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    companion object {
        @Volatile private var INSTANCE: MemoryDatabase? = null
        fun getInstance(ctx: Context): MemoryDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, MemoryDatabase::class.java, "exe_memory.db")
                .fallbackToDestructiveMigration().setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build().also { INSTANCE = it }
        }
    }
}

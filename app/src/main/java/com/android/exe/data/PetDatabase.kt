package com.android.exe.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.android.exe.data.dao.*
import com.android.exe.data.entities.*

@Database(
    entities = [
        PetProfile::class,
        PersonalityTraits::class,
        PetMemory::class,
        InteractionRecord::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class PetDatabase : RoomDatabase() {
    abstract fun petProfileDao(): PetProfileDao
    abstract fun personalityTraitsDao(): PersonalityTraitsDao
    abstract fun petMemoryDao(): PetMemoryDao
    abstract fun interactionHistoryDao(): InteractionHistoryDao

    companion object {
        @Volatile private var INSTANCE: PetDatabase? = null

        fun getInstance(context: Context): PetDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PetDatabase::class.java,
                    "pet_database"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

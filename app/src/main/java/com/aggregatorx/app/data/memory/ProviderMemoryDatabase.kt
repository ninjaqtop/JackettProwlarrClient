package com.aggregatorx.app.data.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Dao
interface ProviderSchemaDao {
    @Query("SELECT * FROM provider_schema WHERE providerId = :providerId LIMIT 1")
    suspend fun get(providerId: String): ProviderSchema?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schema: ProviderSchema)
}

@Dao
interface CorrectionLogDao {
    @Insert
    suspend fun insert(log: CorrectionLog)

    @Query("SELECT * FROM correction_log WHERE providerId = :providerId ORDER BY confidence DESC, timestamp DESC LIMIT :limit")
    suspend fun top(providerId: String, limit: Int): List<CorrectionLog>
}

@Database(
    entities = [ProviderSchema::class, CorrectionLog::class],
    version = 1,
    exportSchema = false
)
abstract class ProviderMemoryDatabase : RoomDatabase() {
    abstract fun providerSchemaDao(): ProviderSchemaDao
    abstract fun correctionLogDao(): CorrectionLogDao

    companion object {
        @Volatile private var instance: ProviderMemoryDatabase? = null

        fun get(context: Context): ProviderMemoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ProviderMemoryDatabase::class.java,
                "provider_memory_database"
            ).build().also { instance = it }
        }
    }
}

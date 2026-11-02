package com.toroid.inspector

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Entity ────────────────────────────────────────────────────────────────────
@Entity(tableName = "measurements")
data class MeasurementRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val partNumber: String = "",
    val operator: String = "",
    val odMm: Double? = null,
    val idMm: Double? = null,
    val htMm: Double? = null,
    val odStatus: String = "–",   // pass/fail/warn/–
    val idStatus: String = "–",
    val htStatus: String = "–",
    val overall: String = "–",
    val photoPath: String = "",    // path to saved frame image
    val batchId: String = ""
)

// ── DAO ───────────────────────────────────────────────────────────────────────
@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(record: MeasurementRecord): Long

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<MeasurementRecord>>

    @Query("SELECT * FROM measurements WHERE batchId = :batchId ORDER BY timestamp ASC")
    suspend fun getBatch(batchId: String): List<MeasurementRecord>

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("DELETE FROM measurements")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int
}

// ── Database ──────────────────────────────────────────────────────────────────
@Database(entities = [MeasurementRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "toroid_inspector.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

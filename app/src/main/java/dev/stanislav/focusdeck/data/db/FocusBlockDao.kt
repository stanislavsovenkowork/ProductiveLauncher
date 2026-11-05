package dev.stanislav.focusdeck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FocusBlockDao {
    @Query("SELECT * FROM focus_blocks WHERE dayEpochDay = :dayEpochDay ORDER BY startEpochMillis ASC")
    fun observeForDay(dayEpochDay: Long): Flow<List<FocusBlock>>

    @Query("SELECT * FROM focus_blocks WHERE endEpochMillis IS NULL ORDER BY startEpochMillis DESC LIMIT 1")
    fun observeActive(): Flow<FocusBlock?>

    @Query("SELECT * FROM focus_blocks WHERE dayEpochDay BETWEEN :startDay AND :endDay ORDER BY startEpochMillis ASC")
    fun observeInRange(startDay: Long, endDay: Long): Flow<List<FocusBlock>>

    @Query("SELECT * FROM focus_blocks ORDER BY startEpochMillis ASC")
    fun observeAllForExport(): Flow<List<FocusBlock>>

    @Query("SELECT * FROM focus_blocks ORDER BY startEpochMillis ASC")
    suspend fun allForExport(): List<FocusBlock>

    @Insert
    suspend fun insert(block: FocusBlock): Long

    @Update
    suspend fun update(block: FocusBlock)
}

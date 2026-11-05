package dev.stanislav.focusdeck.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY epochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WeightEntry>>

    @Insert
    suspend fun insert(entry: WeightEntry): Long

    @Query("SELECT * FROM weight_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): WeightEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReservedRow(entry: WeightEntry): Long

    @Transaction
    suspend fun insertReserved(entry: WeightEntry): WeightEntry {
        require(entry.id < -1L && entry.kg.isFinite() && entry.kg > 0f)
        val existing = findById(entry.id)
        if (existing != null) {
            check(existing == entry) { "Запись веса отличается от операции. Повтор остановлен." }
            return existing
        }
        insertReservedRow(entry)
        val stored = checkNotNull(findById(entry.id)) { "Не удалось подтвердить запись веса." }
        check(stored == entry) { "Идентификатор занят другой записью веса. Она не изменена." }
        return stored
    }
}

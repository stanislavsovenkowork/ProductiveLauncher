package dev.stanislav.focusdeck.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {
    @Query("SELECT * FROM meals WHERE dayEpochDay = :dayEpochDay ORDER BY epochMillis ASC")
    fun observeForDay(dayEpochDay: Long): Flow<List<Meal>>

    @Query(
        "SELECT dayEpochDay AS dayEpochDay, SUM(calories) AS calories FROM meals " +
            "WHERE dayEpochDay BETWEEN :fromDay AND :toDay GROUP BY dayEpochDay ORDER BY dayEpochDay DESC",
    )
    fun observeDailyTotals(fromDay: Long, toDay: Long): Flow<List<DailyCalories>>

    @Insert
    suspend fun insert(meal: Meal): Long

    @Query("SELECT * FROM meals WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): Meal?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReservedRow(meal: Meal): Long

    /** A replay may acknowledge the same immutable operation, never replace a row. */
    @Transaction
    suspend fun insertReserved(meal: Meal): Meal {
        require(meal.id < -1L) { "Invalid reserved meal identity" }
        val existing = findById(meal.id)
        if (existing != null) {
            check(existing == meal) { "Сохранённая запись отличается от операции. Автоповтор остановлен." }
            return existing
        }
        insertReservedRow(meal)
        val stored = checkNotNull(findById(meal.id)) { "Не удалось подтвердить запись еды." }
        check(stored == meal) { "Этот идентификатор занят другой записью. Она не изменена." }
        return stored
    }

    @Update
    suspend fun update(meal: Meal)

    @Delete
    suspend fun delete(meal: Meal)
}

/** Сумма калорий за день — для полоски последних дней. */
data class DailyCalories(val dayEpochDay: Long, val calories: Int)

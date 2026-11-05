package dev.stanislav.focusdeck.data.db

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the DAO's shared replay contract; real Room transactions are covered by device QA. */
class MealInsertSafetyTest {
    private val meal = Meal(-90001L, 20725L, MealType.LUNCH, "QA meal", 321, 123456L)

    @Test fun replayAcknowledgesOneIdenticalRow() = runBlocking {
        val dao = MemoryMeals()
        assertEquals(meal, dao.insertReserved(meal))
        assertEquals(meal, dao.insertReserved(meal))
        assertEquals(1, dao.rows.size)
    }

    @Test fun lostAcknowledgementAfterCommittedInsertDoesNotDuplicate() = runBlocking {
        val rows = mutableMapOf<Long, Meal>()
        val first = MemoryMeals(rows).apply { loseAcknowledgement = true }
        assertTrue(runCatching { first.insertReserved(meal) }.exceptionOrNull() is IOException)
        val reopened = MemoryMeals(rows)
        assertEquals(meal, reopened.insertReserved(meal))
        assertEquals(1, rows.size)
    }

    @Test fun occupiedIdentityWithAnyDifferentPayloadIsNeverOverwritten() = runBlocking {
        val dao = MemoryMeals()
        dao.insertReserved(meal)
        for (changed in listOf(meal.copy(dayEpochDay = meal.dayEpochDay + 1), meal.copy(type = MealType.DINNER),
            meal.copy(name = "other"), meal.copy(calories = 322), meal.copy(epochMillis = 123457L))) {
            assertTrue(runCatching { dao.insertReserved(changed) }.exceptionOrNull() is IllegalStateException)
            assertEquals(meal, dao.findById(meal.id))
            assertEquals(1, dao.rows.size)
        }
    }

    @Test fun reservedIdentityRejectsSentinelAndGeneratedId() = runBlocking {
        val dao = MemoryMeals()
        for (id in listOf(-1L, 0L, 1L, Long.MAX_VALUE)) {
            assertTrue(runCatching { dao.insertReserved(meal.copy(id = id)) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(dao.rows.isEmpty())
    }

    private class MemoryMeals(val rows: MutableMap<Long, Meal> = mutableMapOf()) : MealDao {
        var loseAcknowledgement = false
        override fun observeForDay(dayEpochDay: Long): Flow<List<Meal>> = flowOf(rows.values.filter { it.dayEpochDay == dayEpochDay })
        override fun observeDailyTotals(fromDay: Long, toDay: Long): Flow<List<DailyCalories>> = flowOf(emptyList())
        override suspend fun findById(id: Long): Meal? = rows[id]
        override suspend fun insert(meal: Meal): Long { check(meal.id !in rows); rows[meal.id] = meal; return meal.id }
        override suspend fun insertReservedRow(meal: Meal): Long {
            if (meal.id in rows) return -1L
            rows[meal.id] = meal
            if (loseAcknowledgement) throw IOException("Simulated lost acknowledgement")
            return meal.id
        }
        override suspend fun update(meal: Meal) { if (meal.id in rows) rows[meal.id] = meal }
        override suspend fun delete(meal: Meal) { rows.remove(meal.id) }
    }
}

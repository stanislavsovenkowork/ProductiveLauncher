package dev.stanislav.focusdeck.ui.nutrition

import dev.stanislav.focusdeck.data.db.MealType
import org.junit.Assert.*
import org.junit.Test

class NutritionWriteSafetyTest {
    @Test fun negativeAndPositiveEditIdentitiesRestoreExceptSentinel() {
        assertNull(restoredMealId(-1L))
        for (id in listOf(-2L, -918273645L, Long.MIN_VALUE, 1L, 123L)) {
            assertEquals(id, restoredMealId(id))
            assertEquals("draft_20725_$id", MealDraft(20725L, restoredMealId(id)).key)
        }
    }

    @Test fun recoveredCreationKeepsEveryPayloadFieldAndTimestamp() {
        val draft = MealDraft(day = 20725L, name = "  QA meal  ", calories = "321", type = MealType.DINNER,
            creationId = -937L, creationEpochMillis = 123456789L)
        val meal = draft.reservedMeal()
        assertEquals(-937L, meal.id)
        assertEquals(20725L, meal.dayEpochDay)
        assertEquals("QA meal", meal.name)
        assertEquals(321, meal.calories)
        assertEquals(MealType.DINNER, meal.type)
        assertEquals(123456789L, meal.epochMillis)
        assertEquals(meal, draft.copy(revision = 999L).reservedMeal())
        assertFalse(draft.editable(busy = false, needsCleanup = false))
    }

    @Test fun legacyUncertainCreationCannotBecomeANewInsert() {
        assertTrue(legacyMealRecovery(null, true, 0L))
        assertFalse(legacyMealRecovery(null, false, 0L))
        assertFalse(legacyMealRecovery(null, true, -123L))
        assertFalse(legacyMealRecovery(-123L, true, 0L))
        val draft = MealDraft(20725L, name = "QA", calories = "12", legacyUncertain = true)
        assertFalse(draft.editable(false, false))
        assertTrue(runCatching { draft.reservedMeal() }.isFailure)
    }

    @Test fun weightFailureRetainsInputAndAllowsRetry() {
        val draft = WeightDraft(20725L, "71,5", revision = 8)
        val saving = requireNotNull(draft.beginSave())
        assertEquals(71.5f, saving.kg!!, 0f)
        assertNull(saving.beginSave())
        val failed = saving.complete(saving, success = false)
        assertEquals("71,5", failed.text)
        assertFalse(failed.busy)
        assertNotNull(failed.error)
        assertNotNull(failed.beginSave())
    }

    @Test fun onlyAcknowledgedWeightRevisionIsCleared() {
        val saving = requireNotNull(WeightDraft(20725L, "71.5", revision = 8).beginSave())
        val success = saving.complete(saving, success = true)
        assertEquals("", success.text)
        assertFalse(success.busy)
        val newer = WeightDraft(20725L, "72", revision = 9)
        assertEquals(newer, newer.complete(saving, success = true))
        val otherDay = WeightDraft(20726L, "73", revision = 8)
        assertEquals(otherDay, otherDay.complete(saving, success = true))
        assertEquals(otherDay, otherDay.complete(saving, success = false))
    }

    @Test fun invalidWeightCannotStartSaving() {
        for (value in listOf("", "0", "-1", "NaN", "Infinity", "1.2.3")) {
            assertNull(WeightDraft(20725L, value).beginSave())
        }
    }
}

package dev.stanislav.focusdeck.ui.planning

import dev.stanislav.focusdeck.data.vault.VaultTask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanningSuggestionsTest {
    private val task = VaultTask(lineIndex = 1, title = "На завтра", done = false, durationMinutes = 45)

    @Test fun futureDayOffersAFreeSlotFromStartOfPlanningDay() {
        val state = PlanningUiState(
            selectedDate = LocalDate.now().plusDays(1),
            unscheduled = listOf(task),
            selectedTaskId = task.id,
            freeWindows = listOf(FreeWindow(DAY_START_MINUTE, DAY_END_MINUTE - DAY_START_MINUTE)),
            nowMinuteOfDay = 17 * 60,
        )
        assertEquals(listOf(DAY_START_MINUTE), state.suggestedSlots)
    }

    @Test fun todayStartsAfterNowAndPastDaysHaveNoQuickSuggestions() {
        val base = PlanningUiState(
            selectedDate = LocalDate.now(),
            unscheduled = listOf(task),
            selectedTaskId = task.id,
            freeWindows = listOf(FreeWindow(DAY_START_MINUTE, DAY_END_MINUTE - DAY_START_MINUTE)),
            nowMinuteOfDay = 17 * 60,
        )
        assertEquals(listOf(17 * 60), base.suggestedSlots)
        assertTrue(base.copy(selectedDate = LocalDate.now().minusDays(1)).suggestedSlots.isEmpty())
    }
}

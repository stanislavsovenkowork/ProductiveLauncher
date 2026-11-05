package dev.stanislav.focusdeck.data.repo

import dev.stanislav.focusdeck.data.db.FocusBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class FocusTimeTest {
    private val block = FocusBlock(title = "test", plannedMinutes = 25, startEpochMillis = 1_000, dayEpochDay = 1)

    @Test fun breakDoesNotAccrueFocusAfterItsSuggestedEnd() {
        val paused = block.copy(breakStartedAt = 61_000, firstBreakStartedAt = 61_000, breakCount = 1)
        assertEquals(60_000, paused.focusMillis(61_000 + BREAK_MILLIS + 200_000))
        val resumed = paused.afterReturn(61_000 + BREAK_MILLIS + 200_000)
        assertNull(resumed.breakStartedAt)
        assertEquals(60_000, resumed.focusMillis(61_000 + BREAK_MILLIS + 200_000))
        assertEquals(90_000, resumed.focusMillis(61_000 + BREAK_MILLIS + 230_000))
    }

    @Test fun finishingDuringBreakExcludesEntireBreakAndClockReversalNeverMakesNegativeTime() {
        val paused = block.copy(breakStartedAt = 61_000, firstBreakStartedAt = 61_000, breakCount = 1)
        val stopped = paused.afterStop(121_000).copy(endEpochMillis = 121_000)
        assertNull(stopped.firstBreakEndedAt)
        val exported = FocusEventExporter.jsonl(listOf(stopped.copy(category = "study",
            sessionId = "d6457bfa-352c-4da6-a271-f1f9c862c055", interrupted = true)))
        assertFalse(exported.contains("\"type\":\"resumed\""))
        assertEquals(60_000, stopped.focusMillis(999_000))
        assertEquals(0, block.focusMillis(0))
        // Returning after the wall clock moves backwards cannot subtract pause time.
        val reversed = paused.afterReturn(60_000)
        assertEquals(0, reversed.pausedMillis)
        assertEquals(61_000L, reversed.firstBreakEndedAt)
    }
}

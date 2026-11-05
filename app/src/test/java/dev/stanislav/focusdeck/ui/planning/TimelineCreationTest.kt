package dev.stanislav.focusdeck.ui.planning

import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineCreationTest {
    @Test fun longPressUsesTheNearestQuarterHourInsideTheFreeInterval() {
        assertEquals(10 * 60 + 15, creationMinuteAt(10 * 60 + 8))
        // Rounding at either edge must not place the task inside a neighbouring block.
        assertEquals(10 * 60 + 15, creationMinuteAt(10 * 60 + 5, 10 * 60 + 5, 11 * 60))
        assertEquals(10 * 60 + 45, creationMinuteAt(10 * 60 + 58, 10 * 60, 11 * 60))
        assertNull(creationMinuteAt(605, 601, 610))
    }

    @Test fun blankHoursBeforeTheWorkingDayAndTheLastSlotCanBeUsed() {
        assertEquals(15, creationMinuteAt(8))
        assertEquals(23 * 60 + 45, creationMinuteAt(1439))
    }

    @Test fun scheduledCreationWritesOneTaskWithTheAnchorDurationAndStableIdentity() {
        val date = LocalDate.of(2026, 9, 29)
        val note = DailyNoteParser.parse(date, "day.md", "## Задачи\n\n## Дневник\nСохранить этот текст")
        val updated = DailyNoteParser.addTask(note, "Создано на таймлайне", 615, 45, stableId = "timeline-test")
        val tasks = DailyNoteParser.parse(date, "day.md", updated).tasks
        assertEquals(1, tasks.size)
        assertEquals("Создано на таймлайне", tasks.single().title)
        assertEquals(615, tasks.single().startMinuteOfDay)
        assertEquals(45, tasks.single().durationMinutes)
        assertEquals("timeline-test", tasks.single().stableId)
        assertTrue(updated.contains("## Дневник\nСохранить этот текст"))
    }
}

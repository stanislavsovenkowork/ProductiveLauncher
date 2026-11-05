package dev.stanislav.focusdeck.data.repo

import dev.stanislav.focusdeck.data.db.FocusBlock
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusEventExporterTest {
    private val session = "d6457bfa-352c-4da6-a271-f1f9c862c055"
    private val block = FocusBlock(
        title = "private title must never be exported", plannedMinutes = 25,
        startEpochMillis = 1_760_000_000_000, endEpochMillis = 1_760_001_200_000,
        dayEpochDay = 1, sessionId = session, category = "study", taskStableId = "task-fixture",
        firstBreakStartedAt = 1_760_000_300_000, firstBreakEndedAt = 1_760_000_600_000,
        pausedMillis = 300_000, breakCount = 1,
        startUptimeMillis = 100_000, startBootCount = 7, startProcessId = "qa-process",
        pauseUptimeMillis = 400_000, breakBootCount = 7, pauseProcessId = "qa-process",
        resumeUptimeMillis = 700_000, resumeBootCount = 7, resumeProcessId = "qa-process",
        endUptimeMillis = 1_300_000, endBootCount = 7, endProcessId = "qa-process",
    )

    @Test fun emitsStableSeparateIntervalsWithoutTitlesOrDoubleCounting() {
        val output = FocusEventExporter.jsonl(listOf(block))
        assertEquals(output, FocusEventExporter.jsonl(listOf(block.copy()))) // reconstructed persisted fields
        val ids = Regex("\"eventId\":\"([^\"]+)\"").findAll(output).map { it.groupValues[1] }.toList()
        assertEquals(4, ids.distinct().size)
        assertEquals(4, output.lines().filter { it.isNotEmpty() }.size)
        assertTrue(output.contains("\"focusSeconds\":300.0"))
        assertTrue(output.contains("\"focusSeconds\":600.0"))
        assertEquals(2, Regex("\\\"startedAt\\\"").findAll(output).count())
        assertFalse(output.contains("private title"))
        assertTrue(output.contains("\"category\":\"study\""))
        // Optional synthetic artifact for the cross-platform integration test; never uses personal data.
        System.getenv("FOCUSDECK_FIXTURE_PATH")?.let { path -> File(path).writeText(output) }
    }

    @Test fun clockSleepRebootAndProcessGapsAreMissingNotFocus() {
        for (changed in listOf(
            block.copy(pauseUptimeMillis = 100_500),
            block.copy(breakBootCount = 8),
            block.copy(pauseProcessId = "another-process"),
            block.copy(startUptimeMillis = null),
        )) {
            val pausedLine = FocusEventExporter.jsonl(listOf(changed)).lines().first { it.contains("\"type\":\"paused\"") }
            assertTrue(pausedLine.contains("\"coverage\":\"missing\""))
            assertFalse(pausedLine.contains("\"startedAt\""))
            assertTrue(pausedLine.contains("\"focusSeconds\":null"))
        }
    }

    @Test fun noDataAndBackwardsClockDoNotInventIntervals() {
        assertEquals("", FocusEventExporter.jsonl(emptyList()))
        val backwards = block.copy(endEpochMillis = block.startEpochMillis - 1,
            firstBreakStartedAt = null, firstBreakEndedAt = null)
        val output = FocusEventExporter.jsonl(listOf(backwards))
        assertEquals(2, output.lines().count { it.isNotEmpty() })
        assertFalse(output.contains("\"startedAt\""))
        assertTrue(output.contains("\"type\":\"completed\""))
        assertTrue(output.contains("\"coverage\":\"missing\""))
    }

    @Test fun unknownLegacyBlocksAreNotInventedAndPausedStopDoesNotCountBreak() {
        assertEquals("", FocusEventExporter.jsonl(listOf(block.copy(sessionId = null, category = null))))
        val stopped = block.copy(endEpochMillis = 1_760_000_450_000, firstBreakEndedAt = null,
            breakStartedAt = 1_760_000_300_000, interrupted = true)
        val output = FocusEventExporter.jsonl(listOf(stopped))
        assertEquals(1, Regex("\\\"startedAt\\\"").findAll(output).count())
        assertTrue(output.contains("\"focusSeconds\":300.0"))
        assertTrue(output.contains("\"type\":\"stopped\""))
        assertFalse(output.contains("\"type\":\"resumed\""))
    }
}

package dev.stanislav.focusdeck.ui.home

import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.DailySummary
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SummaryRetryQueueTest {
    private val day = LocalDate.of(2026, 9, 28)
    private val today = day.plusDays(1)
    private val root = "content://vault/a|b"
    private var disk = emptySet<String>()
    private fun queue() = SummaryRetryQueue(disk) { disk = it; true }

    @Test fun requestSurvivesRecreationWithExactDateAndRoot() {
        val request = queue().enqueue(day, root)
        assertEquals(listOf(request), queue().forRoot(root, today))
    }

    @Test fun falseWriteNeverAcknowledges() {
        val queue = queue()
        val request = queue.enqueue(day, root)
        queue.acknowledge(request, false)
        assertEquals(listOf(request), queue().forRoot(root, today))
    }

    @Test fun successfulWriteClearsOnlyItsRequest() {
        val queue = queue()
        val request = queue.enqueue(day, root)
        val other = queue.enqueue(day.minusDays(1), root)
        queue.acknowledge(request, true)
        assertEquals(listOf(other), queue().forRoot(root, today))
    }

    @Test fun manualRetriesAreIdempotent() {
        val queue = queue()
        val request = queue.enqueue(day, root)
        assertEquals(request, queue.enqueue(day, root))
        assertEquals(1, queue.pendingCount)
    }

    @Test fun oldAcknowledgementCannotEraseLateRoomChange() {
        val queue = queue()
        val old = queue.enqueue(day, root)
        val newer = queue.enqueue(day, root, changed = true)
        queue.acknowledge(old, true)
        assertEquals(listOf(newer), queue.forRoot(root, today))
    }

    @Test fun retryNeverSelectsOtherRootOrTodayOrFuture() {
        val queue = queue()
        val wanted = queue.enqueue(day, root)
        queue.enqueue(day, "other")
        queue.enqueue(today, root)
        queue.enqueue(today.plusDays(1), root)
        assertEquals(listOf(wanted), queue.forRoot(root, today))
        assertEquals(4, queue.pendingCount)
    }

    @Test fun failedCommitDoesNotLoseExistingRequest() {
        val stored = queue().enqueue(day, root)
        val broken = SummaryRetryQueue(disk) { false }
        assertThrows(IllegalStateException::class.java) { broken.acknowledge(stored, true) }
        assertEquals(listOf(stored), broken.forRoot(root, today))
        assertThrows(IllegalStateException::class.java) { broken.enqueue(day, root, changed = true) }
        assertEquals(listOf(stored), broken.forRoot(root, today))
    }

    @Test fun failureBeforeAckRetainsDurableWork() {
        queue().enqueue(day, root)
        // A read/write exception or process death does not call acknowledge.
        assertEquals(1, queue().pendingCount)
    }

    @Test fun malformedEntryCannotBecomeAnArchiveRequest() {
        val queue = SummaryRetryQueue(setOf("garbage", "2026-09-28||root")) { true }
        assertTrue(queue.forRoot(root, today).isEmpty())
        assertEquals(2, queue.pendingCount)
    }

    private fun summary() = DailySummary(10, 20, 1, 2, 0, 520, 2000)

    @Test fun missingDailyNoteDoesNotCreateWork() {
        assertFalse(summaryNeedsWrite(null, summary()))
    }

    @Test fun identicalSummaryDoesNotCreateNewWorkOnRefresh() {
        val note = DailyNoteParser.parse(day, "daily.md", "> [!summary]\n")
        val updated = DailyNoteParser.writeSummary(note, summary())
        val fresh = DailyNoteParser.parse(day, "daily.md", updated)
        assertFalse(summaryNeedsWrite(fresh, summary()))
    }

    @Test fun lateMealOrFocusChangeRequiresWrite() {
        val note = DailyNoteParser.parse(day, "daily.md", "> [!summary]\n")
        val fresh = DailyNoteParser.parse(day, "daily.md", DailyNoteParser.writeSummary(note, summary()))
        assertTrue(summaryNeedsWrite(fresh, summary().copy(calories = 900)))
        assertTrue(summaryNeedsWrite(fresh, summary().copy(focusMinutes = 35)))
    }

    @Test fun missingSummarySectionIsNotMistakenForSuccessfulSync() {
        val note = DailyNoteParser.parse(day, "daily.md", "# Existing daily note\n")
        assertTrue(summaryNeedsWrite(note, summary()))
    }
}

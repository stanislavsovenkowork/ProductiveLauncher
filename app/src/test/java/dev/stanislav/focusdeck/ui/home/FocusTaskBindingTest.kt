package dev.stanislav.focusdeck.ui.home

import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.VaultJournal
import dev.stanislav.focusdeck.data.vault.VaultTask
import dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FocusTaskBindingTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val root = "content://vault/original"
    private fun task(id: String? = "task-a", title: String = "Одинаковое дело", start: Int? = 600,
                     duration: Int? = 30, done: Boolean = false, line: Int = 4) =
        VaultTask(line, title, done, start, duration, stableId = id)
    private fun note(vararg tasks: VaultTask) = DailyNote(day, "daily.md", tasks.toList(), VaultJournal(), emptyList())
    private fun block(id: String? = "task-a") = FocusBlock(
        id = 73, title = "Одинаковое дело", plannedMinutes = 30, startEpochMillis = 123,
        dayEpochDay = day.toEpochDay(), taskStableId = id, taskVaultRoot = root, taskStartMinuteOfDay = 600,
    )

    @Test fun stableIdChoosesOriginalAmongSameTitleTasksInDifferentProjects() {
        val original = task().copy(projectId = "project-a")
        val other = task("task-b", line = 5).copy(projectId = "project-b")
        assertEquals(original, resolveFocusTask(block(), note(other, original), root))
    }
    @Test fun sessionTargetDoesNotChangeOriginalTaskBindingOrLegacyTimers() {
        val longTaskTimer = block(null).copy(plannedMinutes = 180, category = "study")
        assertEquals(25, sessionTargetMinutes(longTaskTimer))
        assertEquals(task(null, duration = 180), resolveFocusTask(longTaskTimer, note(task(null, duration = 180)), root))
        assertEquals(12, sessionTargetMinutes(longTaskTimer.copy(plannedMinutes = 12)))
        assertEquals(180, sessionTargetMinutes(longTaskTimer.copy(category = null)))
    }
    @Test fun stableIdSurvivesTitleTimeDurationAndLineChanges() {
        val renamed = task(title = "Новое название", start = 700, duration = 90, line = 44)
        assertEquals(renamed, resolveFocusTask(block(), note(renamed), root))
    }
    @Test fun missingStableIdNeverFallsBackToMatchingTitleOrLine() {
        assertNull(resolveFocusTask(block(), note(task("replacement")), root))
    }
    @Test fun duplicateStableIdIsAmbiguousEvenIfOneCandidateIsAlreadyDone() {
        assertNull(resolveFocusTask(block(), note(task(), task(done = true, line = 5)), root))
    }
    @Test fun wrongRootDayOrMissingNoteNeverMatches() {
        assertNull(resolveFocusTask(block(), note(task()), "content://vault/other"))
        assertNull(resolveFocusTask(block(), note(task()), null))
        assertNull(resolveFocusTask(block(), note(task()).copy(date = day.plusDays(1)), root))
        assertNull(resolveFocusTask(block(), null, root))
    }
    @Test fun preMigrationAndFreeTimersStayUnboundRegardlessOfTitle() {
        assertNull(resolveFocusTask(block().copy(taskVaultRoot = null, taskStableId = null, taskStartMinuteOfDay = null), note(task(null)), root))
        assertNull(resolveFocusTask(block().copy(taskVaultRoot = null), note(task()), root))
    }
    @Test fun emptyStableIdCannotDowngradeToLegacyMatching() {
        assertNull(resolveFocusTask(block(""), note(task(null)), root))
    }
    @Test fun legacyUsesUniqueTitleTimeAndDurationTogether() {
        val original = task(null, line = 99)
        assertEquals(original, resolveFocusTask(block(null), note(
            task(null, start = 660), task(null, duration = 45), task(null, title = "Другое"), original,
        ), root))
    }
    @Test fun duplicateLegacyTuplesAreRefusedAcrossProjectsOrCompletionStates() {
        val original = task(null).copy(projectId = "a")
        val other = task(null, done = true, line = 20).copy(projectId = "b")
        assertNull(resolveFocusTask(block(null), note(original, other), root))
    }
    @Test fun nullDurationUsesDefaultButCannotDisambiguateAnExplicitDefault() {
        val original = task(null, duration = null)
        val timer = block(null).copy(plannedMinutes = DEFAULT_TASK_MINUTES)
        assertEquals(original, resolveFocusTask(timer, note(original), root))
        assertNull(resolveFocusTask(timer, note(original, task(null, duration = DEFAULT_TASK_MINUTES, line = 8)), root))
    }
    @Test fun persistedBindingCanResolveOriginalDayAfterMidnightAndProcessRecreation() {
        val persisted = block().copy()
        val original = task(done = true)
        assertNull(resolveFocusTask(persisted, note(original).copy(date = day.plusDays(1)), root))
        assertEquals(original, resolveFocusTask(persisted, note(original), root))
    }
    @Test fun completionWritesBeforeFinishing() = runBlocking {
        val events = mutableListOf<String>()
        finishFocusBlockAfterTaskWrite({ events += "write"; true }, { events += "finish" })
        assertEquals(listOf("write", "finish"), events)
    }
    @Test fun falseWriteKeepsTimerActive() = runBlocking {
        var finished = false
        try {
            finishFocusBlockAfterTaskWrite({ false }, { finished = true })
            fail("Failed writes must not finish a timer")
        } catch (_: IllegalStateException) { }
        assertFalse(finished)
    }
    @Test fun thrownWriteKeepsTimerActive() = runBlocking {
        var finished = false
        try {
            finishFocusBlockAfterTaskWrite({ throw IOException("provider failed") }, { finished = true })
            fail("Expected failure")
        } catch (_: IOException) { }
        assertFalse(finished)
    }
    @Test fun cancellationPropagatesWithoutFinishing() = runBlocking {
        var finished = false
        try {
            finishFocusBlockAfterTaskWrite({ throw CancellationException("cancel") }, { finished = true })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertFalse(finished)
    }
    @Test fun finishFailureCanRetryAlreadyPersistedCheckbox() = runBlocking {
        var checked = false
        var finished = false
        try {
            finishFocusBlockAfterTaskWrite({ checked = true; true }, { throw IOException("DB failure") })
            fail("Expected DB failure")
        } catch (_: IOException) { }
        assertTrue(checked)
        assertFalse(finished)
        finishFocusBlockAfterTaskWrite({ checked }, { finished = true })
        assertTrue(finished)
    }
}

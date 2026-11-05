package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TaskNoteLinkTest {
    private val date = LocalDate.of(2026, 9, 29)
    private val link = "[[FocusDeck/Проекты/Утренний проект -- project-one|Утро ⏳ 09:00]]"
    private fun parse(text: String) = DailyNoteParser.parse(date, "daily.md", text)
    private fun example(): DailyNote = parse(DailyNoteParser.addTask(parse("## Задачи\n"),
        "Prepare outline", durationMinutes = 30, projectId = "project-one", stableId = "task-one", noteLink = link))

    @Test fun readableObsidianLinkDoesNotChangeTaskTitleOrInventStartTime() {
        val note = example()
        assertEquals("Prepare outline", note.tasks.single().title)
        assertNull(note.tasks.single().startMinuteOfDay)
        assertEquals(link, note.tasks.single().noteLink)
        assertTrue(note.lines.joinToString("\n").contains(link))
    }

    @Test fun movingResizingAndUnschedulingPreserveOriginalLinkText() {
        var note = example()
        note = parse(DailyNoteParser.scheduleTask(note, note.tasks.single().lineIndex, 720, 60))
        assertEquals(720, note.tasks.single().startMinuteOfDay)
        assertEquals(link, note.tasks.single().noteLink)
        note = parse(DailyNoteParser.setDuration(note, note.tasks.single().lineIndex, 90))
        assertEquals(link, note.tasks.single().noteLink)
        note = parse(DailyNoteParser.unscheduleTask(note, note.tasks.single().lineIndex))
        assertEquals(link, note.tasks.single().noteLink)
        assertNull(note.tasks.single().startMinuteOfDay)
    }

    @Test fun renamingTaskPreservesLinkAndStableIdentity() {
        val note = example()
        val changed = parse(DailyNoteParser.renameTask(note, note.tasks.single().lineIndex, "Write outline"))
        assertEquals(link, changed.tasks.single().noteLink)
        assertEquals("task-one", changed.tasks.single().stableId)
        assertEquals("Write outline", changed.tasks.single().title)
        assertNull(changed.tasks.single().startMinuteOfDay)
    }

    @Test fun assigningAnotherProjectReplacesOnlyManagedLink() {
        val note = example()
        val next = "[[FocusDeck/Проекты/Другой проект|Другой]]"
        val changed = parse(DailyNoteParser.linkTask(note, note.tasks.single().lineIndex,
            "project-two", null, null, "task-one", next))
        assertEquals(next, changed.tasks.single().noteLink)
        assertEquals("project-two", changed.tasks.single().projectId)
        assertFalse(changed.lines.joinToString("\n").contains(link))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedLinkCannotInjectANewDailyTask() {
        DailyNoteParser.addTask(parse("## Задачи\n"), "Safe", noteLink = "[[Project]]\n- [ ] injected")
    }
}

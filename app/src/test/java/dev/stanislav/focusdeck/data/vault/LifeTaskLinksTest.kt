package dev.stanislav.focusdeck.data.vault

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LifeTaskLinksTest {
    private val day = LocalDate.of(2026, 9, 29)
    private fun parse(raw: String) = DailyNoteParser.parse(day, DailyNoteParser.fileNameFor(day), raw)
    private fun taskNote() = parse(DailyNoteParser.addTask(parse(DailyNoteParser.template(day)),
        "Составить план главы", durationMinutes = 45, projectId = "project-123",
        goalId = "goal-123", areaId = "area-123", stableId = "stable-123"))

    @Test fun projectMetadataIsNotDisplayedAsTaskTitle() {
        val task = taskNote().tasks.single()
        assertEquals("Составить план главы", task.title)
        assertEquals("project-123", task.projectId)
        assertEquals("goal-123", task.goalId)
        assertEquals("area-123", task.areaId)
        assertEquals("stable-123", task.stableId)
    }

    @Test fun movingResizingRenamingAndUnschedulingKeepProjectAndIdentity() {
        var note = taskNote()
        val identity = note.tasks.single().id
        note = parse(DailyNoteParser.scheduleTask(note, note.tasks.single().lineIndex, 620, 75))
        note = parse(DailyNoteParser.setDuration(note, note.tasks.single().lineIndex, 90))
        note = parse(DailyNoteParser.renameTask(note, note.tasks.single().lineIndex, "Написать первую главу"))
        assertEquals(620, note.tasks.single().startMinuteOfDay)
        assertEquals(90, note.tasks.single().durationMinutes)
        note = parse(DailyNoteParser.unscheduleTask(note, note.tasks.single().lineIndex))
        val task = note.tasks.single()
        assertEquals("Написать первую главу", task.title)
        assertEquals("project-123", task.projectId)
        assertEquals("goal-123", task.goalId)
        assertEquals("area-123", task.areaId)
        assertEquals(identity, task.id)
        assertNull(task.startMinuteOfDay)
        assertTrue(note.lines[task.lineIndex].endsWith("^task-stable-123"))
    }

    @Test fun externalReorderAndRenameResolveStableTaskWithoutTouchingNamesake() {
        val original = taskNote()
        val old = original.tasks.single()
        val updated = parse(original.lines.joinToString("\n")
            .replace("## Задачи", "## Задачи\n\n- [ ] Составить план главы ^task-another-123")
            .replace("Составить план главы [dur::", "Название изменено на Mac [dur::"))
        val resolved = DailyNoteParser.resolveTask(updated, old)!!
        assertEquals("Название изменено на Mac", resolved.title)
        assertEquals(old.id, resolved.id)
        val toggled = parse(DailyNoteParser.toggleTask(updated, resolved.lineIndex, true))
        assertFalse(toggled.tasks.first { it.stableId == "another-123" }.done)
        assertTrue(toggled.tasks.first { it.stableId == old.stableId }.done)
    }

    @Test fun duplicateStableIdRefusesAmbiguousEdit() {
        val original = taskNote()
        val task = original.tasks.single()
        val duplicate = parse(original.lines.joinToString("\n").replace("## Задачи", "## Задачи\n- [ ] Копия ^task-stable-123"))
        assertNull(DailyNoteParser.resolveTask(duplicate, task))
    }

    @Test fun newlinesInTitleCannotCreateExtraTasksOrSections() {
        val note = parse(DailyNoteParser.addTask(parse(DailyNoteParser.template(day)), "План\n- [ ] Лишняя задача\r\n## Раздел"))
        assertEquals(1, note.tasks.size)
    }
}

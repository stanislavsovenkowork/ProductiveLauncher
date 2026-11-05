package dev.stanislav.focusdeck.data.life

import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.VaultJournal
import dev.stanislav.focusdeck.data.vault.VaultTask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeProgressTest {
    @Test fun milestonesAreParsedAndToggledWithoutTouchingOtherLines() {
        val text = "Контекст этапов\n- [ ] Структура\n- [x] Тексты\n  * [X] Вложенный\n- [ ]   \nобычная строка"
        val parsed = Milestones.parse(text)
        assertEquals(listOf("Структура", "Тексты", "Вложенный"), parsed.map { it.title })
        assertEquals(listOf(false, true, true), parsed.map { it.done })
        val toggled = Milestones.toggle(text, parsed[0].line, true)
        assertEquals(text.replace("- [ ] Структура", "- [x] Структура"), toggled)
        assertEquals(text, Milestones.toggle(text, 0, true))
    }

    @Test fun milestonesAddRemoveAndWizardLines() {
        assertEquals("- [ ] Один", Milestones.add("", "Один"))
        assertEquals("- [x] Один\n- [ ] Два", Milestones.add("- [x] Один\n", "Два\n"))
        assertEquals("- [ ] Два", Milestones.remove("- [x] Один\n- [ ] Два", 0))
        assertEquals("- [ ] Структура\n- [ ] Дизайн", Milestones.fromLines("Структура\n\n- Дизайн\n  "))
    }

    @Test fun progressIsMeasuredNeverInvented() {
        val bare = LifeEntry.draft(LifeKind.PROJECT, "Без этапов").copy(status = LifeStatus.ACTIVE)
        assertNull(LifeProgressRules.of(bare, listOf(bare)))
        val staged = bare.copy(fields = mapOf(LifeField.MILESTONES to "- [x] А\n- [ ] Б\n- [ ] В\n- [x] Г"))
        val progress = LifeProgressRules.of(staged, listOf(staged))!!
        assertEquals(2, progress.done)
        assertEquals(4, progress.total)
        assertEquals(0.5f, progress.fraction)
        assertEquals(1f, LifeProgressRules.of(bare.copy(status = LifeStatus.DONE), emptyList())!!.fraction)
    }

    @Test fun goalProgressAveragesItsMeasuredProjects() {
        val goal = LifeEntry.draft(LifeKind.GOAL, "Цель").copy(status = LifeStatus.ACTIVE)
        val half = LifeEntry.draft(LifeKind.PROJECT, "Половина").copy(goalId = goal.id,
            fields = mapOf(LifeField.MILESTONES to "- [x] 1\n- [ ] 2"))
        val done = LifeEntry.draft(LifeKind.PROJECT, "Готов").copy(goalId = goal.id, status = LifeStatus.DONE)
        val unmeasured = LifeEntry.draft(LifeKind.PROJECT, "Без этапов").copy(goalId = goal.id, status = LifeStatus.ACTIVE)
        val archived = LifeEntry.draft(LifeKind.PROJECT, "Архив").copy(goalId = goal.id, status = LifeStatus.ARCHIVED)
        val all = listOf(goal, half, done, unmeasured, archived)
        val progress = LifeProgressRules.of(goal, all)!!
        assertEquals(0.75f, progress.fraction)
        assertEquals(1, progress.done)
        assertEquals(3, progress.total)
        assertNull(LifeProgressRules.of(goal, listOf(goal)))
    }

    @Test fun activityCountsLinkedStepsAndForgivesEmptyWeeks() {
        val today = LocalDate.of(2026, 9, 29) // Tuesday
        fun note(date: LocalDate, vararg tasks: VaultTask) = DailyNote(date, "00 - $date.md", tasks.toList(), VaultJournal(), emptyList())
        val project = "p-123456"
        val notes = listOf(
            note(today, VaultTask(1, "Шаг", true, projectId = project), VaultTask(2, "Бытовое", true)),
            note(today.minusDays(2), VaultTask(1, "Шаг 2", true, projectId = project)),
            note(today.minusDays(15), VaultTask(1, "Старый", false, goalId = "g-123456")),
        )
        val activity = LifeActivity(today.minusDays(27), today, notes.flatMap(LifeActivity::stepsOf), notes.size, 0)
        assertEquals(3, activity.linkedSteps.size)
        val entry = LifeEntry.draft(LifeKind.PROJECT, "П").copy(id = project)
        assertEquals(2, activity.stepsFor(entry).size)
        assertEquals(2, LifeMomentum.doneBetween(activity.linkedSteps, today.minusDays(6), today))
        assertEquals(2, LifeMomentum.activeWeeks(activity.linkedSteps, today))
        val updated = activity.withDay(note(today, VaultTask(1, "Шаг", false, projectId = project)))
        assertEquals(1, LifeMomentum.doneBetween(updated.linkedSteps, today.minusDays(6), today))
        assertTrue(activity.withDay(note(today.plusDays(40))).steps == activity.steps)
    }

    @Test fun milestonesSurviveNoteRoundTrip() {
        val entry = LifeEntry.draft(LifeKind.PROJECT, "Проект").copy(fields = mapOf(
            LifeField.SUCCESS to "Готово", LifeField.MILESTONES to "- [x] Первый\n- [ ] Второй"))
        val parsed = LifeNoteCodec.parse(entry.relativePath, LifeNoteCodec.create(entry))!!
        assertEquals(entry.text(LifeField.MILESTONES), parsed.text(LifeField.MILESTONES))
        val ticked = parsed.copy(fields = parsed.fields + (LifeField.MILESTONES to Milestones.toggle(parsed.text(LifeField.MILESTONES), 1, true)))
        val merged = LifeNoteCodec.parse(entry.relativePath, LifeNoteCodec.merge(parsed, ticked, parsed, LocalDate.now()))!!
        assertEquals(2, Milestones.parse(merged.text(LifeField.MILESTONES)).count { it.done })
    }
}

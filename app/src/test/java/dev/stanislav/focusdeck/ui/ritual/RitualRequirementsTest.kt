package dev.stanislav.focusdeck.ui.ritual

import dev.stanislav.focusdeck.data.db.Meal
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RitualRequirementsTest {
    private val day = LocalDate.of(2026, 9, 28)
    private fun note(text: String) = DailyNoteParser.parse(day, "test.md", text)
    private fun plan(tasks: String) = note("## Задачи\n$tasks\n\n## FocusDeck\n- Главное: Работа\n")

    @Test fun punctuationWhitespaceAndRepeatedWordCannotCompleteJournal() {
        assertFalse(journalReadiness(".\n ".repeat(1000)).isReady)
        assertFalse(journalReadiness("работа ".repeat(1000)).isReady)
        assertFalse(journalReadiness("123456789 ".repeat(1000)).isReady)
    }

    @Test fun substantialRussianReflectionAcceptsParagraphsAndPreservesText() {
        val paragraph = "Сегодня получилось спокойно закончить важную работу обсудить планы команды приготовить хороший ужин прогуляться вечером заметить усталость отложить сообщения выбрать приоритет записать выводы сохранить силы завтра начать раньше"
        val result = journalReadiness(List(6) { paragraph }.joinToString("\n\n"))
        assertTrue(result.isReady)
        assertTrue(result.words >= 100)
        assertTrue(result.characters >= 600)
    }

    @Test fun coverageBoundaryIsExact() {
        assertFalse(planReadiness(plan("- [ ] Работа ⏳ 08:00 [dur:: 539]")).isReady)
        val enough = planReadiness(plan("- [ ] Работа ⏳ 08:00 [dur:: 540]"))
        assertTrue(enough.isReady)
        assertEquals(60, enough.coveragePercent)
    }

    @Test fun duplicatesAndNestedIntervalsDoNotCountTwice() {
        val result = planReadiness(plan("- [ ] Работа ⏳ 08:00 [dur:: 300]\n- [ ] Встреча ⏳ 09:00 [dur:: 120]\n- [ ] Дубликат ⏳ 08:00 [dur:: 300]"))
        assertEquals(300, result.plannedMinutes)
        assertTrue(result.hasOverlap)
        assertFalse(result.isReady)
    }

    @Test fun onlyWakingWindowCountsEvenForCompletedTasks() {
        val result = planReadiness(plan("- [x] Работа ⏳ 05:00 [dur:: 240]\n- [ ] Вечер ⏳ 22:00 [dur:: 120]"))
        assertEquals(120, result.plannedMinutes)
    }

    @Test fun unionAcrossSeparatedIntervalsPreservesRealGaps() {
        val result = planReadiness(plan("- [ ] Работа ⏳ 08:00 [dur:: 240]\n- [ ] Учёба ⏳ 14:00 [dur:: 300]"))
        assertEquals(540, result.plannedMinutes)
        assertTrue(result.isReady)
    }

    @Test fun unnamedBlocksCannotFillDay() {
        val result = planReadiness(plan("- [ ] ... ⏳ 08:00 [dur:: 600]"))
        assertEquals(0, result.plannedMinutes)
        assertFalse(result.isReady)
    }

    @Test fun foodRequiresEntriesAndConfirmationOfExactContents() {
        val meals = listOf(Meal(1, day.toEpochDay(), MealType.DINNER, "Овощи с рисом", 500, 0))
        val empty = note("")
        assertFalse(foodReady(empty, meals))
        val checked = note(RitualNotes.put(empty, RitualNotes.FOOD_CHECKED, foodFingerprint(meals)))
        assertTrue(foodReady(checked, meals))
        assertFalse(foodReady(checked, emptyList()))
        assertFalse(foodReady(checked, listOf(meals.single().copy(calories = 600))))
        assertFalse(foodReady(checked, listOf(meals.single().copy(name = "Рис"))))
        assertFalse(foodReady(checked, listOf(meals.single().copy(dayEpochDay = day.plusDays(1).toEpochDay()))))
    }

    @Test fun completionCannotBeForgedWithMarkerAndSingleDot() {
        val raw = note("## FocusDeck\n- Утро подтверждено: да\n- Вечер завершён: да\n- День отдыха: да\n")
        assertFalse(ritualProgress(RitualWindow(RitualPhase.MORNING, day, day), raw, raw).completed)
        assertFalse(ritualProgress(RitualWindow(RitualPhase.EVENING, day, day.plusDays(1)), raw, raw).completed)
    }
}

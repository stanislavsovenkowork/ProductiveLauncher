package dev.stanislav.focusdeck.ui.ritual

import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.VaultJournal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RitualEngineTest {
    private val date = LocalDate.of(2026, 9, 25)

    private fun note(day: LocalDate, tasks: String = "", evening: String = "", morning: String = "") =
        DailyNoteParser.parse(day, "test.md", """
            ## Задачи
            $tasks

            # Day planner
            plugin content stays here

            <details class="daily-journal">
            <summary>Дневник</summary>
            - Утро: $morning
            - День:
            - Вечер: $evening
            - Выводы:
            </details>
        """.trimIndent() + "\n")

    @Test fun eveningCrossesMidnightWithoutChangingTargetDay() {
        assertEquals(RitualPhase.MORNING,
            ritualWindow(LocalDateTime.of(2026, 9, 25, 22, 0)).phase)
        assertEquals(RitualPhase.MORNING,
            ritualWindow(LocalDateTime.of(2026, 9, 25, 22, 59)).phase)
        assertEquals(RitualPhase.EVENING,
            ritualWindow(LocalDateTime.of(2026, 9, 25, 23, 0)).phase)
        val before = ritualWindow(LocalDateTime.of(2026, 9, 25, 23, 59))
        val after = ritualWindow(LocalDateTime.of(2026, 9, 26, 1, 0))
        assertEquals(before, after)
        assertEquals(date.plusDays(1), before.planDate)
        assertEquals(RitualPhase.MORNING,
            ritualWindow(LocalDateTime.of(2026, 9, 26, 6, 0)).phase)
    }

    @Test fun manualOpeningAfterMidnightDoesNotJumpToDayAfterTomorrow() {
        val atOneAm = LocalDateTime.of(2026, 9, 26, 1, 0)
        assertEquals(date, shownRitualWindow(atOneAm, manuallyOpened = true).journalDate)
        assertEquals(date.plusDays(1), shownRitualWindow(atOneAm, manuallyOpened = true).planDate)
        val inAfternoon = LocalDateTime.of(2026, 9, 25, 16, 0)
        assertEquals(date.plusDays(1), shownRitualWindow(inAfternoon, manuallyOpened = true).planDate)
    }

    @Test fun eveningReminderStartsAtTenThirtyWithoutBlocking() {
        assertFalse(shouldOfferEveningPreparation(LocalTime.of(22, 29), false, false))
        assertTrue(shouldOfferEveningPreparation(LocalTime.of(22, 30), false, false))
        assertTrue(shouldOfferEveningPreparation(LocalTime.of(22, 59), false, false))
        assertFalse(shouldOfferEveningPreparation(LocalTime.of(23, 0), false, false))
        assertFalse(shouldOfferEveningPreparation(LocalTime.of(22, 30), true, false))
        assertFalse(shouldOfferEveningPreparation(LocalTime.of(22, 30), false, true))
    }

    @Test fun focusBlockStartedBeforeElevenCanDeferOnlyToItsEndOrElevenFifteen() {
        val zone = ZoneId.of("Europe/Minsk")
        val window = ritualWindow(LocalDateTime.of(2026, 9, 25, 23, 0))
        fun millis(hour: Int, minute: Int) =
            LocalDateTime.of(2026, 9, 25, hour, minute).atZone(zone).toInstant().toEpochMilli()

        assertTrue(eveningFocusGrace(window, millis(22, 50), 50, millis(23, 5), zone))
        assertFalse(eveningFocusGrace(window, millis(22, 50), 50, millis(23, 15), zone))
        assertFalse(eveningFocusGrace(window, millis(22, 50), 20, millis(23, 10), zone))
        assertFalse(eveningFocusGrace(window, millis(23, 1), 50, millis(23, 5), zone))
    }

    @Test fun earlyPreparationIsNotAnInescapableGate() {
        val morning = ritualWindow(LocalDateTime.of(2026, 9, 25, 16, 0))
        val evening = ritualWindow(LocalDateTime.of(2026, 9, 25, 23, 0))
        assertFalse(shouldRequireRitual(evening, morning, date, false, false))
        assertTrue(shouldRequireRitual(evening, evening, date, false, false))
        assertFalse(shouldRequireRitual(evening, evening, date.plusDays(1), false, false))
        assertFalse(shouldRequireRitual(evening, evening, date, true, false))
        assertFalse(shouldRequireRitual(evening, evening, date, false, true))
        assertFalse(shouldRequireRitual(morning, morning, date, false, false, date))
    }

    @Test fun planRequiresNineCoveredHoursAndExplicitPriority() {
        val raw = note(date, "- [ ] Работа ⏳ 09:00 [dur:: 540]")
        assertFalse(planReadiness(raw).isReady)
        val selected = DailyNoteParser.parse(date, "test.md", RitualNotes.put(raw, RitualNotes.PRIORITY, "Работа"))
        assertTrue(planReadiness(selected).isReady)
        assertTrue(selected.lines.any { it.contains("plugin content stays here") })
        assertEquals("Работа", RitualNotes.value(selected, RitualNotes.PRIORITY))
    }

    @Test fun overlappingTasksDoNotInflateCoverage() {
        val raw = note(date, "- [ ] Первое ⏳ 09:00 [dur:: 50]\n- [ ] Второе ⏳ 09:30 [dur:: 30]")
        val selected = DailyNoteParser.parse(date, "test.md", RitualNotes.put(raw, RitualNotes.PRIORITY, "Первое"))
        assertTrue(planReadiness(selected).hasOverlap)
        assertEquals(60, planReadiness(selected).plannedMinutes)
        assertFalse(planReadiness(selected).isReady)
    }

    @Test fun taskOutsideDayCannotSatisfyRitual() {
        val raw = note(date, "- [ ] Ночь ⏳ 23:50 [dur:: 50]")
        val selected = DailyNoteParser.parse(date, "test.md", RitualNotes.put(raw, RitualNotes.PRIORITY, "Ночь"))
        assertTrue(planReadiness(selected).hasInvalidTime)
        assertFalse(planReadiness(selected).isReady)
    }

    @Test fun malformedTaskMustBeCorrectedBeforeCompletion() {
        val raw = note(date,
            "- [ ] Неверный импорт ⏳ 25:00 [dur:: 540]\n" +
                "- [ ] Реальная задача ⏳ 10:00 [dur:: 540]")
        val selected = DailyNoteParser.parse(date, "test.md",
            RitualNotes.put(raw, RitualNotes.PRIORITY, "Реальная задача"))
        assertTrue(planReadiness(selected).hasInvalidTime)
        assertEquals(1, planReadiness(selected).scheduledCount)
        assertFalse(planReadiness(selected).isReady)
    }

    @Test fun absurdDurationCannotOverflowIntoAValidPlan() {
        val raw = note(date, "- [ ] Импорт ⏳ 10:00 [dur:: 2147483647]")
        assertTrue(planReadiness(raw).hasInvalidTime)
        assertEquals(0, planReadiness(raw).scheduledCount)
        assertFalse(planReadiness(raw).isReady)
    }

    @Test fun restDayMarkerCannotBypassCoverage() {
        val raw = note(date)
        val rest = DailyNoteParser.parse(date, "test.md", RitualNotes.put(raw, RitualNotes.REST_DAY, "да"))
        assertFalse(planReadiness(rest).isReady)
        assertEquals(0, planReadiness(rest).scheduledCount)
    }

    @Test fun completionMarkerCannotBypassMissingRequirements() {
        val morningWindow = RitualWindow(RitualPhase.MORNING, date, date)
        val ready = note(date, "- [ ] Работа ⏳ 09:00 [dur:: 50]", morning = "Начну спокойно")
        val withPriority = DailyNoteParser.parse(date, "test.md", RitualNotes.put(ready, RitualNotes.PRIORITY, "Работа"))
        val morningDone = DailyNoteParser.parse(date, "test.md", RitualNotes.put(withPriority, RitualNotes.MORNING_DONE, "да"))
        assertFalse(ritualProgress(morningWindow, morningDone, morningDone).completed)
        assertFalse(ritualProgress(RitualWindow(RitualPhase.EVENING, date, date.plusDays(1)), morningDone, null).completed)
    }

    @Test fun multilineReflectionSurvivesRoundTripWithoutTouchingPlanner() {
        val original = note(date)
        val updated = DailyNoteParser.writeJournal(
            original,
            VaultJournal(evening = "Сделал главное\nПомешали звонки\nЗавтра начну раньше"),
        )
        val readBack = DailyNoteParser.parse(date, "test.md", updated)
        assertEquals("Сделал главное\nПомешали звонки\nЗавтра начну раньше",
            readBack.journal.evening)
        assertTrue(updated.contains("plugin content stays here"))
        assertTrue(updated.contains("  Помешали звонки"))
    }

    @Test fun missingSectionsCanBeCreatedWithoutReplacingOtherNoteContent() {
        val original = DailyNoteParser.parse(date, "test.md", "# Existing note\nPersonal text\n")
        val withTask = DailyNoteParser.addTask(original, "Важное", durationMinutes = 45)
        val afterTask = DailyNoteParser.parse(date, "test.md", withTask)
        val withJournal = DailyNoteParser.writeJournal(afterTask,
            VaultJournal(morning = "Начну с важного"))
        val result = DailyNoteParser.parse(date, "test.md", withJournal)
        assertEquals("Важное", result.tasks.single().title)
        assertEquals("Начну с важного", result.journal.morning)
        assertTrue(withJournal.contains("Personal text"))
    }

    @Test fun missingMorningFieldInsideExistingJournalIsRecovered() {
        val raw = DailyNoteParser.parse(date, "test.md", """
            # Other content
            <details class="daily-journal">
            <summary>Дневник</summary>
            - День: Только день
            </details>
        """.trimIndent())
        val written = DailyNoteParser.writeJournal(raw,
            VaultJournal(morning = "Первый шаг", day = "Только день"))
        val parsed = DailyNoteParser.parse(date, "test.md", written)
        assertEquals("Первый шаг", parsed.journal.morning)
        assertEquals("Только день", parsed.journal.day)
        assertTrue(written.contains("# Other content"))
    }

    @Test fun repeatedCommitUpdatesOneFieldAndKeepsOtherNotes() {
        val original = note(date, "- [ ] Работа ⏳ 09:00 [dur:: 50]")
        val once = RitualNotes.put(original, RitualNotes.PRIORITY, "Работа")
        val twice = RitualNotes.put(DailyNoteParser.parse(date, "test.md", once),
            RitualNotes.PRIORITY, "Работа")
        assertEquals(1, Regex("""^- Главное:""", RegexOption.MULTILINE).findAll(twice).count())
        assertTrue(twice.contains("plugin content stays here"))
    }

    @Test fun eveningReviewCountsOnlyScheduledCommitments() {
        val mixed = note(date,
            "- [x] Запланировано и сделано ⏳ 09:00 [dur:: 50]\n" +
                "- [ ] Запланировано и не сделано ⏳ 11:00 [dur:: 50]\n" +
                "- [x] Случайно сделано\n" +
                "- [ ] Пул без времени")
        val review = eveningReview(mixed)
        assertEquals(2, review.planned)
        assertEquals(1, review.completed)
        assertEquals(1, review.unplannedCompleted)
        assertEquals(listOf("Запланировано и не сделано"), review.unfinished.map { it.title })
    }
}

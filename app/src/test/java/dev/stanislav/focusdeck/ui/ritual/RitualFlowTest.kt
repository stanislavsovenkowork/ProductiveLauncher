package dev.stanislav.focusdeck.ui.ritual

import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.db.Meal
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RitualFlowTest {
    private val friday = LocalDate.of(2026, 9, 25)
    private val saturday = friday.plusDays(1)

    private fun parse(day: LocalDate, text: String): DailyNote =
        DailyNoteParser.parse(day, DailyNoteParser.fileNameFor(day), text)

    private val reflection = (1..6).joinToString(" ") {
        "Сегодня получилось спокойно закончить важную работу обсудить планы команды приготовить хороший ужин прогуляться вечером заметить усталость отложить сообщения выбрать приоритет записать выводы сохранить силы завтра начать раньше"
    }

    @Test fun eveningCarryAndNextMorningRoundTripKeepsHistoryAndPluginContent() {
        val fridayText = DailyNoteParser.addTask(
            parse(friday, DailyNoteParser.template(friday)),
            "Письмо",
            startMinuteOfDay = 10 * 60,
            durationMinutes = 50,
        )
        val fridayNote = parse(friday, fridayText)
        val review = eveningReview(fridayNote)
        assertEquals(listOf("Письмо"), review.unfinished.map { it.title })

        // Carry creates a new unscheduled task, leaving Friday's missed task visible.
        val carried = DailyNoteParser.addTask(
            parse(saturday, DailyNoteParser.template(saturday)),
            review.unfinished.single().title,
            durationMinutes = review.unfinished.single().durationMinutes,
        )
        val saturdayPool = parse(saturday, carried)
        assertFalse(saturdayPool.tasks.single().isScheduled)
        val scheduledShort = DailyNoteParser.scheduleTask(
            saturdayPool,
            saturdayPool.tasks.single().lineIndex,
            9 * 60,
            50,
        )
        val scheduled = DailyNoteParser.addTask(parse(saturday, scheduledShort), "Остальные дела", 9 * 60 + 50, 490)
        val withPriority = parse(saturday,
            RitualNotes.put(parse(saturday, scheduled), RitualNotes.PRIORITY, "Письмо"))
        val saturdayPlan = parse(saturday,
            RitualNotes.put(withPriority, RitualNotes.IF_THEN,
                "Если отвлекусь, то вернусь к первому абзацу"))

        val fridayWithReview = parse(friday, DailyNoteParser.writeJournal(
            fridayNote,
            fridayNote.journal.copy(evening = reflection),
        ))
        val meals = listOf(Meal(1, friday.toEpochDay(), MealType.DINNER, "Ужин", 600, 0))
        val withFood = parse(friday, RitualNotes.put(fridayWithReview, RitualNotes.FOOD_CHECKED, foodFingerprint(meals)))
        val fridayFinished = parse(friday,
            RitualNotes.put(withFood, RitualNotes.EVENING_DONE, "да"))
        val evening = RitualWindow(RitualPhase.EVENING, friday, saturday)
        assertTrue(ritualProgress(evening, fridayFinished, saturdayPlan, meals).canComplete)
        assertTrue(ritualProgress(evening, fridayFinished, saturdayPlan, meals).completed)
        assertEquals("Письмо", fridayFinished.tasks.single().title)
        assertFalse(fridayFinished.tasks.single().done)
        assertTrue(fridayFinished.lines.joinToString("\n").contains("movebreak-daily-plan"))

        val saturdayMorning = parse(saturday, DailyNoteParser.writeJournal(
            saturdayPlan,
            saturdayPlan.journal.copy(morning = reflection),
        ))
        val morning = RitualWindow(RitualPhase.MORNING, saturday, saturday)
        assertTrue(ritualProgress(morning, saturdayMorning, saturdayMorning).canComplete)
        assertFalse(ritualProgress(morning, saturdayMorning, saturdayMorning).completed)
        val saturdayStarted = parse(saturday,
            RitualNotes.put(saturdayMorning, RitualNotes.MORNING_DONE, "да"))
        assertTrue(ritualProgress(morning, saturdayStarted, saturdayStarted).completed)
        assertTrue(saturdayStarted.lines.joinToString("\n").contains("movebreak-daily-plan"))
        assertEquals("Если отвлекусь, то вернусь к первому абзацу",
            RitualNotes.value(saturdayStarted, RitualNotes.IF_THEN))
    }
}

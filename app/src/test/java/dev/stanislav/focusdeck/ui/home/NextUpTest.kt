package dev.stanislav.focusdeck.ui.home

import dev.stanislav.focusdeck.data.vault.VaultTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что предлагает большая кнопка на главном экране. Правило простое на словах
 * («сейчас, иначе просроченное, иначе отдых»), но именно в нём легко разойтись
 * с ожиданием — поэтому оно проверяется без экрана.
 */
class NextUpTest {

    private var line = 0

    private fun task(
        title: String,
        start: Int?,
        duration: Int? = 60,
        done: Boolean = false,
    ) = VaultTask(
        lineIndex = line++,
        title = title,
        done = done,
        startMinuteOfDay = start,
        durationMinutes = duration,
    )

    @Test
    fun `идущая по времени задача важнее просроченной`() {
        val now = task("сейчас", start = 10 * 60, duration = 60)
        val overdue = task("проспал", start = 8 * 60, duration = 60)

        val result = resolveNextUp(listOf(overdue, now), minuteOfDay = 10 * 60 + 30)

        assertEquals(NextUp.Now(now), result)
    }

    @Test
    fun `без идущей берётся самая свежая из просроченных`() {
        val early = task("утро", start = 8 * 60, duration = 30)
        val late = task("полдень", start = 12 * 60, duration = 30)

        val result = resolveNextUp(listOf(early, late), minuteOfDay = 15 * 60)

        assertEquals(NextUp.Overdue(late), result)
    }

    @Test
    fun `сделанные задачи не предлагаются`() {
        val done = task("сделано", start = 10 * 60, duration = 60, done = true)

        val result = resolveNextUp(listOf(done), minuteOfDay = 10 * 60 + 30)

        assertEquals(NextUp.Rest(nextStartMinute = null), result)
    }

    @Test
    fun `задачи без времени не попадают под кнопку`() {
        val pooled = task("когда-нибудь", start = null)

        val result = resolveNextUp(listOf(pooled), minuteOfDay = 12 * 60)

        assertEquals(NextUp.Rest(nextStartMinute = null), result)
    }

    @Test
    fun `отдых знает, когда кончится`() {
        val evening = task("вечер", start = 18 * 60 + 30, duration = 90)
        val night = task("ночь", start = 22 * 60, duration = 60)

        val result = resolveNextUp(listOf(night, evening), minuteOfDay = 17 * 60)

        assertEquals(NextUp.Rest(nextStartMinute = 18 * 60 + 30), result)
    }

    @Test
    fun `пустой день — просто отдых`() {
        val result = resolveNextUp(emptyList(), minuteOfDay = 12 * 60)

        assertTrue(result is NextUp.Rest)
        assertNull((result as NextUp.Rest).nextStartMinute)
    }

    @Test
    fun `задача кончилась минута в минуту — уже не идёт`() {
        val finished = task("кончилась", start = 9 * 60, duration = 60)

        val result = resolveNextUp(listOf(finished), minuteOfDay = 10 * 60)

        assertEquals(NextUp.Overdue(finished), result)
    }

    @Test
    fun `подсказка восстановления появляется через 15 минут внутри блока`() {
        val planned = task("сейчас", start = 10 * 60, duration = 60)
        val activeSlot = NextUp.Now(planned)

        assertNull(resolveRecoveryCue(null, activeSlot, 10 * 60 + 14, focusActive = false))
        assertTrue(resolveRecoveryCue(null, activeSlot, 10 * 60 + 15, focusActive = false) != null)
        assertNull(resolveRecoveryCue(null, activeSlot, 10 * 60 + 15, focusActive = true))
    }

    @Test
    fun `ошибочный временной блок не становится следующей задачей`() {
        val malformed = task("невалидное время", start = 25 * 60, duration = 50)
        assertTrue(resolveNextUp(listOf(malformed), 12 * 60) is NextUp.Rest)
    }
}

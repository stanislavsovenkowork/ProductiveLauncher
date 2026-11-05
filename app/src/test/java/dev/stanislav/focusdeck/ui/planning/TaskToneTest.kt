package dev.stanislav.focusdeck.ui.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правило окраски блоков: оттенок задаёт положение в дне, а дорожка добавляет
 * лёгкий сдвиг. Всё это чистая арифметика, и проверяется она без экрана.
 */
class TaskToneTest {

    @Test
    fun `градиент растянут на рабочее окно, а не на сутки`() {
        assertEquals(0f, dayFraction(DAY_START_MINUTE), 0.0001f)
        assertEquals(1f, dayFraction(DAY_END_MINUTE), 0.0001f)
        assertEquals(0.5f, dayFraction((DAY_START_MINUTE + DAY_END_MINUTE) / 2), 0.0001f)
    }

    @Test
    fun `ночные часы прижимаются к краям, а не уходят за них`() {
        assertEquals(0f, dayFraction(0), 0.0001f)
        assertEquals(0f, dayFraction(DAY_START_MINUTE - 60), 0.0001f)
        assertEquals(1f, dayFraction(DAY_END_MINUTE + 60), 0.0001f)
    }

    @Test
    fun `чем позже задача, тем дальше она по градиенту`() {
        val morning = dayFraction(8 * 60)
        val noon = dayFraction(13 * 60)
        val evening = dayFraction(21 * 60)
        assertTrue(morning < noon)
        assertTrue(noon < evening)
    }

    @Test
    fun `первая дорожка не сдвигается`() {
        assertEquals(0f, laneToneShift(0), 0.0001f)
    }

    @Test
    fun `соседние дорожки расходятся в разные стороны`() {
        val first = laneToneShift(1)
        val second = laneToneShift(2)
        assertTrue("первая дорожка уходит вверх по градиенту", first > 0f)
        assertTrue("вторая — вниз, чтобы у края дня обе не упёрлись в крайний цвет", second < 0f)
        assertEquals(first, -second, 0.0001f)
    }

    @Test
    fun `сдвиг растёт с номером дорожки, но остаётся лёгким`() {
        assertTrue(laneToneShift(3) > laneToneShift(1))
        assertTrue("сдвиг не должен перекрывать ход градиента", laneToneShift(3) < 0.25f)
    }

    @Test
    fun `задачи с одинаковым временем начала получают разные оттенки`() {
        val sameStart = dayFraction(15 * 60)
        val laneZero = sameStart + laneToneShift(0)
        val laneOne = sameStart + laneToneShift(1)
        assertNotEquals(laneZero, laneOne)
    }
}

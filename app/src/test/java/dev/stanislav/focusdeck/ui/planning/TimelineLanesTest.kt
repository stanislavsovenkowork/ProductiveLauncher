package dev.stanislav.focusdeck.ui.planning

import dev.stanislav.focusdeck.data.vault.VaultTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раскладка пересекающихся блоков по дорожкам.
 *
 * Функция существовала и раньше, но её никто не вызывал, поэтому наложившиеся задачи
 * рисовались друг на друге. Тесты закрывают именно это: что дорожки считаются и что
 * ширину делит только затронутая группа, а не весь день.
 */
class TimelineLanesTest {

    private fun task(title: String, start: Int, duration: Int) = ScheduledItem.TaskBlock(
        task = VaultTask(lineIndex = title.hashCode(), title = title, done = false),
        startMinuteOfDay = start,
        durationMinutes = duration,
    )


    private fun laneOf(placed: List<PlacedItem>, title: String) =
        placed.first { it.item.title == title }

    @Test
    fun `непересекающиеся блоки занимают всю ширину`() {
        val placed = layoutLanes(
            listOf(task("Утро", 9 * 60, 60), task("Вечер", 18 * 60, 60)),
        )
        assertEquals(2, placed.size)
        assertTrue(placed.all { it.laneCount == 1 && it.lane == 0 })
    }

    @Test
    fun `два пересекающихся блока делят ширину пополам`() {
        val placed = layoutLanes(
            listOf(task("Первая", 10 * 60, 60), task("Вторая", 10 * 60 + 30, 60)),
        )
        assertEquals(setOf(0, 1), placed.map { it.lane }.toSet())
        assertTrue(placed.all { it.laneCount == 2 })
    }

    @Test
    fun `одинокий блок не сужается из-за пересечения в другое время`() {
        val placed = layoutLanes(
            listOf(
                task("Первая", 10 * 60, 60),
                task("Вторая", 10 * 60 + 30, 60),
                task("Вечер", 20 * 60, 60),
            ),
        )
        assertEquals(2, laneOf(placed, "Первая").laneCount)
        assertEquals(2, laneOf(placed, "Вторая").laneCount)
        // Вечерняя задача ни с чем не пересекается — ей вся ширина.
        assertEquals(1, laneOf(placed, "Вечер").laneCount)
        assertEquals(0, laneOf(placed, "Вечер").lane)
    }


    @Test
    fun `освободившаяся дорожка переиспользуется`() {
        val placed = layoutLanes(
            listOf(
                task("Длинная", 9 * 60, 180),
                task("Короткая 1", 9 * 60, 60),
                task("Короткая 2", 10 * 60, 60),
            ),
        )
        // Обе короткие идут встык, значит им хватает одной дорожки на двоих.
        assertEquals(laneOf(placed, "Короткая 1").lane, laneOf(placed, "Короткая 2").lane)
        assertTrue(placed.all { it.laneCount == 2 })
    }

    @Test
    fun `три наложившихся блока делят ширину на три`() {
        val placed = layoutLanes(
            listOf(
                task("A", 12 * 60, 90),
                task("B", 12 * 60 + 15, 90),
                task("C", 12 * 60 + 30, 90),
            ),
        )
        assertEquals(setOf(0, 1, 2), placed.map { it.lane }.toSet())
        assertTrue(placed.all { it.laneCount == 3 })
    }

    @Test
    fun `пустой список не роняет раскладку`() {
        assertTrue(layoutLanes(emptyList()).isEmpty())
    }
}

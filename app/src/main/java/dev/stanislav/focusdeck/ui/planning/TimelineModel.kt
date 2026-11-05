package dev.stanislav.focusdeck.ui.planning

import dev.stanislav.focusdeck.data.vault.VaultTask
import java.time.LocalDate
import java.time.ZoneId

/** Шаг сетки планирования. 15 минут — общепринятый дефолт. */
const val SNAP_MINUTES = 15

/**
 * Рабочее окно суток в минутах от полуночи.
 * Именно в минутах, а не LocalTime: LocalTime.of(24, 0) невалиден — часы только 0..23.
 */
const val DAY_START_MINUTE: Int = 6 * 60
const val DAY_END_MINUTE: Int = 24 * 60

/** Элемент на таймлайне. */
sealed interface ScheduledItem {
    val startMinuteOfDay: Int
    val durationMinutes: Int
    val title: String

    val endMinuteOfDay: Int get() = startMinuteOfDay + durationMinutes

    /** Задача — можно двигать и растягивать. */
    data class TaskBlock(
        val task: VaultTask,
        override val startMinuteOfDay: Int,
        override val durationMinutes: Int,
    ) : ScheduledItem {
        override val title: String get() = task.title
        val done: Boolean get() = task.done
    }

}

/** Свободный промежуток между занятыми блоками. */
data class FreeWindow(
    val startMinuteOfDay: Int,
    val durationMinutes: Int,
) {
    val endMinuteOfDay: Int get() = startMinuteOfDay + durationMinutes

    fun fits(minutes: Int): Boolean = durationMinutes >= minutes
}

/**
 * Доля прошедшего рабочего окна: 0 в [DAY_START_MINUTE], 1 в [DAY_END_MINUTE].
 * По ней берётся оттенок блока — градиент растянут на те часы, которые реально
 * планируют, а не на пустую ночь.
 */
fun dayFraction(minuteOfDay: Int): Float =
    ((minuteOfDay - DAY_START_MINUTE).toFloat() / (DAY_END_MINUTE - DAY_START_MINUTE))
        .coerceIn(0f, 1f)

/** Насколько сдвигается оттенок на соседней дорожке. Доля градиента, не цвет. */
private const val LANE_TONE_SHIFT = 0.07f

/**
 * Лёгкий сдвиг по градиенту для соседних дорожек.
 *
 * Оттенок зависит только от времени начала, поэтому две задачи, начинающиеся
 * в одну минуту, получали один цвет. Сдвиг разводит их, не ломая общего хода
 * градиента. Знак чередуется: у края дня соседние дорожки расходятся в разные
 * стороны, а не упираются обе в крайний цвет палитры.
 */
fun laneToneShift(lane: Int): Float {
    if (lane <= 0) return 0f
    val step = (lane + 1) / 2
    val sign = if (lane % 2 == 1) 1 else -1
    return sign * step * LANE_TONE_SHIFT
}

/** Приводит минуту к сетке снаппинга. */
fun snapMinutes(minute: Int, step: Int = SNAP_MINUTES): Int =
    ((minute.toFloat() / step).let { Math.round(it) }) * step

/** Keep a long-press anchor on the grid, inside the empty interval and before midnight. */
internal fun creationMinuteAt(minute: Int, fromMinute: Int = 0, toMinute: Int = DAY_END_MINUTE): Int? {
    val first = ((fromMinute + SNAP_MINUTES - 1) / SNAP_MINUTES) * SNAP_MINUTES
    val last = minOf((toMinute - 1) / SNAP_MINUTES * SNAP_MINUTES, DAY_END_MINUTE - SNAP_MINUTES)
    return if (first <= last) snapMinutes(minute).coerceIn(first, last) else null
}

/** Минута суток из epoch-времени. */
fun minuteOfDay(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
    val time = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()
    return time.hour * 60 + time.minute
}

/** Epoch-время из минуты суток для конкретной даты. */
fun epochMillisAt(
    date: LocalDate,
    minuteOfDay: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = date
    .atStartOfDay(zone)
    .plusMinutes(minuteOfDay.toLong())
    .toInstant()
    .toEpochMilli()

fun formatMinuteOfDay(minute: Int): String {
    val normalized = minute.coerceIn(0, 24 * 60)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

/**
 * Считает свободные окна внутри рабочего интервала.
 * Занятыми считаются и задачи, и события календаря.
 */
fun computeFreeWindows(
    items: List<ScheduledItem>,
    fromMinute: Int = DAY_START_MINUTE,
    toMinute: Int = DAY_END_MINUTE,
    minimumMinutes: Int = 15,
): List<FreeWindow> {
    if (items.isEmpty()) {
        val total = toMinute - fromMinute
        return if (total >= minimumMinutes) listOf(FreeWindow(fromMinute, total)) else emptyList()
    }

    // Схлопываем пересекающиеся занятые интервалы, иначе разрывы посчитаются неверно.
    val busy = items
        .map { it.startMinuteOfDay to it.endMinuteOfDay }
        .sortedBy { it.first }
        .fold(mutableListOf<Pair<Int, Int>>()) { acc, range ->
            val last = acc.lastOrNull()
            if (last != null && range.first <= last.second) {
                acc[acc.lastIndex] = last.first to maxOf(last.second, range.second)
            } else {
                acc += range
            }
            acc
        }

    val windows = mutableListOf<FreeWindow>()
    var cursor = fromMinute
    busy.forEach { (start, end) ->
        if (start > cursor) {
            val gap = minOf(start, toMinute) - cursor
            if (gap >= minimumMinutes) windows += FreeWindow(cursor, gap)
        }
        cursor = maxOf(cursor, end)
    }
    if (cursor < toMinute) {
        val gap = toMinute - cursor
        if (gap >= minimumMinutes) windows += FreeWindow(cursor, gap)
    }
    return windows
}

/**
 * Ближайшие окна, куда влезает задача указанной длительности.
 * Это основной путь размещения на телефоне — быстрее и надёжнее перетаскивания.
 */
fun suggestSlots(
    windows: List<FreeWindow>,
    durationMinutes: Int,
    afterMinute: Int,
    limit: Int = 4,
): List<Int> = windows
    .asSequence()
    .mapNotNull { window ->
        val earliest = maxOf(window.startMinuteOfDay, snapMinutes(afterMinute))
        val latestStart = window.endMinuteOfDay - durationMinutes
        if (earliest <= latestStart) snapMinutes(earliest) else null
    }
    .distinct()
    .take(limit)
    .toList()

/** Блок с назначенной дорожкой: пересекающиеся задачи делят ширину по горизонтали. */
data class PlacedItem(
    val item: ScheduledItem,
    val lane: Int,
    val laneCount: Int,
)

/**
 * Раскладывает блоки по дорожкам, чтобы пересекающиеся не рисовались друг на друге.
 *
 * Ширину делит не весь день, а только группа связанных пересечений: две задачи с 10 до 11
 * ужимаются вдвое, а одинокая вечерняя остаётся во всю ширину. Делить ширину на максимум
 * по всем суткам значило бы сузить весь день из-за одного накладывающегося часа.
 */
fun layoutLanes(items: List<ScheduledItem>): List<PlacedItem> {
    if (items.isEmpty()) return emptyList()

    val sorted = items.sortedWith(compareBy({ it.startMinuteOfDay }, { -it.durationMinutes }))
    val result = mutableListOf<PlacedItem>()
    var cluster = mutableListOf<ScheduledItem>()
    var clusterEnd = Int.MIN_VALUE

    fun flushCluster() {
        if (cluster.isEmpty()) return
        val laneEnds = mutableListOf<Int>()
        val lanes = IntArray(cluster.size)
        cluster.forEachIndexed { index, item ->
            val free = laneEnds.indexOfFirst { it <= item.startMinuteOfDay }
            if (free == -1) {
                laneEnds += item.endMinuteOfDay
                lanes[index] = laneEnds.lastIndex
            } else {
                laneEnds[free] = item.endMinuteOfDay
                lanes[index] = free
            }
        }
        cluster.forEachIndexed { index, item ->
            result += PlacedItem(item, lanes[index], laneEnds.size)
        }
        cluster = mutableListOf()
    }

    sorted.forEach { item ->
        // Новая группа начинается там, где очередной блок уже не задевает предыдущие.
        if (cluster.isNotEmpty() && item.startMinuteOfDay >= clusterEnd) {
            flushCluster()
            clusterEnd = Int.MIN_VALUE
        }
        cluster += item
        clusterEnd = maxOf(clusterEnd, item.endMinuteOfDay)
    }
    flushCluster()
    return result
}

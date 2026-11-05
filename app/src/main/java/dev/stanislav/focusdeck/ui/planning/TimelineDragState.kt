package dev.stanislav.focusdeck.ui.planning

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Что именно тянут.
 *
 * Растягивание разведено на две ручки: за нижнюю двигается конец, за верхнюю — начало,
 * а конец при этом стоит на месте. Одна ручка внизу заставляла бы переносить задачу
 * целиком только ради того, чтобы начать её на полчаса раньше.
 */
enum class DragMode { MOVE, RESIZE_START, RESIZE_END }

/**
 * Состояние выделения и перетаскивания на таймлайне.
 *
 * Жесты разведены намеренно, потому что раньше они спорили друг с другом:
 * касание — выделить (и только тогда показать ручки), долгое нажатие — взять и нести,
 * ручка — растянуть. Пока блок не выделен, попасть в ручку невозможно, поэтому
 * обычное касание у нижней кромки больше не меняет длительность.
 */
@Stable
class TimelineDragState {

    /** Выделенный блок: у него видны ручки и крестик. */
    var selectedTaskId by mutableStateOf<Long?>(null)

    var draggedTaskId by mutableStateOf<Long?>(null)
        private set

    var mode by mutableStateOf(DragMode.MOVE)
        private set

    /** Начало блока в минутах суток, уже привязанное к сетке. */
    var previewStartMinute by mutableIntStateOf(0)
        private set

    var previewDurationMinutes by mutableIntStateOf(0)
        private set

    /**
     * Задача, правка которой уже ушла в заметку, но новая версия файла ещё не прочитана.
     *
     * Без этого блок на долю секунды прыгал обратно: жест заканчивался, превью
     * сбрасывалось, и до ответа файловой системы блок рисовался на старом месте.
     */
    var committedTaskId by mutableStateOf<Long?>(null)
        private set

    /**
     * Позиция пальца по вертикали внутри видимой области — её ждёт автоскролл.
     * Нарочно nullable: пока жест не сообщил настоящую координату, автоскролл молчит.
     */
    var pointerYInViewport by mutableStateOf<Float?>(null)

    /** Сдвинулось ли хоть что-то за жест. Нажатие без движения ничего не записывает. */
    var moved by mutableStateOf(false)
        private set

    private var accumulatedPx = 0f
    private var originStartMinute = 0
    private var originDurationMinutes = 0

    val isDragging: Boolean get() = draggedTaskId != null

    fun isBusy(taskId: Long): Boolean = draggedTaskId == taskId || committedTaskId == taskId

    fun select(taskId: Long?) {
        selectedTaskId = taskId
    }

    fun begin(taskId: Long, startMinute: Int, durationMinutes: Int, dragMode: DragMode) {
        draggedTaskId = taskId
        committedTaskId = null
        mode = dragMode
        originStartMinute = startMinute
        originDurationMinutes = durationMinutes
        previewStartMinute = startMinute
        previewDurationMinutes = durationMinutes
        accumulatedPx = 0f
        moved = false
        pointerYInViewport = null
    }

    /** @return true, если привязанное к сетке значение изменилось — вызывающий даёт тик haptic. */
    fun onDelta(deltaPx: Float, hourHeightPx: Float): Boolean {
        accumulatedPx += deltaPx
        val deltaMinutes = (accumulatedPx / hourHeightPx * 60f).toInt()
        return when (mode) {
            DragMode.MOVE -> {
                val snapped = snapMinutes(originStartMinute + deltaMinutes)
                    .coerceIn(0, 24 * 60 - previewDurationMinutes)
                val changed = snapped != previewStartMinute
                previewStartMinute = snapped
                if (snapped != originStartMinute) moved = true
                changed
            }
            DragMode.RESIZE_END -> {
                val snapped = snapMinutes(originDurationMinutes + deltaMinutes)
                    .coerceIn(SNAP_MINUTES, 24 * 60 - previewStartMinute)
                val changed = snapped != previewDurationMinutes
                previewDurationMinutes = snapped
                if (snapped != originDurationMinutes) moved = true
                changed
            }
            DragMode.RESIZE_START -> {
                // Конец стоит на месте: насколько сдвинулось начало, настолько же
                // изменилась длительность в обратную сторону.
                val end = originStartMinute + originDurationMinutes
                val snapped = snapMinutes(originStartMinute + deltaMinutes)
                    .coerceIn(0, end - SNAP_MINUTES)
                val changed = snapped != previewStartMinute
                previewStartMinute = snapped
                previewDurationMinutes = end - snapped
                if (snapped != originStartMinute) moved = true
                changed
            }
        }
    }

    /** Автоскролл сдвинул содержимое — блок должен остаться под пальцем. */
    fun onAutoScrolled(deltaPx: Float, hourHeightPx: Float) {
        onDelta(deltaPx, hourHeightPx)
    }

    /**
     * Жест закончился правкой: превью остаётся на экране, пока не придёт новая
     * версия заметки. [clearCommitted] вызывает таймлайн, когда список обновился.
     */
    fun commit(taskId: Long) {
        committedTaskId = taskId
        draggedTaskId = null
        accumulatedPx = 0f
        moved = false
        pointerYInViewport = null
    }

    fun clearCommitted() {
        committedTaskId = null
    }

    /** Жест закончился без правки — просто отпускаем. */
    fun cancel() {
        draggedTaskId = null
        accumulatedPx = 0f
        moved = false
        pointerYInViewport = null
    }
}

@Composable
fun rememberTimelineDragState(): TimelineDragState = remember { TimelineDragState() }

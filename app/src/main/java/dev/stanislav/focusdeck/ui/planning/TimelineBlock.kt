package dev.stanislav.focusdeck.ui.planning

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import dev.stanislav.focusdeck.ui.theme.taskAccent
import dev.stanislav.focusdeck.ui.theme.taskFill
import dev.stanislav.focusdeck.ui.theme.tabularNums
import kotlin.math.roundToInt

/** Ниже этой высоты в блоке помещается только название. */
private const val COMPACT_HEIGHT_MINUTES = 40

/** Высота ручки растягивания — она же цель касания, поэтому не меньше 24dp. */
/** Зазор между блоками, идущими встык. Разделяет их без обводки. */
private val BLOCK_GAP: Dp = 3.dp

/** Доводка при смене дорожки: быстро и без раскачки. */
private val LANE_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private val HANDLE_HEIGHT: Dp = 24.dp

/** Доля ширины блока под ручку. Остаток по краям — под крестик и прокрутку. */
private const val HANDLE_WIDTH_FRACTION = 0.55f

/**
 * Минимальная высота выделенного блока.
 *
 * Две ручки по краям физически не помещаются в пятнадцатиминутный блок высотой 16dp,
 * а попасть в ручку в 8dp нельзя. Поэтому у выделенного блока растёт область касания,
 * но не цветное тело: длительность остаётся нарисованной честно.
 */
private val SELECTED_TOUCH_HEIGHT: Dp = 72.dp

@Composable
fun TimelineBlock(
    placed: PlacedItem,
    dragState: TimelineDragState,
    hourHeightPx: Float,
    onMove: (taskId: Long, startMinute: Int) -> Unit,
    onResize: (taskId: Long, startMinute: Int, durationMinutes: Int) -> Unit,
    onUnschedule: (taskId: Long) -> Unit,
    onHapticTick: () -> Unit,
    onHapticGrab: () -> Unit,
    onHapticDrop: () -> Unit,
    scrollOffsetPx: () -> Float,
) {
    when (val item = placed.item) {
        is ScheduledItem.TaskBlock -> TaskBlockView(
            item = item,
            placed = placed,
            dragState = dragState,
            hourHeightPx = hourHeightPx,
            onMove = onMove,
            onResize = onResize,
            onUnschedule = onUnschedule,
            onHapticTick = onHapticTick,
            onHapticGrab = onHapticGrab,
            onHapticDrop = onHapticDrop,
            scrollOffsetPx = scrollOffsetPx,
        )
    }
}

@Composable
private fun TaskBlockView(
    item: ScheduledItem.TaskBlock,
    placed: PlacedItem,
    dragState: TimelineDragState,
    hourHeightPx: Float,
    onMove: (Long, Int) -> Unit,
    onResize: (Long, Int, Int) -> Unit,
    onUnschedule: (Long) -> Unit,
    onHapticTick: () -> Unit,
    onHapticGrab: () -> Unit,
    onHapticDrop: () -> Unit,
    scrollOffsetPx: () -> Float,
) {
    val taskId = item.task.id
    val density = LocalDensity.current
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnResize by rememberUpdatedState(onResize)
    val currentTick by rememberUpdatedState(onHapticTick)
    val currentGrab by rememberUpdatedState(onHapticGrab)
    val currentDrop by rememberUpdatedState(onHapticDrop)

    // Состояние перетаскивания читает сам блок, а не список выше. Иначе любой шаг
    // жеста пересобирал бы все блоки сразу — отсюда и бралось дёрганье.
    val busy = dragState.isBusy(taskId)
    val dragging = dragState.draggedTaskId == taskId
    val start = if (busy) dragState.previewStartMinute else item.startMinuteOfDay
    val duration = if (busy) dragState.previewDurationMinutes else item.durationMinutes
    val selected = dragState.selectedTaskId == taskId

    // Оттенок по положению в дне: перенёс блок — сменился цвет.
    // Плюс лёгкий сдвиг по дорожке, чтобы задачи с одинаковым временем начала
    // не оказывались одного тона.
    val palette = LocalFocusDeckColors.current
    val tone = dayFraction(start) + laneToneShift(placed.lane)
    val accent = palette.taskAccent(tone)
    val bodyHeight = with(density) { (duration / 60f * hourHeightPx).toDp() }
        .coerceAtLeast(MIN_BLOCK_HEIGHT)
    val touchHeight = if (selected) maxOf(bodyHeight, SELECTED_TOUCH_HEIGHT) else bodyHeight
    val compact = bodyHeight < with(density) { (COMPACT_HEIGHT_MINUTES / 60f * hourHeightPx).toDp() }

    // Дорожки разъезжаются плавно. Раскладка пересчитывается в момент отпускания,
    // и без анимации два наложившихся блока мгновенно схлопывались до половины ширины.
    val lane by animateFloatAsState(placed.lane.toFloat(), LANE_SPRING, label = "lane")
    val laneCount by animateFloatAsState(
        placed.laneCount.toFloat(),
        LANE_SPRING,
        label = "laneCount",
    )

    // Жест живёт дольше, чем композиция, в которой он создан: pointerInput пересоздаётся
    // только при смене ключа, то есть id задачи. Без обёртки лямбда навсегда запоминала
    // время и длительность на момент первого показа блока — и после первой же правки
    // начинала тащить устаревшие значения, затирая только что записанное.
    val currentStart by rememberUpdatedState(start)
    val currentDuration by rememberUpdatedState(duration)

    /**
     * Позиция пальца считается из модели, а не из разметки: узел, который слушает
     * жест, едет вместе с пальцем, и его координаты отстают на кадр.
     */
    fun viewportY(localY: Float, topOffsetPx: Float): Float =
        localY + topOffsetPx - scrollOffsetPx()

    fun blockTopPx(): Float = dragState.previewStartMinute / 60f * hourHeightPx

    fun bodyBottomPx(): Float =
        blockTopPx() + dragState.previewDurationMinutes / 60f * hourHeightPx

    Box(
        modifier = Modifier
            .timelineBlock(
                startMinute = start,
                heightDp = bodyHeight,
                touchHeightDp = touchHeight,
                lane = lane,
                laneCount = laneCount,
                density = density,
                hourHeightPx = hourHeightPx,
                // Взятый и выделенный блоки рисуются поверх соседей по дорожке.
                zIndex = if (busy || selected) 1f else 0f,
            )
            .graphicsLayer {
                // Поднятие — только в draw-фазе, состав дерева при перетаскивании не трогаем.
                if (dragging) {
                    scaleX = 1.02f
                    shadowElevation = 10.dp.toPx()
                }
            },
    ) {
        // Цветное тело: ровно та высота, которой соответствует длительность,
        // минус волосяной зазор — им и отделяются блоки, идущие встык.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((bodyHeight - BLOCK_GAP).coerceAtLeast(MIN_BLOCK_HEIGHT - BLOCK_GAP))
                .clip(FocusRadius.chip)
                // Только заливка, без обводки. Тонкий контур выбивался из стиля
                // и тащил на себе весь контраст: соседние блоки различались рамкой,
                // а не цветом. Теперь их разделяют разница тона и зазор ниже.
                .background(palette.taskFill(tone, emphasised = dragging || selected))
                // Касание выделяет и снимает выделение. Ничего не удаляет и не двигает —
                // раньше тот же жест снимал задачу с расписания, и промах стоил времени.
                .pointerInput(taskId) {
                    detectTapGestures {
                        dragState.select(if (selected) null else taskId)
                    }
                }
                // Долгое нажатие — взять и нести. Только оно, без вариантов.
                .pointerInput(taskId) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val long = awaitLongPressOrCancellation(down.id)
                            ?: return@awaitEachGesture
                        long.consume()
                        dragState.select(taskId)
                        currentGrab()
                        dragState.begin(taskId, currentStart, currentDuration, DragMode.MOVE)
                        var lastY = viewportY(long.position.y, blockTopPx())
                        dragState.pointerYInViewport = lastY

                        drag(long.id) { change ->
                            change.consume()
                            val y = viewportY(change.position.y, blockTopPx())
                            dragState.pointerYInViewport = y
                            val delta = y - lastY
                            lastY = y
                            if (dragState.onDelta(delta, hourHeightPx)) currentTick()
                        }

                        val newStart = dragState.previewStartMinute
                        val changed = dragState.moved
                        currentDrop()
                        if (changed) {
                            dragState.commit(taskId)
                            currentOnMove(taskId, newStart)
                        } else {
                            dragState.cancel()
                        }
                    }
                },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = 12.dp,
                            end = if (selected) 40.dp else 10.dp,
                            top = if (compact) 2.dp else 6.dp,
                            bottom = if (compact) 2.dp else 6.dp,
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = item.title,
                        style = if (compact) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compact) {
                        Text(
                            text = "${formatMinuteOfDay(start)} – ${formatMinuteOfDay(start + duration)}",
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (selected) {
            // Крестик — единственный способ снять задачу с расписания, и он явный.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp)
                    .pointerInput(taskId) {
                        detectTapGestures {
                            dragState.select(null)
                            onUnschedule(taskId)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Снять с расписания",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            ResizeHandle(
                taskId = taskId,
                accentColor = accent,
                startMinute = start,
                durationMinutes = duration,
                dragState = dragState,
                hourHeightPx = hourHeightPx,
                mode = DragMode.RESIZE_START,
                topOffsetPx = { blockTopPx() },
                viewportY = ::viewportY,
                onTick = currentTick,
                onGrab = currentGrab,
                onDrop = currentDrop,
                onCommit = { s, d -> currentOnResize(taskId, s, d) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            ResizeHandle(
                taskId = taskId,
                accentColor = accent,
                startMinute = start,
                durationMinutes = duration,
                dragState = dragState,
                hourHeightPx = hourHeightPx,
                mode = DragMode.RESIZE_END,
                topOffsetPx = { bodyBottomPx() - with(density) { HANDLE_HEIGHT.toPx() } },
                viewportY = ::viewportY,
                onTick = currentTick,
                onGrab = currentGrab,
                onDrop = currentDrop,
                onCommit = { s, d -> currentOnResize(taskId, s, d) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset {
                        IntOffset(0, (bodyHeight.toPx() - HANDLE_HEIGHT.toPx()).roundToInt())
                    },
            )
        }
    }
}

/**
 * Ручка растягивания. Появляется только у выделенного блока — пока блок не выбран,
 * попасть в неё нельзя, и обычное касание у кромки не меняет длительность.
 */
@Composable
private fun BoxScope.ResizeHandle(
    taskId: Long,
    accentColor: androidx.compose.ui.graphics.Color,
    startMinute: Int,
    durationMinutes: Int,
    dragState: TimelineDragState,
    hourHeightPx: Float,
    mode: DragMode,
    topOffsetPx: () -> Float,
    viewportY: (Float, Float) -> Float,
    onTick: () -> Unit,
    onGrab: () -> Unit,
    onDrop: () -> Unit,
    onCommit: (startMinute: Int, durationMinutes: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentStart by rememberUpdatedState(startMinute)
    val currentDuration by rememberUpdatedState(durationMinutes)

    Box(
        modifier = modifier
            // Не на всю ширину: полоса во всю ширину накрывала крестик в углу, и нажатие
            // на него уходило в ручку. Заодно у краёв блока остаётся место для прокрутки.
            .fillMaxWidth(HANDLE_WIDTH_FRACTION)
            .height(HANDLE_HEIGHT)
            .pointerInput(taskId, mode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onGrab()
                    dragState.begin(taskId, currentStart, currentDuration, mode)
                    var lastY = viewportY(down.position.y, topOffsetPx())
                    dragState.pointerYInViewport = lastY

                    drag(down.id) { change ->
                        change.consume()
                        val y = viewportY(change.position.y, topOffsetPx())
                        dragState.pointerYInViewport = y
                        val delta = y - lastY
                        lastY = y
                        if (dragState.onDelta(delta, hourHeightPx)) onTick()
                    }

                    val newStart = dragState.previewStartMinute
                    val newDuration = dragState.previewDurationMinutes
                    val changed = dragState.moved
                    onDrop()
                    if (changed) {
                        dragState.commit(taskId)
                        onCommit(newStart, newDuration)
                    } else {
                        dragState.cancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(FocusRadius.pill)
                .background(accentColor),
        )
    }
}

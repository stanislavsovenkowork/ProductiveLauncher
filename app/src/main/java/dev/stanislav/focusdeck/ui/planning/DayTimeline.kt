package dev.stanislav.focusdeck.ui.planning

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ParentDataModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.tabularNums
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/** Высота часа. 64dp — рабочий дефолт: 15 минут дают 16dp, день читается почти целиком. */
val HOUR_HEIGHT: Dp = 64.dp

/** Колонка подписей часов. */
private val GUTTER_WIDTH: Dp = 52.dp

/** Минимальная видимая высота блока — иначе 15 минут схлопываются в нить. */
val MIN_BLOCK_HEIGHT: Dp = 24.dp

private val AUTOSCROLL_EDGE: Dp = 80.dp

@Composable
fun DayTimeline(
    items: List<PlacedItem>,
    freeWindows: List<FreeWindow>,
    nowMinuteOfDay: Int,
    dragState: TimelineDragState,
    scrollState: ScrollState,
    onMoveTask: (taskId: Long, newStartMinute: Int) -> Unit,
    onResizeTask: (taskId: Long, startMinute: Int, durationMinutes: Int) -> Unit,
    onUnscheduleTask: (taskId: Long) -> Unit,
    onTapFreeWindow: (minuteOfDay: Int) -> Unit,
    modifier: Modifier = Modifier,
    highlightDurationMinutes: Int? = null,
    onLongPressEmpty: (minuteOfDay: Int) -> Unit = {},
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val hourHeightPx = with(density) { HOUR_HEIGHT.toPx() }
    val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val halfHourColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val nowColor = MaterialTheme.colorScheme.primary

    var viewportHeightPx by remember { mutableIntStateOf(0) }
    val currentItems by rememberUpdatedState(items)
    val currentLongPress by rememberUpdatedState(onLongPressEmpty)


    // При открытии подводим к «сейчас»: сутки с полуночи никому не нужны.
    // Ждём, пока контент измерен — до этого maxValue == 0 и scrollTo уходит в никуда.
    var didInitialScroll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.maxValue }
            .filter { it > 0 }
            .first()
        if (!didInitialScroll) {
            val anchorMinute = if (nowMinuteOfDay >= 0) nowMinuteOfDay else DAY_START_MINUTE + 120
            val target = (anchorMinute / 60f * hourHeightPx - hourHeightPx * 2f)
                .coerceAtLeast(0f)
            scrollState.scrollTo(target.roundToInt())
            didInitialScroll = true
        }
    }

    // Пришла новая версия заметки — превью, показанное после правки, больше не нужно.
    LaunchedEffect(items) { dragState.clearCommitted() }

    AutoScrollWhileDragging(
        dragState = dragState,
        scrollState = scrollState,
        viewportHeightPx = { viewportHeightPx },
        hourHeightPx = hourHeightPx,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportHeightPx = it.height }
            .verticalScroll(scrollState)
            // Касание мимо блоков снимает выделение — иначе ручки остаются висеть.
            .pointerInput(Unit) {
                detectTapGestures { dragState.select(null) }
            },
    ) {
        Layout(
            modifier = Modifier
                .fillMaxWidth()
                // Coordinates belong to the full day content, so scrolling does not
                // change the minute under the finger. Children own task gestures.
                .pointerInput(hourHeightPx, gutterPx) {
                    detectTapGestures(onLongPress = { offset ->
                        if (offset.x < gutterPx) return@detectTapGestures
                        val rawMinute = (offset.y / hourHeightPx * 60f).toInt()
                        val start = currentItems.filter { it.item.endMinuteOfDay <= rawMinute }
                            .maxOfOrNull { it.item.endMinuteOfDay } ?: 0
                        val end = currentItems.filter { it.item.startMinuteOfDay > rawMinute }
                            .minOfOrNull { it.item.startMinuteOfDay } ?: DAY_END_MINUTE
                        if (currentItems.none { rawMinute >= it.item.startMinuteOfDay && rawMinute < it.item.endMinuteOfDay }) {
                            creationMinuteAt(rawMinute, start, end)?.let {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentLongPress(it)
                            }
                        }
                    }, onTap = { dragState.select(null) })
                }
                .drawBehind {
                    // Сетка рисуется, а не собирается из 24 composable.
                    repeat(25) { hour ->
                        val y = hour * hourHeightPx
                        drawLine(
                            color = gridColor,
                            start = Offset(gutterPx, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                        if (hour < 24) {
                            drawLine(
                                color = halfHourColor,
                                start = Offset(gutterPx, y + hourHeightPx / 2f),
                                end = Offset(size.width, y + hourHeightPx / 2f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        }
                    }
                    // Отрицательная минута — «сейчас» не на этом дне, линию не рисуем.
                    if (nowMinuteOfDay < 0) return@drawBehind
                    val nowY = nowMinuteOfDay / 60f * hourHeightPx
                    drawLine(
                        color = nowColor,
                        start = Offset(gutterPx - 8.dp.toPx(), nowY),
                        end = Offset(size.width, nowY),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(
                        color = nowColor,
                        radius = 4.dp.toPx(),
                        center = Offset(gutterPx - 8.dp.toPx(), nowY),
                    )
                },
            content = {
                repeat(24) { hour ->
                    key("label_$hour") {
                        Text(
                            text = "%02d".format(hour),
                            style = MaterialTheme.typography.labelSmall.tabularNums(),
                            color = if (nowMinuteOfDay / 60 == hour) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                            modifier = Modifier.hourLabel(hour, density),
                        )
                    }
                }

                freeWindows.forEach { window ->
                    val fits = highlightDurationMinutes?.let { window.fits(it) } ?: false
                    key("free_${window.startMinuteOfDay}") {
                        FreeWindowSlot(
                            window = window,
                            highlighted = highlightDurationMinutes != null && fits,
                            dimmed = highlightDurationMinutes != null && !fits,
                            hourHeightPx = hourHeightPx,
                            // Кладём туда, куда ткнули, а не в начало окна: раньше задача
                            // из середины дня уезжала к его началу и выглядела потерянной.
                            onClickAt = onTapFreeWindow,
                            onLongPressAt = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentLongPress(it)
                            },
                        )
                    }
                }

                items.forEach { placed ->
                    key(itemKey(placed.item)) {
                        TimelineBlock(
                            placed = placed,
                            dragState = dragState,
                            hourHeightPx = hourHeightPx,
                            onMove = onMoveTask,
                            onResize = onResizeTask,
                            onUnschedule = onUnscheduleTask,
                            onHapticTick = {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            },
                            onHapticGrab = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onHapticDrop = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            },
                            scrollOffsetPx = { scrollState.value.toFloat() },
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val totalHeight = (hourHeightPx * 24).roundToInt()
            val gutter = gutterPx.roundToInt()
            val blockLeft = gutter + with(density) { 8.dp.roundToPx() }
            val fullWidth = (width - blockLeft - with(density) { 12.dp.roundToPx() })
                .coerceAtLeast(0)
            val laneGap = with(density) { 4.dp.roundToPx() }

            fun laneWidth(count: Float): Int =
                ((fullWidth - laneGap * (count - 1f)) / count).roundToInt().coerceAtLeast(0)

            val placeables = measurables.map { measurable ->
                when (val data = measurable.parentData) {
                    is HourLabelData -> measurable.measure(Constraints())
                    is TimelineBlockData -> measurable.measure(
                        Constraints.fixed(
                            width = laneWidth(data.laneCount),
                            height = data.touchHeightPx,
                        ),
                    )
                    else -> measurable.measure(constraints)
                }
            }

            layout(width, totalHeight) {
                placeables.forEachIndexed { index, placeable ->
                    when (val data = measurables[index].parentData) {
                        is HourLabelData -> placeable.place(
                            x = with(density) { 12.dp.roundToPx() },
                            y = data.yPx - placeable.height / 2,
                        )
                        is TimelineBlockData -> placeable.place(
                            x = blockLeft +
                                (data.lane * (laneWidth(data.laneCount) + laneGap)).roundToInt(),
                            y = data.yPx,
                            // Взятый блок поверх остальных: иначе он ныряет под соседей.
                            zIndex = data.zIndex,
                        )
                        else -> placeable.place(0, 0)
                    }
                }
            }
        }
    }
}

/**
 * Свободный промежуток. Нажатие отдаёт минуту, в которую ткнули, а не начало окна.
 */
@Composable
private fun FreeWindowSlot(
    window: FreeWindow,
    highlighted: Boolean,
    dimmed: Boolean,
    hourHeightPx: Float,
    onClickAt: (minuteOfDay: Int) -> Unit,
    onLongPressAt: (minuteOfDay: Int) -> Unit,
) {
    val density = LocalDensity.current
    val height = with(density) { (window.durationMinutes / 60f * hourHeightPx).toDp() }
    val currentClick by rememberUpdatedState(onClickAt)
    val currentLongPress by rememberUpdatedState(onLongPressAt)

    Box(
        modifier = Modifier
            .timelineBlock(
                startMinute = window.startMinuteOfDay,
                heightDp = height,
                touchHeightDp = height,
                lane = 0f,
                laneCount = 1f,
                density = density,
                hourHeightPx = hourHeightPx,
                zIndex = -1f,
            )
            .clip(FocusRadius.chip)
            .then(
                if (highlighted) {
                    Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                } else {
                    Modifier
                },
            )
            .pointerInput(window.startMinuteOfDay, window.durationMinutes) {
                detectTapGestures(onLongPress = { offset ->
                    val minute = window.startMinuteOfDay + (offset.y / hourHeightPx * 60f).toInt()
                    creationMinuteAt(minute, window.startMinuteOfDay, window.endMinuteOfDay)
                        ?.let(currentLongPress)
                }, onTap = { offset ->
                    val minute = window.startMinuteOfDay + (offset.y / hourHeightPx * 60f).toInt()
                    currentClick(
                        snapMinutes(minute)
                            .coerceIn(window.startMinuteOfDay, window.endMinuteOfDay),
                    )
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        // Подпись только у заметных разрывов — иначе экран зарастает служебным текстом.
        if (window.durationMinutes >= 30) {
            Text(
                text = if (highlighted) "нажми, чтобы поставить сюда" else "${window.durationMinutes} мин свободно",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    highlighted -> MaterialTheme.colorScheme.primary
                    dimmed -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                },
            )
        }
    }
}

private fun itemKey(item: ScheduledItem): Any = when (item) {
    is ScheduledItem.TaskBlock -> "task_${item.task.id}"
}

// --- parentData: позиционирование по времени ---

private data class TimelineBlockData(
    val yPx: Int,
    val heightPx: Int,
    val touchHeightPx: Int,
    /**
     * Дорожка и их число — дробные, чтобы расхождение блоков можно было анимировать.
     * При целых значениях наложившиеся задачи в момент отпускания мгновенно
     * прыгали на половину ширины.
     */
    val lane: Float,
    val laneCount: Float,
    val zIndex: Float,
)

private data class HourLabelData(val yPx: Int)

/**
 * Позиция и размер блока задаются временем, а не потоком разметки.
 *
 * [touchHeightDp] отдельно от [heightDp]: у выделенного блока область касания больше
 * цветного тела, чтобы в ручки можно было попасть даже у пятнадцатиминутной задачи.
 */
internal fun Modifier.timelineBlock(
    startMinute: Int,
    heightDp: Dp,
    touchHeightDp: Dp,
    lane: Float,
    laneCount: Float,
    density: Density,
    hourHeightPx: Float,
    zIndex: Float = 0f,
): Modifier = this.then(
    TimelineBlockElement(
        TimelineBlockData(
            yPx = (startMinute / 60f * hourHeightPx).roundToInt(),
            heightPx = with(density) { heightDp.roundToPx() },
            touchHeightPx = with(density) { touchHeightDp.roundToPx() },
            lane = lane,
            laneCount = laneCount.coerceAtLeast(1f),
            zIndex = zIndex,
        ),
    ),
)

private fun Modifier.hourLabel(hour: Int, density: Density): Modifier {
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    return this.then(HourLabelElement(HourLabelData((hour * hourPx).roundToInt())))
}

private data class TimelineBlockElement(
    val data: TimelineBlockData,
) : ModifierNodeElement<TimelineBlockNode>() {
    override fun create() = TimelineBlockNode(data)
    override fun update(node: TimelineBlockNode) {
        node.data = data
    }
}

private class TimelineBlockNode(var data: TimelineBlockData) :
    Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = data
}

private data class HourLabelElement(
    val data: HourLabelData,
) : ModifierNodeElement<HourLabelNode>() {
    override fun create() = HourLabelNode(data)
    override fun update(node: HourLabelNode) {
        node.data = data
    }
}

private class HourLabelNode(var data: HourLabelData) :
    Modifier.Node(), ParentDataModifierNode {
    override fun Density.modifyParentData(parentData: Any?): Any = data
}

// --- Автоскролл у краёв ---

@Composable
private fun AutoScrollWhileDragging(
    dragState: TimelineDragState,
    scrollState: ScrollState,
    viewportHeightPx: () -> Int,
    hourHeightPx: Float,
) {
    val density = LocalDensity.current
    val edgePx = with(density) { AUTOSCROLL_EDGE.toPx() }
    val maxSpeedPx = with(density) { 1200.dp.toPx() }
    val state by rememberUpdatedState(dragState)

    LaunchedEffect(dragState.isDragging) {
        if (!dragState.isDragging) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = (now - last) / 1_000_000_000f
            last = now

            // Пока жест не сообщил настоящую координату пальца, автоскроллу двигать нечего.
            val y = state.pointerYInViewport ?: continue

            // И пока пользователь не сдвинул блок хотя бы на шаг сетки — тоже нечего.
            // Захват часто начинается уже внутри краевой зоны: блок в 18:00 лежит у нижней
            // кромки экрана. Без этого условия простое касание ручки растягивало задачу
            // на часы, потому что лента ехала сама и подменяла это за движение пальца.
            if (!state.moved) continue
            val h = viewportHeightPx()
            if (h <= 0) continue

            // Квадратичный разгон: у края быстро, в начале зоны почти незаметно.
            val factor = when {
                y < edgePx -> -(1f - y / edgePx)
                y > h - edgePx -> 1f - (h - y) / edgePx
                else -> 0f
            }.coerceIn(-1f, 1f)

            if (factor != 0f) {
                val consumed = scrollState.scrollBy(
                    factor * factor * factor.sign * maxSpeedPx * dt,
                )
                if (consumed != 0f) state.onAutoScrolled(consumed, hourHeightPx)
            }
        }
    }
}

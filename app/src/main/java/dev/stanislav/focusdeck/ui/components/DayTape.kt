package dev.stanislav.focusdeck.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors

/**
 * Один сегмент ленты дня: доля от [0,1] по ширине суток и его тип.
 *
 * [tint] задаётся для запланированных задач — тем же цветом, что и блок на таймлайне,
 * чтобы полоска на главном экране и вкладка планирования читались как одно и то же.
 */
data class TapeSegment(
    val startFraction: Float,
    val widthFraction: Float,
    val kind: SegmentKind,
    val tint: Color? = null,
)

/**
 * PLANNED — то, что стоит в заметке на сегодня; FOCUS — время, которое реально
 * отработано таймером. Раньше плана на ленте не было вовсе, и она показывала
 * пустоту у всех, кто не пользуется таймером.
 */
enum class SegmentKind { PLANNED, FOCUS, MEETING, INTERRUPTED }

@Composable
fun DayTape(
    segments: List<TapeSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 22.dp,
    /** Полка над лентой под остриё метки «сейчас». Ноль — метка рисуется прямо на ленте. */
    markerGutter: Dp = 0.dp,
    nowFraction: Float? = null,
    /** Участок, показанный крупным планом. Рисуется рамкой поверх сегментов. */
    window: ClosedFloatingPointRange<Float>? = null,
) {
    val plannedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val focusColor = MaterialTheme.colorScheme.primary
    val meetingColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val interruptedColor = LocalFocusDeckColors.current.attention
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val pinColor = MaterialTheme.colorScheme.onSurface

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height + markerGutter),
    ) {
        val gutter = markerGutter.toPx()
        val tapeHeight = size.height - gutter
        drawRoundRect(
            color = bgColor,
            topLeft = Offset(0f, gutter),
            size = Size(size.width, tapeHeight),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        )
        // Порядок отрисовки — это слои: план снизу, поверх встречи, сверху то,
        // что действительно отработано. Иначе план перекрывал бы факт.
        listOf(
            SegmentKind.PLANNED to plannedColor,
            SegmentKind.MEETING to meetingColor,
            SegmentKind.FOCUS to focusColor,
            SegmentKind.INTERRUPTED to interruptedColor,
        ).forEach { (kind, color) ->
            segments.filter { it.kind == kind }.forEach {
                drawSegment(it, it.tint ?: color, gutter, tapeHeight)
            }
        }
        // Рамка окна — поверх сегментов, чтобы было видно, какой кусок суток
        // сейчас разобран крупно. Заливка внутри не годится: она перекрасила бы
        // задачи, ради цвета которых лента и существует.
        window?.let { range ->
            val left = range.start * size.width
            val right = range.endInclusive * size.width
            drawRoundRect(
                color = pinColor.copy(alpha = 0.16f),
                topLeft = Offset(left, gutter),
                size = Size((right - left).coerceAtLeast(2.dp.toPx()), tapeHeight),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            listOf(left, right).forEach { x ->
                drawRoundRect(
                    color = pinColor.copy(alpha = 0.55f),
                    topLeft = Offset(x - 1.dp.toPx(), gutter),
                    size = Size(2.dp.toPx(), tapeHeight),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                )
            }
        }
        // Та же метка, что в крупном плане: остриё на полке над лентой и линия
        // сквозь неё. Одна линия на ленте в 12dp терялась среди сегментов —
        // её не отличить от границы между двумя соседними задачами.
        nowFraction?.let { fraction ->
            val x = fraction * size.width
            val half = 5.dp.toPx()
            if (gutter > 0f) {
                drawPath(
                    path = Path().apply {
                        moveTo(x - half, 0f)
                        lineTo(x + half, 0f)
                        lineTo(x, gutter)
                        close()
                    },
                    color = pinColor,
                )
            }
            drawRoundRect(
                color = pinColor,
                topLeft = Offset(x - 1.dp.toPx(), gutter),
                size = Size(2.dp.toPx(), tapeHeight),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawSegment(seg: TapeSegment, color: Color, top: Float, height: Float) {
    val x = seg.startFraction * size.width
    val w = (seg.widthFraction * size.width).coerceAtLeast(2.dp.toPx())
    drawRoundRect(
        color = color,
        topLeft = Offset(x, top),
        size = Size(w, height),
        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
    )
}

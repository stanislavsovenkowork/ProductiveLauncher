package dev.stanislav.focusdeck.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.tabularNums

/** Что нарисовать в окне ближайших часов: доли считаются от левого края окна. */
data class UpcomingItem(
    val startFraction: Float,
    val widthFraction: Float,
    val title: String,
    val timing: String,
    val fill: Color,
    val clippedStart: Boolean,
)

private val STRIP_HEIGHT: Dp = 76.dp

/**
 * Ближайшие часы в натуральную величину.
 *
 * В отличие от ленты суток здесь у блока есть ширина, в которую влезает
 * название: окно в пару часов растянуто на весь экран, и задача на пятьдесят
 * минут занимает треть ширины, а не тридцать пикселей. Ради этого окно и
 * заведено — лента показывала форму дня, но не отвечала, что сейчас будет.
 *
 * Левый край окна — это «сейчас», поэтому отдельной метки времени нет:
 * всё, что нарисовано, лежит в будущем по определению.
 */
/** Свободный промежуток в окне: подписывается, если в него влезает подпись. */
data class UpcomingGapItem(
    val startFraction: Float,
    val widthFraction: Float,
    val label: String,
)

@Composable
fun UpcomingStrip(
    items: List<UpcomingItem>,
    gaps: List<UpcomingGapItem>,
    hourMarks: List<Float>,
    modifier: Modifier = Modifier,
    /** Где в окне «сейчас». Точка отсчёта, от которой считается час назад и час вперёд. */
    nowFraction: Float? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            // Над полосой оставлена полка под остриё метки: стоя прямо на блоках,
            // оно спорило с их подписями и читалось как часть задачи, а не как
            // отметка времени. Сверху же оно однозначно указывает на полосу.
            .height(MARKER_GUTTER + STRIP_HEIGHT),
    ) {
        val full = maxWidth

        Box(
            modifier = Modifier
                .padding(top = MARKER_GUTTER)
                .fillMaxSize()
                .clip(FocusRadius.chip)
                .background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
        gaps.forEach { gap ->
            val width = full * gap.widthFraction
            Box(
                modifier = Modifier
                    .offset(x = full * gap.startFraction)
                    .width(width)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                if (width > 72.dp) {
                    Text(
                        text = gap.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        items.forEach { item ->
            val width = (full * item.widthFraction).coerceAtLeast(3.dp)
            Box(
                modifier = Modifier
                    .offset(x = full * item.startFraction)
                    .width(width)
                    .fillMaxHeight()
                    // Обрезанный слева блок начался раньше окна: круглый край
                    // соврал бы, что задача начинается прямо сейчас.
                    .clip(if (item.clippedStart) FocusRadius.chipEnd else FocusRadius.chip)
                    .background(item.fill)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Узкой полоске подпись не нужна: обрезанное на втором символе
                // название читается хуже, чем честно пустой блок.
                if (width > 64.dp) {
                    Column2(title = item.title, timing = item.timing)
                }
            }
        }

        // Часовая сетка идёт поверх блоков, а не под ними: под ними её просто
        // не видно — заполненное окно закрывает её целиком. Поэтому линия
        // почти прозрачная: ей нужно давать чувство шкалы, а не спорить с
        // подписями задач.
        hourMarks.forEach { fraction ->
            Box(
                modifier = Modifier
                    .offset(x = full * fraction)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
            )
        }
        }

        // «Сейчас» — самый верхний слой: это точка отсчёта окна, и она должна
        // читаться поверх любого блока, включая светлые вечерние. Одной линии
        // для этого мало: на залитом блоке она теряется, поэтому сверху стоит
        // треугольник — его видно и краем глаза, и он сразу читается как метка,
        // а не как граница между задачами.
        nowFraction?.let { fraction ->
            NowMarker(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(x = full * fraction - MARKER_WIDTH / 2),
            )
        }
    }
}

private val MARKER_WIDTH: Dp = 12.dp

/** Высота полки над полосой: ровно остриё метки, без зазора — оно упирается в полосу. */
private val MARKER_GUTTER: Dp = 8.dp

/**
 * Метка «сейчас»: остриё на полке над полосой и тонкая линия сквозь саму полосу.
 * Линия нужна, чтобы ткнуть в конкретную минуту, остриё — чтобы её заметить.
 */
@Composable
private fun NowMarker(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.width(MARKER_WIDTH).fillMaxHeight()) {
        val half = size.width / 2f
        val gutter = MARKER_GUTTER.toPx()
        drawPath(
            path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(half, gutter)
                close()
            },
            color = color,
        )
        drawRect(
            color = color,
            topLeft = Offset(half - 1.dp.toPx(), gutter),
            size = Size(2.dp.toPx(), size.height - gutter),
        )
    }
}

@Composable
private fun Column2(title: String, timing: String) {
    androidx.compose.foundation.layout.Column {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = timing,
            style = MaterialTheme.typography.labelSmall.tabularNums(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

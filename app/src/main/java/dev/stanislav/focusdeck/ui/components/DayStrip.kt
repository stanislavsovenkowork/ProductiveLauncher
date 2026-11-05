package dev.stanislav.focusdeck.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.tabularNums
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Сколько дней держим в полосе по каждую сторону от сегодня. */
private const val DAYS_BACK = 60
private const val DAYS_FORWARD = 180

private val CELL_WIDTH: Dp = 46.dp
private val RUSSIAN: Locale = Locale.forLanguageTag("ru")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", RUSSIAN)

/**
 * Заголовок дня и скрытая под ним полоса дней.
 *
 * Полоса по умолчанию свёрнута и открывается нажатием на дату: развёрнутой она
 * постоянно занимала бы высоту у таймлайна, хотя нужна редко. Выбор дня её
 * закрывает — дальше человек работает с днём, а не с выбором дня.
 */
@Composable
fun DayHeader(
    selected: LocalDate,
    summary: String,
    daysWithNotes: Set<LocalDate>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    allowDateSelection: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val today = remember { LocalDate.now() }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(FocusRadius.field)
                    .then(if (allowDateSelection) Modifier.clickable(onClick = onToggle) else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selected.format(DATE_FORMAT).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (selected == today) "Сегодня · $summary" else summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (allowDateSelection) Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "Выбрать день",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(20.dp)
                        .rotate(arrow),
                )
            }
            // Возврат к сегодня нужен только когда ушли с него.
            if (allowDateSelection && selected != today) {
                Text(
                    text = "Сегодня",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(FocusRadius.pill)
                        .clickable { onSelect(today) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            trailing?.invoke()
        }

        if (allowDateSelection) AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            DayStrip(
                selected = selected,
                today = today,
                daysWithNotes = daysWithNotes,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun DayStrip(
    selected: LocalDate,
    today: LocalDate,
    daysWithNotes: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
) {
    val days = remember(today) {
        (-DAYS_BACK..DAYS_FORWARD).map { today.plusDays(it.toLong()) }
    }
    val listState = rememberLazyListState()

    // Открываем полосу сразу на выбранном дне, отступив пару дней назад,
    // чтобы соседние дни слева тоже были видны.
    LaunchedEffect(Unit) {
        val index = days.indexOf(selected)
        if (index >= 0) listState.scrollToItem((index - 2).coerceAtLeast(0))
    }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(days, key = { it.toEpochDay() }) { day ->
            DayCell(
                day = day,
                isToday = day == today,
                isSelected = day == selected,
                hasNote = day in daysWithNotes,
                onClick = { onSelect(day) },
            )
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    hasNote: Boolean,
    onClick: () -> Unit,
) {
    val weekday = day.dayOfWeek.getDisplayName(TextStyle.SHORT, RUSSIAN)
        .take(2)
        .replaceFirstChar { it.uppercase() }

    Column(
        modifier = Modifier
            .width(CELL_WIDTH)
            .clip(FocusRadius.field)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = weekday,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected || isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        Text(
            text = day.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium.tabularNums(),
            color = when {
                isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                isToday -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(top = 2.dp),
        )
        // Точка занимает место всегда, даже пустая: иначе числа прыгали бы
        // по вертикали в зависимости от того, заведён день или нет.
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(4.dp)
                .clip(FocusRadius.pill)
                .background(
                    when {
                        !hasNote -> Color.Transparent
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                ),
        )
    }
}

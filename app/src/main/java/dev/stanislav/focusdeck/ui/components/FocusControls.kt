package dev.stanislav.focusdeck.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors

/**
 * Поле ввода по спеке макета: заполненный контейнер, метка акцентным цветом,
 * никакой обводки. Material-овские Outlined/TextField сюда не подходят —
 * они рисуют рамку или индикатор, которых в макете нет.
 */
@Composable
fun FocusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    container: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val content: @Composable () -> Unit = {
        Column {
            label?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Box(
                // Минимальная высота обязательна и для поля без подложки: иначе
                // цель касания — одна строка текста, и палец промахивается мимо
                // соседнего поля. Границ не рисуем, но нажимать есть куда.
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    ),
                    singleLine = singleLine,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (container) {
        Box(
            modifier = modifier
                .clip(FocusRadius.field)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) { content() }
    } else {
        Box(modifier = modifier) { content() }
    }
}

/** Кнопка 48dp с полным скруглением — как в спеке. */
@Composable
fun FocusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    style: FocusButtonStyle = FocusButtonStyle.Filled,
    enabled: Boolean = true,
) {
    val container = when (style) {
        FocusButtonStyle.Filled -> MaterialTheme.colorScheme.primary
        FocusButtonStyle.Tonal -> MaterialTheme.colorScheme.secondaryContainer
        FocusButtonStyle.Outlined -> androidx.compose.ui.graphics.Color.Transparent
    }
    val contentColor = when (style) {
        FocusButtonStyle.Filled -> MaterialTheme.colorScheme.onPrimary
        FocusButtonStyle.Tonal -> MaterialTheme.colorScheme.onSecondaryContainer
        FocusButtonStyle.Outlined -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val alpha = if (enabled) 1f else 0.38f

    Box(
        modifier = modifier
            .height(48.dp)
            .clip(FocusRadius.pill)
            .background(container.copy(alpha = container.alpha * alpha))
            .then(
                if (style == FocusButtonStyle.Outlined) {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha),
                        FocusRadius.pill,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.let {
                Icon(
                    it,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = alpha),
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = alpha),
            )
        }
    }
}

enum class FocusButtonStyle { Filled, Tonal, Outlined }

/** Чип 32dp, радиус 8dp — по спеке. Активный заливается secondaryContainer. */
@Composable
fun FocusChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .height(32.dp)
            .clip(FocusRadius.chip)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, FocusRadius.chip)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val tint = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        icon?.let {
            Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/** Карточка-контейнер: 28dp, surfaceContainer, поля 16dp. */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /**
     * Внутренний отступ. Нулевой нужен карточкам со списком строк: тогда строка
     * отбивает отступ сама, и подсветка при нажатии идёт во всю ширину карточки,
     * а не обрывается там, где кончается её содержимое.
     */
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(FocusRadius.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
    ) { content() }
}

/** Общий отступ карточки — им же отбиваются строки внутри карточки без отступа. */
val FocusCardPadding: Dp = 16.dp

/** Кружок-иконка заголовка секции: 24dp, тональный контейнер по смыслу. */
@Composable
fun SectionIcon(
    icon: ImageVector,
    tone: SectionTone = SectionTone.Primary,
) {
    val bg = when (tone) {
        SectionTone.Primary -> MaterialTheme.colorScheme.primaryContainer
        SectionTone.Secondary -> MaterialTheme.colorScheme.secondaryContainer
        SectionTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
        SectionTone.Attention -> LocalFocusDeckColors.current.attentionContainer
    }
    val fg = when (tone) {
        SectionTone.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        SectionTone.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
        SectionTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        SectionTone.Attention -> LocalFocusDeckColors.current.onAttentionContainer
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(FocusRadius.pill)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
    }
}

/**
 * Attention — янтарная роль темы: «посмотри сюда», но это ещё не ошибка.
 * Просроченная задача именно такая: ничего не сломалось, время просто ушло.
 */
enum class SectionTone { Primary, Secondary, Neutral, Attention }

/** Строка заголовка секции: кружок + название, промежуток 16dp. */
@Composable
fun SectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    tone: SectionTone = SectionTone.Primary,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionIcon(icon, tone)
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        trailing?.invoke(this)
    }
}

/** Экранный заголовок со стрелкой назад — единый для всех подэкранов. */
@Composable
fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(FocusRadius.pill)
                        .clickable(onClick = it),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp),
            )
        }
        trailing?.invoke(this)
    }
}

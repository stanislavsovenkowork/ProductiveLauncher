package dev.stanislav.focusdeck.ui.nutrition

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.ui.components.FocusButton
import dev.stanislav.focusdeck.ui.components.FocusButtonStyle
import dev.stanislav.focusdeck.ui.components.FocusCard
import dev.stanislav.focusdeck.ui.components.FocusTextField
import dev.stanislav.focusdeck.ui.components.ScreenHeader
import dev.stanislav.focusdeck.ui.components.SectionHeader
import dev.stanislav.focusdeck.ui.components.SectionTone
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import dev.stanislav.focusdeck.ui.theme.tabularNums
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun mealTypeLabel(type: MealType) = when (type) {
    MealType.BREAKFAST -> "Завтрак"
    MealType.LUNCH -> "Обед"
    MealType.SNACK -> "Перекус"
    MealType.DINNER -> "Ужин"
}

private fun mealTypeIcon(type: MealType) = when (type) {
    MealType.BREAKFAST -> Icons.Filled.WbTwilight
    MealType.LUNCH -> Icons.Filled.LightMode
    MealType.SNACK -> Icons.Filled.Cookie
    MealType.DINNER -> Icons.Filled.DarkMode
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NutritionScreen(viewModel: NutritionViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val keyboardVisible = WindowInsets.isImeVisible
    var historyExpanded by remember { mutableStateOf(false) }
    val draft = state.draft
    val editing = draft.mealId != null
    val focusManager = LocalFocusManager.current
    val formEnabled = draft.editable(state.busy, state.needsCleanup)
    val weightDraft by viewModel.weightDraft.collectAsState()
    var targetInput by remember { mutableStateOf("") }
    var editingTarget by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stickyHeader(key = "header") { ScreenHeader(title = "Питание · ${state.date.dayOfMonth}.${state.date.monthValue}", onBack = { focusManager.clearFocus(); onBack() }) }
        if (!keyboardVisible || editingTarget) item(key = "summary") {
            FocusCard(contentPadding = 12.dp) {
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${state.totalCalories}",
                            style = MaterialTheme.typography.headlineSmall.tabularNums(),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "из ${state.calorieTarget} ккал",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 12.dp, bottom = 4.dp)
                                .clip(FocusRadius.chip)
                                .clickable {
                                    targetInput = state.calorieTarget.toString()
                                    editingTarget = true
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    ProgressTrack(
                        progress = state.calorieProgress,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = if (state.remainingCalories >= 0) {
                            "Осталось ${state.remainingCalories} ккал"
                        } else {
                            "Перебор на ${-state.remainingCalories} ккал"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.remainingCalories >= 0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            LocalFocusDeckColors.current.attention
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    if (editingTarget) {
                        Row(
                            modifier = Modifier.padding(top = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            FocusTextField(
                                value = targetInput,
                                onValueChange = { targetInput = it.filter { c -> c.isDigit() } },
                                placeholder = "Цель, ккал",
                                keyboardType = KeyboardType.Number,
                                container = false,
                                modifier = Modifier.width(120.dp),
                            )
                            FocusButton(
                                text = "Сохранить",
                                enabled = targetInput.toIntOrNull() != null,
                                onClick = {
                                    targetInput.toIntOrNull()?.let { viewModel.setTarget(it) }
                                    editingTarget = false
                                },
                            )
                        }
                    }
                    if (state.history.any { it.calories > 0 }) {
                        androidx.compose.material3.TextButton(onClick = { historyExpanded = !historyExpanded }) {
                            Text(if (historyExpanded) "Скрыть историю" else "История калорий")
                        }
                        if (historyExpanded)
                        HistoryStrip(
                            history = state.history,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
            }
        }
        item(key = "meal-editor") {
            FocusCard {
                Column {
                    Text(
                        text = if (draft.requiresRecovery) "Проверка сохранения" else if (!editing) "Добавить приём пищи" else "Правка записи",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (draft.requiresRecovery) {
                        Text(
                            text = if (draft.legacyUncertain)
                                "Предыдущее добавление могло завершиться. Проверь список выше: найденную запись можно открыть и исправить. После проверки убери этот черновик; новая запись автоматически не добавится."
                            else "Добавление уже начато. Заверши его с теми же данными: повтор не создаст вторую запись. Затем запись можно открыть и исправить.",
                            modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MealType.entries.forEach { type ->
                            Box(
                                Modifier.heightIn(min = 48.dp).clip(FocusRadius.pill)
                                    .background(if (draft.type == type) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .selectable(selected = draft.type == type, enabled = formEnabled, role = Role.RadioButton,
                                        onClick = { viewModel.updateMealType(type) })
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(mealTypeLabel(type), style = MaterialTheme.typography.labelLarge,
                                    color = if (draft.type == type) MaterialTheme.colorScheme.onSecondaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    MealDraftField(
                        value = draft.name,
                        onValueChange = viewModel::updateName,
                        label = "Что ел",
                        enabled = formEnabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    // Let actions wrap below the input on narrow screens and at large font sizes.
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        MealDraftField(
                            value = draft.calories,
                            onValueChange = viewModel::updateCalories,
                            label = "Ккал",
                            keyboardType = KeyboardType.Number,
                            enabled = formEnabled,
                            modifier = Modifier.width(96.dp),
                        )
                        FocusButton(
                            text = when {
                                state.busy -> "Сохраняю…"
                                draft.legacyUncertain -> "Проверено · убрать черновик"
                                state.needsCleanup || draft.requiresRecovery -> "Завершить запись"
                                editing -> "Сохранить"
                                else -> "Добавить"
                            },
                            icon = if (!editing && !state.busy && !state.needsCleanup && !draft.requiresRecovery) Icons.Filled.Add else null,
                            enabled = !state.busy && !state.loading && (draft.valid || state.needsCleanup || draft.legacyUncertain),
                            onClick = {
                                focusManager.clearFocus()
                                if (draft.legacyUncertain) viewModel.acknowledgeLegacyDraft() else viewModel.saveMeal()
                            },
                        )
                        if (editing) {
                            FocusButton(
                                text = "Новая запись",
                                style = FocusButtonStyle.Tonal,
                                enabled = !state.busy,
                                onClick = { focusManager.clearFocus(); viewModel.cancelEditing() },
                            )
                            FocusButton(
                                text = "Удалить",
                                style = FocusButtonStyle.Tonal,
                                enabled = !state.busy && !state.loading && !state.needsCleanup,
                                onClick = { focusManager.clearFocus(); viewModel.deleteEditing() },
                            )
                        }

                    }
                    state.error?.let { error ->
                        Text(error, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    if (state.draftSaving || draft.name.isNotEmpty() || draft.calories.isNotEmpty()) {
                        Text(when {
                                state.draftSaving -> "Сохраняю черновик…"
                                state.draftPersisted -> "Черновик сохранён на устройстве"
                                else -> "Черновик ещё не сохранён"
                            },
                            Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!keyboardVisible && state.meals.isNotEmpty()) item(key = "meals") {
            FocusCard {
                Column {
                    SectionHeader(
                        icon = Icons.Filled.Restaurant,
                        title = "Что было сегодня",
                        tone = SectionTone.Secondary,
                    )
                    if (state.meals.isEmpty()) {
                        Text(
                            text = "Пока ничего не записано",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    state.meals.forEach { meal ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(FocusRadius.field)
                                .clickable(enabled = !state.busy && !state.loading) { focusManager.clearFocus(); viewModel.startEditing(meal) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(FocusRadius.pill)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    mealTypeIcon(meal.type),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mealTypeLabel(meal.type),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${meal.name} · ${timeFormat.format(Date(meal.epochMillis))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Text(
                                text = "${meal.calories}",
                                style = MaterialTheme.typography.titleSmall.tabularNums(),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        item(key = "weight") {
            FocusCard(contentPadding = 12.dp) {
                Column {
                    Text(
                        text = "Вес",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(top = 12.dp),
                    ) {
                        Text(
                            text = state.recentWeights.firstOrNull()?.let { "%.1f".format(it.kg) } ?: "—",
                            style = MaterialTheme.typography.displaySmall.tabularNums(),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        state.weightDeltaKg?.let { delta ->
                            Text(
                                text = "кг · %+.1f за период".format(delta),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    if (state.recentWeights.size > 1) {
                        WeightChart(
                            values = state.recentWeights.map { it.kg }.reversed(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .height(56.dp),
                        )
                    }
                    FlowRow(
                        modifier = Modifier.padding(top = 16.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        MealDraftField(
                            value = weightDraft.text,
                            onValueChange = { viewModel.updateWeight(weightDraft.day, it) },
                            label = "Вес, кг",
                            keyboardType = KeyboardType.Decimal,
                            enabled = !weightDraft.busy && weightDraft.creationId == null,
                            modifier = Modifier.width(96.dp),
                        )
                        FocusButton(
                            text = when {
                                weightDraft.busy -> "Записываю…"
                                weightDraft.creationId != null -> "Завершить запись"
                                else -> "Записать"
                            },
                            enabled = !weightDraft.busy && weightDraft.kg != null,
                            onClick = {
                                focusManager.clearFocus()
                                viewModel.saveWeight(weightDraft.day)
                            },
                        )
                    }
                    weightDraft.error?.let { error ->
                        Text(error, Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Same borderless body style as FocusTextField, with a real disabled input state. */
@Composable
private fun MealDraftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
        decorationBox = { field ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(label, modifier = Modifier.clearAndSetSemantics {},
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            }
        },
    )
}

@Composable
private fun ProgressTrack(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(FocusRadius.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun WeightChart(values: List<Float>, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier = modifier) {
        if (values.isEmpty()) return@Canvas
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0.01f } ?: 1f
        val slot = size.width / values.size
        val barWidth = slot * 0.6f
        values.forEachIndexed { index, value ->
            val normalized = (value - min) / range
            val barHeight = (size.height * (0.25f + normalized * 0.75f)).coerceAtLeast(3f)
            drawRoundRect(
                color = if (index == values.lastIndex) barColor else trackColor,
                topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

/**
 * Неделя калорий столбиками. Цель — не график, а быстрый ответ на «как шла неделя»:
 * попал в цель — зелёный, перебрал — янтарный, не записывал — пустой след.
 */
@Composable
private fun HistoryStrip(history: List<DaySummary>, modifier: Modifier = Modifier) {
    val colors = LocalFocusDeckColors.current
    val maxValue = history.maxOf { maxOf(it.calories, it.target) }.coerceAtLeast(1)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        history.forEach { day ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(FocusRadius.chip)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (day.calories > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(
                                    (day.calories.toFloat() / maxValue).coerceIn(0.06f, 1f),
                                )
                                .clip(FocusRadius.chip)
                                .background(
                                    if (day.withinTarget) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        colors.attention
                                    },
                                ),
                        )
                    }
                }
                Text(
                    text = day.label,
                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

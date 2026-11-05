package dev.stanislav.focusdeck.ui.journal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.vault.VaultState
import dev.stanislav.focusdeck.ui.components.FocusCard
import dev.stanislav.focusdeck.ui.components.FocusButton
import dev.stanislav.focusdeck.ui.components.FocusButtonStyle
import dev.stanislav.focusdeck.ui.components.DayHeader
import dev.stanislav.focusdeck.ui.components.ScreenHeader
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))

private fun dayLabel(date: LocalDate): String =
    date.format(DATE_FORMAT).replaceFirstChar { it.uppercase() }

/**
 * Вкладка дневника — сегодняшняя запись из daily note, четыре поля шаблона.
 *
 * Прошлых дней здесь нет намеренно: каждая запись лежит в отдельном файле,
 * и список за месяц означал бы тридцать чтений через SAF на открытие вкладки.
 * Архив читается в самом Obsidian, а приложение отвечает за сегодня.
 */
@Composable
fun JournalScreen(
    viewModel: JournalViewModel,
    onWriteToday: (JournalField) -> Unit,
    stats: DayStats? = null,
    onOpenNutrition: (LocalDate) -> Unit = {},
) {
    val journal by viewModel.journal.collectAsState()
    val vaultState by viewModel.vaultState.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val daysWithNotes by viewModel.daysWithNotes.collectAsState()
    var daysExpanded by remember { mutableStateOf(false) }
    // Считаем по тому же значению, что и рисуем. Через viewModel.textOf() счётчик
    // брал .value потока напрямую и расходился с собранным состоянием.
    val fields = listOf(
        JournalField.MORNING to journal.morning,
        JournalField.DAY to journal.day,
        JournalField.EVENING to journal.evening,
        JournalField.CONCLUSIONS to journal.conclusions,
    )
    val filled = fields.count { it.second.isNotBlank() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DayHeader(
                    selected = selectedDate,
                    summary = when (vaultState) {
                        VaultState.NOT_CONNECTED -> "Vault не подключён"
                        VaultState.NOTE_MISSING -> "Заметки за этот день нет"
                        VaultState.ERROR -> "Заметку не удалось прочитать"
                        VaultState.READY -> "Заполнено $filled из 4"
                    },
                    daysWithNotes = daysWithNotes,
                    expanded = daysExpanded,
                    onToggle = {
                        daysExpanded = !daysExpanded
                        if (daysExpanded) viewModel.loadNoteDates()
                    },
                    onSelect = { day ->
                        daysExpanded = false
                        viewModel.selectDate(day)
                    },
                )
                if (stats != null && stats.date == selectedDate) DayStatsRow(stats) { onOpenNutrition(selectedDate) }
            }
        }

        if (vaultState == VaultState.READY) {
            items(fields, key = { it.first.name }) { (field, text) ->
                JournalFieldCard(
                    field = field,
                    text = text,
                    onClick = { onWriteToday(field) },
                )
            }
        }
    }
}

/**
 * The day in four numbers. One quiet row instead of four big tiles: the words stay the
 * main content of this tab, food and weight open the nutrition log for the same day.
 */
@Composable
private fun DayStatsRow(stats: DayStats, onOpenNutrition: () -> Unit) {
    val deck = dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors.current
    Row(
        Modifier.fillMaxWidth().clip(dev.stanislav.focusdeck.ui.theme.FocusRadius.card)
            .background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 4.dp),
    ) {
        StatCell(if (stats.calories > 0) "${stats.calories}" else "—", "ккал", Modifier.weight(1f), onOpenNutrition)
        StatCell(stats.weightKg?.let { "%.1f".format(it) } ?: "—", if (stats.weightToday || stats.weightKg == null) "кг" else "кг, ранее",
            Modifier.weight(1f), onOpenNutrition)
        StatCell(if (stats.focusMinutes > 0) hoursMinutes(stats.focusMinutes.toLong()) else "—", "фокус", Modifier.weight(1f),
            color = if (stats.focusMinutes > 0) MaterialTheme.colorScheme.primary else null)
        StatCell(stats.screenMinutes?.let(::hoursMinutes) ?: "—", "экран", Modifier.weight(1f),
            color = if ((stats.screenMinutes ?: 0) > 240) deck.attention else null)
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier, onClick: (() -> Unit)? = null,
    color: androidx.compose.ui.graphics.Color? = null) {
    Column(
        modifier.clip(dev.stanislav.focusdeck.ui.theme.FocusRadius.field)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun hoursMinutes(minutes: Long): String =
    if (minutes < 60) "$minutes м" else "${minutes / 60}ч ${"%02d".format(minutes % 60)}"

/**
 * Поле дневника карточкой.
 *
 * Раньше это были подпись и текст прямо на чёрном, без всякой рамки: на фоне
 * остального интерфейса, собранного из карточек, выглядело незаконченным.
 * Нажатие открывает именно это поле, а не то, которое подходит по времени суток.
 */
@Composable
private fun JournalFieldCard(field: JournalField, text: String, onClick: () -> Unit) {
    FocusCard(onClick = onClick) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (text.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (text.isBlank()) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (text.isNotBlank()) Text(
                text = text,
                maxLines = 4, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = if (text.isBlank()) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * The ViewModel owns the raw text and the captured document identity. Navigation
 * flushes pending work; composition and incoming note snapshots never replace typing.
 */
@Composable
fun JournalEntryScreen(
    viewModel: JournalViewModel,
    initialField: JournalField,
    onDone: () -> Unit,
) {
    val state by viewModel.editor.collectAsState()
    val comparison by viewModel.comparison.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val draft = state.draft
    val field = draft?.target?.field ?: initialField
    val text = draft?.text.orEmpty()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    var copied by remember(draft?.target, text) { mutableStateOf(false) }

    LaunchedEffect(viewModel, initialField) { viewModel.openEditor(initialField) }
    LaunchedEffect(comparison != null) { if (comparison != null) focusManager.clearFocus() }
    DisposableEffect(viewModel) { onDispose { viewModel.flushEditor() } }
    val leave = {
        viewModel.flushEditor()
        focusManager.clearFocus()
        onDone()
    }
    BackHandler(onBack = leave)

    var fieldsExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(FocusRadius.pill).clickable(onClick = leave), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", Modifier.size(20.dp))
            }
            Text((draft?.target?.date ?: selectedDate).format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))),
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton(onClick = { focusManager.clearFocus(); fieldsExpanded = true }, enabled = draft != null) {
                    Text(field.label, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Filled.ExpandMore, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = fieldsExpanded, onDismissRequest = { fieldsExpanded = false }) {
                    JournalField.entries.forEach { candidate ->
                        DropdownMenuItem(text = { Text(candidate.label) }, onClick = {
                            fieldsExpanded = false
                            viewModel.switchField(candidate)
                        })
                    }
                }
            }
        }
        if (state.error != null || state.conflicted) {
            Text(state.error ?: "В Obsidian другая версия записи", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.conflicted) TextButton(onClick = viewModel::openComparison, enabled = !state.saving && draft != null) { Text("Сравнить") }
                TextButton(onClick = viewModel::retryEditor, enabled = !state.saving && !state.loading && draft != null) { Text("Повторить") }
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, enabled = text.isNotEmpty()) {
                    Text(if (copied) "Скопировано" else "Копировать")
                }
            }
        }
        BasicTextField(
            value = text, onValueChange = { value -> draft?.target?.let { viewModel.save(it, value) } },
            enabled = state.ready && comparison == null, singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp).focusRequester(focusRequester)
                .semantics { contentDescription = field.label },
        )
        LaunchedEffect(draft?.target, state.ready, comparison != null) {
            if (state.ready && comparison == null) focusRequester.requestFocus()
        }
        Text(when {
            state.loading -> "Открываю…"
            state.error != null -> if (state.draftSaved) "Черновик на устройстве" else "Не сохранено"
            state.saving || state.draftSaving -> "Сохраняю…"
            state.saved && !state.dirty -> "Сохранено в Obsidian"
            state.draftSaved -> "Черновик сохранён"
            else -> "Черновик"
        }, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    comparison?.let { shown ->
        JournalComparisonSheet(shown, viewModel::dismissComparison, viewModel::openComparison, viewModel::resolveComparison)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JournalComparisonSheet(
    state: JournalComparisonState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onChoose: (Boolean) -> Unit,
) {
    val busy by rememberUpdatedState(state.busy)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !busy }),
        shape = FocusRadius.card,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Какую версию оставить?", modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                Text("${state.local.target.field.label} · ${dayLabel(state.local.target.date)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                JournalVersionCard("Мой текст", state.local.text)
                if (state.loading) Text("Читаю текст из Obsidian…", style = MaterialTheme.typography.bodyMedium)
                else state.remote?.let { JournalVersionCard("Из Obsidian", it) }
                Text("«Мой текст» заменит запись в Obsidian. «Из Obsidian» заменит черновик. Перед выбором сохраним обе версии на устройстве.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.error?.let { error ->
                    Text(error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    FocusButton(text = "Обновить", style = FocusButtonStyle.Tonal, enabled = !state.busy, onClick = onRefresh)
                }
                if (state.busy) Text("Сохраняю выбранную версию…", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            val canChoose = !state.loading && !state.busy && state.remote != null && state.error == null
            FocusButton(text = "Мой текст", enabled = canChoose, onClick = { onChoose(true) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Сохранить мой текст в Obsidian" })
            FocusButton(text = "Из Obsidian", enabled = canChoose, style = FocusButtonStyle.Tonal, onClick = { onChoose(false) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Заменить черновик текстом из Obsidian" })
            FocusButton(text = "Решить позже", enabled = !state.busy, style = FocusButtonStyle.Tonal,
                onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun JournalVersionCard(title: String, text: String) {
    FocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            SelectionContainer {
                Text(if (text.isEmpty()) "Пустая запись" else text, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

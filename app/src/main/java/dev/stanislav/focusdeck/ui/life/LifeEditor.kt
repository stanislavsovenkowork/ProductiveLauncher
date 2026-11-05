package dev.stanislav.focusdeck.ui.life

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.data.life.fieldsFor
import dev.stanislav.focusdeck.data.life.meaningfulLifeText
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.LocalDate

internal fun missingLifeCompletionField(entry: LifeEntry): LifeField? {
    if (entry.status != LifeStatus.DONE) return null
    val required = when (entry.kind) {
        LifeKind.GOAL -> LifeField.EVIDENCE
        LifeKind.DECISION -> LifeField.OUTCOME
        LifeKind.REVIEW -> LifeField.CHANGE
        else -> return null
    }
    return required.takeUnless { meaningfulLifeText(entry.text(it)) }
}

private fun primaryFields(kind: LifeKind): List<LifeField> = when (kind) {
    LifeKind.AREA -> listOf(LifeField.STANDARD, LifeField.WHY)
    LifeKind.GOAL -> listOf(LifeField.WHY, LifeField.SUCCESS, LifeField.MILESTONES, LifeField.OBSTACLE, LifeField.IF_THEN)
    LifeKind.PROJECT -> listOf(LifeField.SUCCESS, LifeField.NEXT_ACTION, LifeField.MILESTONES, LifeField.OBSTACLE, LifeField.IF_THEN)
    LifeKind.DECISION -> listOf(LifeField.OPTIONS, LifeField.CRITERIA, LifeField.CHOICE, LifeField.PREDICTION)
    LifeKind.REVIEW -> listOf(LifeField.WINS, LifeField.FRICTION, LifeField.CHANGE, LifeField.COMMITMENT)
}

private fun fieldLabel(field: LifeField): String = when (field) {
    LifeField.SUCCESS -> "Готово, когда"
    LifeField.NEXT_ACTION -> "Следующий шаг"
    LifeField.MILESTONES -> "Этапы · строка = этап"
    LifeField.OBSTACLE -> "Что может помешать"
    LifeField.IF_THEN -> "Если — то"
    LifeField.WHY -> "Почему это важно"
    else -> field.heading
}

/** Full edit: every field is a quiet block; rarely used ones wait behind a chip. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LifeEditor(state: LifeUiState, viewModel: LifeViewModel, mode: String, onDiscard: () -> Unit) {
    val draft = state.draft ?: return
    val tone = kindTone(draft.kind)
    val enabled = !state.busy && state.snapshot.connected
    val validTitle = draft.title.any { it.isLetterOrDigit() }
    val activating = mode == "activate"
    val problems = draft.copy(status = LifeStatus.ACTIVE).activationProblems()
    val primary = primaryFields(draft.kind)
    val secondary = fieldsFor(draft.kind).filter { it !in primary && it != LifeField.REVIEW_NOTE }
    var revealed by rememberSaveable(draft.id) { mutableStateOf(secondary.filter { draft.text(it).isNotBlank() }.map { it.name }) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "title") {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    if (draft.title.isEmpty()) Text("Название", style = LifeType.hero, color = MaterialTheme.colorScheme.outline)
                    BasicTextField(draft.title, { value -> viewModel.updateDraft { it.copy(title = value.replace('\n', ' ')) } },
                        textStyle = LifeType.hero, cursorBrush = SolidColor(tone.accent), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                }
            }
            if (activating && problems.isNotEmpty()) item(key = "problems") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val deck = LocalFocusDeckColors.current
                    problems.forEach {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = deck.onAttentionContainer,
                            modifier = Modifier.clip(CircleShape).background(deck.attentionContainer).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
            }
            items(primary + secondary.filter { it.name in revealed }, key = { it.name }) { field ->
                FieldBlock(fieldLabel(field), draft.text(field), { viewModel.updateField(field, it) }, tone.accent,
                    singleLine = field == LifeField.NEXT_ACTION, modifier = Modifier.animateItem())
            }
            val hidden = secondary.filter { it.name !in revealed }
            if (hidden.isNotEmpty()) item(key = "more") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    hidden.forEach { field -> Pill(fieldLabel(field), { revealed = revealed + field.name }, icon = Icons.Rounded.Add) }
                }
            }
            if (draft.kind != LifeKind.REVIEW) item(key = "links") { LinksBlock(draft, state, enabled, viewModel) }
        }
        val deck = LocalFocusDeckColors.current
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).pressable(onDiscard, !state.busy),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Отменить правку", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (activating) Pill("Без запуска", { viewModel.saveDraft() }, enabled = enabled && validTitle)
            val canSave = enabled && validTitle && (if (activating) problems.isEmpty() else missingLifeCompletionField(draft) == null)
            Row(
                Modifier.weight(1f).height(52.dp).clip(CircleShape).background(if (canSave) tone.accent else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .pressable({ if (activating) viewModel.saveDraft(LifeStatus.ACTIVE) else viewModel.saveDraft() }, canSave),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (canSave) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(Icons.Rounded.Check, null, tint = fg, modifier = Modifier.size(18.dp))
                Text(when {
                    activating && draft.kind == LifeKind.DECISION -> "Принять решение"
                    activating -> "Взять в работу"
                    else -> "Сохранить"
                }, style = MaterialTheme.typography.titleSmall, color = fg, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (!state.draftSaved) Text("Черновик не сохранён на устройстве — скопируй текст", style = MaterialTheme.typography.labelSmall,
            color = deck.attention, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun FieldBlock(label: String, value: String, onValue: (String) -> Unit, accent: androidx.compose.ui.graphics.Color, singleLine: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label.uppercase(), style = LifeType.section, color = accent.copy(alpha = 0.9f))
        BasicTextField(value, { onValue(if (singleLine) it.replace('\n', ' ') else it) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(accent), modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
    }
}

/** Links and dates as chips; a tap opens a short list or a date picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinksBlock(draft: LifeEntry, state: LifeUiState, enabled: Boolean, viewModel: LifeViewModel) {
    val context = LocalContext.current
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = state.snapshot.entries
    fun date(initial: LocalDate?, onPick: (LocalDate) -> Unit) {
        val start = initial ?: LocalDate.now().plusDays(7)
        DatePickerDialog(context, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        SectionTitle("Связи и даты")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.kind != LifeKind.AREA) LinkChip(LifeKind.AREA, entries.firstOrNull { it.id == draft.areaId }?.title ?: "Сфера", draft.areaId != null, enabled) { picker = "AREA" }
            if (draft.kind == LifeKind.PROJECT) LinkChip(LifeKind.GOAL, entries.firstOrNull { it.id == draft.goalId }?.title ?: "Цель", draft.goalId != null, enabled) { picker = "GOAL" }
            if (draft.kind == LifeKind.DECISION) LinkChip(LifeKind.PROJECT, entries.firstOrNull { it.id == draft.projectId }?.title ?: "Проект", draft.projectId != null, enabled) { picker = "PROJECT" }
            if (draft.kind in listOf(LifeKind.GOAL, LifeKind.PROJECT)) {
                Pill(draft.dueDate?.let { "Срок · ${relativeDay(it)}" } ?: "Срок", { date(draft.dueDate) { d -> viewModel.updateDraft { it.copy(dueDate = d) } } },
                    icon = Icons.Rounded.CalendarMonth, enabled = enabled)
                if (draft.dueDate != null) Pill("Без срока", { viewModel.updateDraft { it.copy(dueDate = null) } }, enabled = enabled)
            }
            Pill(draft.reviewDate?.let { (if (draft.kind == LifeKind.DECISION) "Проверка · " else "Сверка · ") + relativeDay(it) }
                ?: if (draft.kind == LifeKind.DECISION) "Дата проверки" else "Напомнить свериться",
                { date(draft.reviewDate) { d -> viewModel.updateDraft { it.copy(reviewDate = d) } } }, icon = Icons.Rounded.EventRepeat, enabled = enabled)
            if (draft.reviewDate != null && draft.kind != LifeKind.DECISION) Pill("Без напоминания", { viewModel.updateDraft { it.copy(reviewDate = null) } }, enabled = enabled)
        }
    }
    picker?.let { kindName ->
        val kind = LifeKind.valueOf(kindName)
        EntityPickerSheet(kind, entries, when (kind) {
            LifeKind.AREA -> draft.areaId
            LifeKind.GOAL -> draft.goalId
            else -> draft.projectId
        }, onDismiss = { picker = null }) { chosen ->
            picker = null
            viewModel.updateDraft { current ->
                when (kind) {
                    LifeKind.AREA -> {
                        val goal = entries.firstOrNull { it.id == current.goalId }
                        val project = entries.firstOrNull { it.id == current.projectId }
                        current.copy(areaId = chosen?.id, goalId = current.goalId.takeIf { goal?.areaId == chosen?.id },
                            projectId = current.projectId.takeIf { project?.areaId == chosen?.id })
                    }
                    LifeKind.GOAL -> current.copy(goalId = chosen?.id, areaId = chosen?.areaId ?: current.areaId)
                    else -> current.copy(projectId = chosen?.id, goalId = chosen?.goalId, areaId = chosen?.areaId ?: current.areaId)
                }
            }
        }
    }
}

@Composable
private fun LinkChip(kind: LifeKind, text: String, set: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tone = kindTone(kind)
    Pill(text, onClick, icon = tone.icon, enabled = enabled,
        container = if (set) tone.container else MaterialTheme.colorScheme.surfaceContainerHighest,
        content = if (set) tone.onContainer else MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntityPickerSheet(kind: LifeKind, entries: List<LifeEntry>, selectedId: String?, onDismiss: () -> Unit, onPick: (LifeEntry?) -> Unit) {
    val choices = entries.filter { it.kind == kind && (!it.archived || it.id == selectedId) }.sortedBy { it.title.lowercase() }
    val tone = kindTone(kind)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.padding(bottom = 24.dp).navigationBarsPadding().heightIn(max = 520.dp)) {
            item {
                Row(Modifier.fillMaxWidth().clickable { onPick(null) }.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    Text("Без связи", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(choices, key = { it.id }) { entry ->
                Row(Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(tone.icon, null, tint = tone.accent, modifier = Modifier.size(18.dp))
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.id == selectedId) Icon(Icons.Rounded.Check, "Выбрано", tint = tone.accent)
                }
            }
            if (choices.isEmpty()) item { Text("Пока нет записей", style = LifeType.muted, modifier = Modifier.padding(20.dp)) }
        }
    }
}

package dev.stanislav.focusdeck.ui.life

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stanislav.focusdeck.data.life.Calibration
import dev.stanislav.focusdeck.data.life.GoalMotive
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifePace
import dev.stanislav.focusdeck.data.life.Metrics
import dev.stanislav.focusdeck.data.life.PaceForecast
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeMomentum
import dev.stanislav.focusdeck.data.life.LifeProgress
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.data.life.Milestone
import dev.stanislav.focusdeck.data.life.Milestones
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class DetailActions(
    val recordMetric: (Double) -> Unit,
    val open: (LifeEntry) -> Unit,
    val edit: () -> Unit,
    val activate: () -> Unit,
    val review: () -> Unit,
    val complete: () -> Unit,
    val status: (LifeStatus) -> Unit,
    val postpone: (LocalDate) -> Unit,
    val createChild: (LifeKind) -> Unit,
    val planNext: (LocalDate) -> Unit,
    val planSheet: () -> Unit,
    val setNext: (String, LocalDate?) -> Unit,
    val toggleMilestone: (Milestone, Boolean) -> Unit,
    val addMilestone: (String) -> Unit,
    val removeMilestone: (Milestone) -> Unit,
    val openNote: () -> Unit,
    val openPlan: (LocalDate) -> Unit,
    val weekFacts: (LocalDate, LocalDate) -> Unit,
)

@Composable
internal fun LifeDetail(state: LifeUiState, entry: LifeEntry, actions: DetailActions) {
    val enabled = !state.busy && state.snapshot.connected
    val tone = kindTone(entry.kind)
    val progress = state.progressOf(entry)
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") { Hero(entry, state, progress, actions) }
        if (entry.needsReview && !entry.archived) item(key = "due") { ReviewDueCard(entry, enabled, actions) }
        if (entry.status == LifeStatus.DRAFT && entry.kind != LifeKind.REVIEW && !entry.archived) item(key = "activate") {
            ActivateCard(entry, enabled, actions.activate)
        }
        when (entry.kind) {
            LifeKind.PROJECT -> {
                if (!entry.archived && entry.status != LifeStatus.DONE) item(key = "next") { NextStepCard(entry, state, enabled, actions) }
                item(key = "milestones") { MilestonesCard(entry, enabled && !entry.archived, actions) }
                if (progress?.fraction == 1f && entry.status == LifeStatus.ACTIVE) item(key = "finish") { FinishCard(entry, enabled, actions.complete) }
                item(key = "activity") { ActivityCard(entry, state) }
                item(key = "clarity") { ClarityCard(entry, actions.edit) }
                val decisions = state.snapshot.entries.filter { it.kind == LifeKind.DECISION && it.projectId == entry.id && !it.archived }
                if (decisions.isNotEmpty()) item(key = "decisions") { ChildrenBlock("Решения", decisions, state, actions, LifeKind.DECISION, enabled && !entry.archived) }
            }
            LifeKind.GOAL -> {
                GoalMotive.strip(entry.text(LifeField.WHY)).takeIf { it.isNotBlank() }?.let { item(key = "why") {
                    QuoteCard("Почему это важно" + (GoalMotive.of(entry.text(LifeField.WHY))?.let { m -> " · ${m.label}" } ?: ""), it, tone.accent)
                } }
                if (entry.text(LifeField.METRIC).isNotBlank() || (!entry.archived && entry.status != LifeStatus.DONE)) item(key = "metric") {
                    MetricCard(entry, enabled && !entry.archived, actions)
                }
                entry.text(LifeField.SUCCESS).takeIf { it.isNotBlank() }?.let { item(key = "success") { FactCard(Icons.Rounded.Flag, "Достигнута, когда", it, tone.accent) } }
                val projects = state.snapshot.entries.filter { it.kind == LifeKind.PROJECT && it.goalId == entry.id && !it.archived }
                item(key = "projects") { ChildrenBlock("Проекты", projects, state, actions, LifeKind.PROJECT, enabled && !entry.archived) }
                item(key = "milestones") { MilestonesCard(entry, enabled && !entry.archived, actions, optional = true) }
                if (entry.status == LifeStatus.ACTIVE && (progress?.fraction == 1f)) item(key = "finish") { FinishCard(entry, enabled, actions.complete) }
                item(key = "activity") { ActivityCard(entry, state) }
                item(key = "clarity") { ClarityCard(entry, actions.edit) }
                entry.text(LifeField.EVIDENCE).takeIf { it.isNotBlank() }?.let { item(key = "evidence") { FactCard(Icons.Rounded.CheckCircle, "Признаки прогресса", it, tone.accent) } }
            }
            LifeKind.AREA -> {
                entry.text(LifeField.STANDARD).takeIf { it.isNotBlank() }?.let { item(key = "standard") { QuoteCard("Достаточно — это", it, tone.accent) } }
                item(key = "activity") { ActivityCard(entry, state) }
                val goals = state.snapshot.entries.filter { it.kind == LifeKind.GOAL && it.areaId == entry.id && !it.archived }
                val projects = state.snapshot.entries.filter { it.kind == LifeKind.PROJECT && it.areaId == entry.id && it.goalId == null && !it.archived }
                item(key = "goals") { ChildrenBlock("Цели", goals, state, actions, LifeKind.GOAL, enabled) }
                item(key = "projects") { ChildrenBlock("Проекты без цели", projects, state, actions, LifeKind.PROJECT, enabled) }
                entry.text(LifeField.WHY).takeIf { it.isNotBlank() }?.let { item(key = "why") { FactCard(null, "Почему важно", it, tone.accent) } }
            }
            LifeKind.DECISION -> decisionItems(entry, enabled, actions)
            LifeKind.REVIEW -> reviewItems(entry, state, enabled, actions)
        }
        if (entry.text(LifeField.REVIEW_HISTORY).isNotBlank()) item(key = "rh") { HistoryList("Сверки", entry.text(LifeField.REVIEW_HISTORY)) }
        if (entry.text(LifeField.HISTORY).isNotBlank()) item(key = "h") { HistoryList("История решения", entry.text(LifeField.HISTORY)) }
        item(key = "footer") { DetailFooter(entry, enabled, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(entry: LifeEntry, state: LifeUiState, progress: LifeProgress?, actions: DetailActions) {
    val tone = kindTone(entry.kind)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KindBadge(entry)
            Text(entry.title, style = LifeType.hero)
            val parents = listOfNotNull(entry.goalId, entry.projectId, entry.areaId).distinct().mapNotNull { state.snapshot.entry(it) }
            if (parents.isNotEmpty() || entry.dueDate != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                parents.forEach { parent ->
                    val parentTone = kindTone(parent.kind)
                    Row(
                        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { actions.open(parent) }
                            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(parentTone.icon, null, tint = parentTone.accent, modifier = Modifier.size(14.dp))
                        Text(parent.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp))
                    }
                }
                if (entry.status != LifeStatus.DONE) entry.dueDate?.let { DueChip(it) }
            }
        }
        if (entry.kind in listOf(LifeKind.PROJECT, LifeKind.GOAL)) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ProgressRing(progress?.fraction, tone.accent, size = 84.dp, stroke = 8.dp) {
                if (progress?.unit == LifeProgress.ProgressUnit.FINISHED) Icon(Icons.Rounded.EmojiEvents, null, tint = tone.accent, modifier = Modifier.size(34.dp))
                else PercentLabel(progress?.fraction, MaterialTheme.colorScheme.onSurface, LifeType.number.copy(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            }
            progress?.framing()?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReviewDueCard(entry: LifeEntry, enabled: Boolean, actions: DetailActions) {
    val deck = LocalFocusDeckColors.current
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(deck.attentionContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.EventRepeat, null, tint = deck.attention)
            Text(if (entry.kind == LifeKind.DECISION) "Пора проверить ожидание" else "Пора свериться", style = LifeType.title, color = deck.onAttentionContainer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (entry.kind == LifeKind.DECISION) "Проверить" else "Свериться", actions.review, enabled = enabled,
                container = deck.attention, content = deck.attentionContainer, icon = Icons.AutoMirrored.Rounded.ArrowForward)
            Pill("Через неделю", { actions.postpone(LocalDate.now().plusDays(7)) }, enabled = enabled,
                container = deck.onAttentionContainer.copy(alpha = 0.14f), content = deck.onAttentionContainer)
            Pill("Дата", {
                val tomorrow = LocalDate.now().plusDays(1)
                DatePickerDialog(context, { _, y, m, d -> actions.postpone(LocalDate.of(y, m + 1, d)) }, tomorrow.year, tomorrow.monthValue - 1, tomorrow.dayOfMonth).apply {
                    datePicker.minDate = tomorrow.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.show()
            }, enabled = enabled, container = deck.onAttentionContainer.copy(alpha = 0.14f), content = deck.onAttentionContainer)
        }
    }
}

@Composable
private fun ActivateCard(entry: LifeEntry, enabled: Boolean, onActivate: () -> Unit) {
    val tone = kindTone(entry.kind)
    val problems = entry.activationProblems()
    LifeCard(tint = tone.container, onClick = if (enabled) onActivate else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when (entry.kind) {
                    LifeKind.DECISION -> "Принять решение"
                    LifeKind.AREA -> "Поддерживать сферу"
                    else -> "Взять в работу"
                }, style = LifeType.title)
                Text(if (problems.isEmpty()) "Всё готово к старту" else "Осталось: ${problems.size}", style = LifeType.muted)
            }
            Icon(Icons.Rounded.PlayArrow, null, tint = tone.accent)
        }
    }
}

/**
 * The next action is the hinge between intention and the plan. Its state is read from the
 * daily notes: planned, done, or not yet anywhere. When it is done, the card asks for the next one.
 */
@Composable
private fun NextStepCard(entry: LifeEntry, state: LifeUiState, enabled: Boolean, actions: DetailActions) {
    val tone = kindTone(LifeKind.PROJECT)
    val next = entry.text(LifeField.NEXT_ACTION).trim()
    val planned = state.plannedNextStep(entry)
    var editing by rememberSaveable(entry.id, next) { mutableStateOf(next.isEmpty() || planned?.done == true) }
    var text by rememberSaveable(entry.id, next, editing) { mutableStateOf(if (planned?.done == true) "" else next) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(tone.container, tone.container.copy(alpha = 0.55f))))
            .padding(16.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (planned?.done == true) "ШАГ СДЕЛАН · ЧТО ДАЛЬШЕ?" else "СЛЕДУЮЩИЙ ШАГ", style = LifeType.section, color = tone.onContainer.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f))
            if (!editing && next.isNotEmpty()) Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled) { editing = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Edit, "Изменить шаг", tint = tone.onContainer, modifier = Modifier.size(16.dp))
            }
        }
        if (planned?.done == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckOrb(true, tone.accent, null, size = 20.dp)
            Text(next, style = MaterialTheme.typography.bodyMedium, color = tone.onContainer.copy(alpha = 0.7f),
                textDecoration = TextDecoration.LineThrough, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedContent(editing, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "nextEdit") { isEditing ->
            if (isEditing) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    if (text.isEmpty()) Text("Одно действие, которое можно начать", style = LifeType.input, color = tone.onContainer.copy(alpha = 0.45f))
                    BasicTextField(text, { text = it.replace('\n', ' ') }, textStyle = LifeType.input.copy(color = tone.onContainer),
                        cursorBrush = SolidColor(tone.accent), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { actions.setNext(text, null); editing = false } }))
                }
                val valid = text.count { it.isLetterOrDigit() } >= 3
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("На сегодня", { actions.setNext(text, LocalDate.now()); editing = false }, icon = Icons.Rounded.Today, enabled = enabled && valid,
                        container = tone.accent, content = tone.container)
                    Pill("Завтра", { actions.setNext(text, LocalDate.now().plusDays(1)); editing = false }, enabled = enabled && valid,
                        container = tone.onContainer.copy(alpha = 0.14f), content = tone.onContainer)
                    Pill("Сохранить", { actions.setNext(text, null); editing = false }, enabled = enabled && valid,
                        container = tone.onContainer.copy(alpha = 0.14f), content = tone.onContainer)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(next, style = LifeType.input.copy(color = tone.onContainer))
                IfThenLine(entry, tone.onContainer)
                when {
                    planned != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Today, null, tint = tone.accent, modifier = Modifier.size(18.dp))
                        Text("в плане · ${relativeDay(planned.date)}" + (planned.startMinute?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: " · без времени"),
                            style = MaterialTheme.typography.labelLarge, color = tone.onContainer, modifier = Modifier.weight(1f))
                        Pill("План", { actions.openPlan(planned.date) }, container = tone.onContainer.copy(alpha = 0.14f), content = tone.onContainer)
                    }
                    else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("На сегодня", { actions.planNext(LocalDate.now()) }, icon = Icons.Rounded.Today, enabled = enabled,
                            container = tone.accent, content = tone.container)
                        Pill("Завтра", { actions.planNext(LocalDate.now().plusDays(1)) }, enabled = enabled,
                            container = tone.onContainer.copy(alpha = 0.14f), content = tone.onContainer)
                        Pill("Другое…", actions.planSheet, enabled = enabled,
                            container = tone.onContainer.copy(alpha = 0.14f), content = tone.onContainer)
                    }
                }
            }
        }
    }
}

/** Milestones are the only source of the percentage. No milestones → no invented percent. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MilestonesCard(entry: LifeEntry, enabled: Boolean, actions: DetailActions, optional: Boolean = false) {
    val tone = kindTone(entry.kind)
    val milestones = Milestones.parse(entry.text(LifeField.MILESTONES))
    var adding by rememberSaveable(entry.id) { mutableStateOf("") }
    var removing by remember { mutableStateOf<Milestone?>(null) }
    var open by rememberSaveable(entry.id) { mutableStateOf(milestones.isNotEmpty() || !optional) }
    if (!open) {
        Pill(if (entry.kind == LifeKind.GOAL) "Вехи цели" else "Этапы", { open = true }, icon = Icons.Rounded.Add, enabled = enabled)
        return
    }
    LifeCard(padding = 0.dp) {
        Column(Modifier.animateContentSize()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (entry.kind == LifeKind.GOAL) "ВЕХИ" else "ЭТАПЫ", style = LifeType.section, modifier = Modifier.weight(1f))
                if (milestones.isNotEmpty()) Text("${milestones.count { it.done }}/${milestones.size}", style = LifeType.number, color = tone.accent)
            }
            if (milestones.isNotEmpty()) SegmentedBar(milestones.count { it.done }, milestones.size, tone.accent, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            LifePace.milestones(entry)?.let { PaceLine(it, "этапа", Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
            milestones.forEach { milestone ->
                key(milestone.line, milestone.title) {
                    val fade by animateFloatAsState(if (milestone.done) 0.55f else 1f, tween(300), label = "ms")
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(enabled = enabled,
                            onClick = { actions.toggleMilestone(milestone, !milestone.done) },
                            onLongClick = { removing = milestone })
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CheckOrb(milestone.done, tone.accent, if (enabled) ({ actions.toggleMilestone(milestone, !milestone.done) }) else null, size = 24.dp)
                        Text(milestone.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).alpha(fade),
                            textDecoration = if (milestone.done) TextDecoration.LineThrough else null)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (adding.isEmpty()) Text(if (milestones.isEmpty()) "Разбей на 3–5 этапов" else "Ещё этап", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline)
                    BasicTextField(adding, { adding = it.replace('\n', ' ') }, enabled = enabled,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(tone.accent), modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (adding.isNotBlank()) { actions.addMilestone(adding); adding = "" } }))
                }
                if (adding.any(Char::isLetterOrDigit)) Box(Modifier.size(36.dp).clip(CircleShape).background(tone.accent)
                    .clickable { actions.addMilestone(adding); adding = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, "Добавить этап", tint = tone.container, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    removing?.let { milestone ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Убрать этап?") },
            text = { Text(milestone.title) },
            confirmButton = { TextButton(onClick = { actions.removeMilestone(milestone); removing = null }) { Text("Убрать") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Оставить") } },
        )
    }
}

@Composable
private fun FinishCard(entry: LifeEntry, enabled: Boolean, onFinish: () -> Unit) {
    val deck = LocalFocusDeckColors.current
    LifeCard(tint = deck.attentionContainer, onClick = if (enabled) onFinish else null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.EmojiEvents, null, tint = deck.attention, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(if (entry.kind == LifeKind.GOAL) "Цель достигнута?" else "Завершить проект", style = LifeType.title)
                Text(if (entry.kind == LifeKind.GOAL) "Отметь подтверждающий факт" else "Все этапы пройдены", style = LifeType.muted)
            }
        }
    }
}

/** Four weeks of real completed steps linked to this entry. */
@Composable
private fun ActivityCard(entry: LifeEntry, state: LifeUiState) {
    val tone = kindTone(entry.kind)
    val steps = state.stepsOf(entry)
    val done = steps.filter { it.done }.sortedByDescending { it.date }
    val today = LocalDate.now()
    LifeCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ШАГИ ЗА 4 НЕДЕЛИ", style = LifeType.section, modifier = Modifier.weight(1f))
                AnimatedCount(done.size, LifeType.number, tone.accent)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cell = ((maxWidth - 3.dp * 27) / 28).coerceIn(6.dp, 14.dp)
                HeatStrip(LifeMomentum.doneByDay(steps), tone.accent, days = 28, cell = cell, today = today)
            }
            if (done.isEmpty()) Text(if (state.activityLoading) "Читаю дневные заметки…" else "Шаги появятся, когда задачи с этой связью будут отмечены в плане",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            done.take(4).forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = tone.accent.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                    Text(step.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeDay(step.date, today), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if ((state.activity?.failedDays ?: 0) > 0) Text("Не прочитано дневных заметок: ${state.activity?.failedDays}",
                style = MaterialTheme.typography.labelSmall, color = LocalFocusDeckColors.current.attention)
        }
    }
}

/**
 * Mental contrasting in one card: the finish line, the likely obstacle, the if–then plan.
 * Empty parts are single-tap invitations, not red validation errors.
 */
@Composable
private fun ClarityCard(entry: LifeEntry, onEdit: () -> Unit) {
    val tone = kindTone(entry.kind)
    val success = entry.text(LifeField.SUCCESS).trim()
    val obstacle = entry.text(LifeField.OBSTACLE).trim()
    val ifThen = entry.text(LifeField.IF_THEN).trim()
    var open by rememberSaveable(entry.id) { mutableStateOf(obstacle.isEmpty() || ifThen.isEmpty()) }
    LifeCard(padding = 0.dp) {
        Column(Modifier.animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Shield, null, tint = tone.accent, modifier = Modifier.size(18.dp))
                Text("План на трудный момент", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                val filled = listOf(obstacle, ifThen).count { it.isNotEmpty() }
                if (!open) Text("$filled/2", style = LifeType.number, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entry.kind == LifeKind.PROJECT) ClarityRow("Готово, когда", success, tone.accent, onEdit)
                ClarityRow("Что может помешать", obstacle, tone.accent, onEdit)
                ClarityRow("Если это случится, то я…", ifThen, tone.accent, onEdit)
            }
        }
    }
}

@Composable
private fun ClarityRow(label: String, value: String, accent: Color, onEdit: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onEdit).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
        if (value.isEmpty()) Text("Добавить", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        else Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QuoteCard(label: String, text: String, accent: Color) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(accent))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label.uppercase(Locale.forLanguageTag("ru")), style = LifeType.section)
            Text(text, style = LifeType.quote)
        }
    }
}

@Composable
private fun FactCard(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, text: String, accent: Color) {
    LifeCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { Icon(it, null, tint = accent, modifier = Modifier.size(16.dp)) }
                Text(label.uppercase(Locale.forLanguageTag("ru")), style = LifeType.section)
            }
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ChildrenBlock(label: String, children: List<LifeEntry>, state: LifeUiState, actions: DetailActions, kind: LifeKind, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(if (children.isEmpty()) label else "$label · ${children.size}") {
            if (enabled) Pill(kindShort(kind), { actions.createChild(kind) }, icon = Icons.Rounded.Add)
        }
        children.sortedWith(compareBy<LifeEntry> { it.status != LifeStatus.ACTIVE }.thenBy { it.title.lowercase() }).forEach { child ->
            if (child.kind == LifeKind.PROJECT && child.status == LifeStatus.ACTIVE) ProjectCard(child, state, onClick = { actions.open(child) }, onPlan = null)
            else LifeCard(padding = 0.dp) { EntryRow(child, onClick = { actions.open(child) }, trailing = childTrailing(child, state)) }
        }
    }
}

private fun childTrailing(entry: LifeEntry, state: LifeUiState): String = state.progressOf(entry)
    ?.let { "${(it.fraction * 100).toInt()}% · ${entry.statusLabel.lowercase()}" } ?: entry.statusLabel.lowercase()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailFooter(entry: LifeEntry, enabled: Boolean, actions: DetailActions) {
    var confirmArchive by remember { mutableStateOf(false) }
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (entry.kind != LifeKind.REVIEW && !entry.archived) Pill("Изменить", actions.edit, icon = Icons.Rounded.Edit, enabled = enabled)
        if (entry.kind == LifeKind.PROJECT && !entry.archived) Pill("Решение", { actions.createChild(LifeKind.DECISION) }, icon = Icons.Rounded.Add, enabled = enabled)
        if (entry.kind in listOf(LifeKind.PROJECT, LifeKind.GOAL, LifeKind.AREA) && entry.status in listOf(LifeStatus.ACTIVE, LifeStatus.PAUSED) && !entry.needsReview)
            Pill("Свериться", actions.review, icon = Icons.Rounded.EventRepeat, enabled = enabled)
        if (entry.status == LifeStatus.ACTIVE && entry.kind !in listOf(LifeKind.REVIEW, LifeKind.DECISION))
            Pill("Пауза", { actions.status(LifeStatus.PAUSED) }, icon = Icons.Rounded.Pause, enabled = enabled)
        if (entry.status == LifeStatus.PAUSED) Pill("Возобновить", actions.activate, icon = Icons.Rounded.PlayArrow, enabled = enabled)
        if (entry.status == LifeStatus.ACTIVE && entry.kind in listOf(LifeKind.PROJECT, LifeKind.GOAL))
            Pill(if (entry.kind == LifeKind.GOAL) "Достигнута" else "Завершить", actions.complete, icon = Icons.Rounded.EmojiEvents, enabled = enabled)
        if (entry.status == LifeStatus.DONE && entry.kind != LifeKind.REVIEW) Pill("Вернуть в работу", { actions.status(LifeStatus.DRAFT) }, enabled = enabled)
        if (!entry.archived) Pill(if (entry.kind == LifeKind.GOAL) "Больше не планирую" else "В архив", { confirmArchive = true }, icon = Icons.Rounded.Archive, enabled = enabled)
        else Pill("Из архива", { actions.status(LifeStatus.DRAFT) }, icon = Icons.Rounded.Unarchive, enabled = enabled)
        Pill("Obsidian", actions.openNote, icon = Icons.AutoMirrored.Rounded.OpenInNew)
    }
    if (confirmArchive) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text(if (entry.kind == LifeKind.GOAL) "Больше не планировать?" else "Убрать в архив?") },
        text = { Text(if (entry.kind == LifeKind.GOAL) "Цель уйдёт в архив. Это не отметка о достижении." else "Заметка останется в Obsidian.") },
        confirmButton = { TextButton(onClick = { actions.status(LifeStatus.ARCHIVED); confirmArchive = false }) { Text("В архив") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.decisionItems(entry: LifeEntry, enabled: Boolean, actions: DetailActions) {
    item(key = "dstate") {
        val decided = entry.decidedOn
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            decided?.let { InfoChip("принято ${relativeDay(it)}") }
            if (entry.status == LifeStatus.ACTIVE) entry.reviewDate?.let { InfoChip(if (it.isAfter(LocalDate.now())) "проверка ${relativeDay(it)}" else "пора проверить") }
            entry.reviewedOn?.takeIf { entry.status == LifeStatus.DONE }?.let { InfoChip("проверено ${relativeDay(it)}") }
        }
    }
    listOf(LifeField.OPTIONS to "Варианты", LifeField.CRITERIA to "Критерии", LifeField.CHOICE to "Выбор и основания", LifeField.COST to "Цена выбора")
        .filter { entry.text(it.first).isNotBlank() }
        .forEach { (field, label) -> item(key = field.name) { FactCard(null, label, entry.text(field), kindTone(LifeKind.DECISION).accent) } }
    Calibration.stripConfidence(entry.text(LifeField.PREDICTION)).takeIf { it.isNotBlank() }?.let {
        val confidence = Calibration.confidence(entry.text(LifeField.PREDICTION))
        item(key = "prediction") { QuoteCard((if (entry.decidedOn != null) "Ожидание от ${relativeDay(entry.decidedOn)}" else "Ожидание") +
            (confidence?.let { c -> " · уверенность $c%" } ?: ""), it, kindTone(LifeKind.DECISION).accent) }
    }
    if (entry.status == LifeStatus.ACTIVE && !entry.needsReview) item(key = "check") {
        Pill("Проверить результат сейчас", actions.review, icon = Icons.Rounded.EventRepeat, enabled = enabled)
    }
    Calibration.verdict(entry.text(LifeField.OUTCOME))?.let { verdict -> item(key = "verdict") { InfoChip("ожидание сбылось: ${verdict.label}") } }
    listOf(LifeField.OUTCOME to "Что произошло", LifeField.LESSON to "Что я понял")
        .map { (field, label) -> Triple(field, label, if (field == LifeField.OUTCOME) Calibration.stripVerdict(entry.text(field)) else entry.text(field)) }
        .filter { it.third.isNotBlank() }
        .forEach { (field, label, text) -> item(key = field.name) { FactCard(Icons.Rounded.CheckCircle, label, text, kindTone(LifeKind.DECISION).accent) } }
    val history = entry.text(LifeField.HISTORY)
    if (entry.status != LifeStatus.DRAFT && (entry.decidedOn == null || history.isBlank() || history.contains("Исходная история отсутствует"))) item(key = "prov") {
        Text("Исходное ожидание не сохранено: текущая запись не показывает, что было известно до результата", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (history.contains("· Записано после результата")) item(key = "late") {
        Text("Часть ожиданий записана вместе с результатом — это не прогноз заранее", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reviewItems(entry: LifeEntry, state: LifeUiState, enabled: Boolean, actions: DetailActions) {
    item(key = "facts") { WeekSummary(entry, state, actions.weekFacts) }
    listOf(LifeField.WINS to "Что получилось", LifeField.FRICTION to "Что мешало", LifeField.CHANGE to "Что изменю", LifeField.COMMITMENT to "Главное на неделю")
        .filter { entry.text(it.first).isNotBlank() }
        .forEach { (field, label) -> item(key = field.name) { FactCard(null, label, entry.text(field), kindTone(LifeKind.REVIEW).accent) } }
    item(key = "reviewActions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (entry.status == LifeStatus.DONE) "Дополнить" else "Продолжить обзор", actions.edit, icon = Icons.Rounded.Edit, enabled = enabled)
            if (entry.status == LifeStatus.DONE && entry.text(LifeField.COMMITMENT).isNotBlank()) Pill("Главное — в план", actions.planSheet, icon = Icons.Rounded.Today, enabled = enabled)
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** Facts of the week: steps per project and planned tasks. Missing notes are named, not counted as failure. */
@Composable
internal fun WeekSummary(entry: LifeEntry, state: LifeUiState, onLoad: (LocalDate, LocalDate) -> Unit) {
    val start = entry.periodStart
    val end = entry.periodEnd
    if (start == null || end == null) return
    val facts = state.weekFacts?.takeIf { it.start == start && it.end == end }
    LaunchedEffect(entry.id, start, end) { if (facts == null) onLoad(start, end) }
    val tone = kindTone(LifeKind.REVIEW)
    val steps = state.activity?.linkedSteps.orEmpty().filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
    val done = steps.filter { it.done }
    val perProject = done.groupBy { it.projectId ?: it.goalId ?: it.areaId }.mapNotNull { (id, list) -> state.snapshot.entry(id)?.let { it to list.size } }
        .sortedByDescending { it.second }
    val ru = Locale.forLanguageTag("ru")
    LifeCard(tint = tone.container) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${start.format(DateTimeFormatter.ofPattern("d MMM", ru))} — ${end.format(DateTimeFormatter.ofPattern("d MMM", ru))}".uppercase(ru), style = LifeType.section)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                WeekStat(done.size.toString(), "шагов к целям")
                if (facts != null && !facts.loading && facts.scheduled > 0) WeekStat("${facts.completedScheduled}/${facts.scheduled}", "задач со временем")
                if (facts != null && !facts.loading && facts.elapsedDays > 0) WeekStat("${facts.journalDays}", "дней с дневником")
            }
            val max = perProject.maxOfOrNull { it.second } ?: 0
            perProject.take(5).forEach { (project, count) ->
                val projectTone = kindTone(project.kind)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text(project.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(count.toString(), style = LifeType.number, color = projectTone.accent) }
                    SmoothBar(count.toFloat() / max, projectTone.accent, height = 5.dp)
                }
            }
            if (facts?.loading == true) Text("Читаю дневные заметки…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (facts != null && facts.failedDays > 0) Text("Не прочитано заметок: ${facts.failedDays}", style = MaterialTheme.typography.bodySmall,
                color = LocalFocusDeckColors.current.attention)
        }
    }
}

@Composable
private fun WeekStat(value: String, label: String) {
    Column {
        Text(value, style = LifeType.bigNumber.copy(fontSize = 30.sp, lineHeight = 32.sp), color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The if–then plan shown where the step is started, not only where it was written. */
@Composable
private fun IfThenLine(entry: LifeEntry, color: Color) {
    val plan = entry.text(LifeField.IF_THEN).trim()
    if (plan.isEmpty()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Shield, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(plan, style = MaterialTheme.typography.bodyMedium, color = color.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Forecast from the pace actually achieved; late forecasts are amber, never red. */
@Composable
internal fun PaceLine(pace: PaceForecast, unitGenitive: String, modifier: Modifier = Modifier) {
    val deck = LocalFocusDeckColors.current
    val rate = if (pace.perWeek >= 10) "%.0f".format(pace.perWeek) else "%.1f".format(Locale.forLanguageTag("ru"), pace.perWeek).removeSuffix(",0")
    val eta = pace.eta.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru")))
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Speed, null, tint = if (pace.late) deck.attention else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text("темп $rate $unitGenitive в неделю → финиш ~$eta" + when {
            pace.due == null -> ""
            pace.late -> " · позже срока"
            else -> " · успеваешь"
        }, style = MaterialTheme.typography.labelMedium, color = if (pace.late) deck.attention else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One number that moves. The last value large, the target beside it, a sparkline of
 * dated measurements, and a field to add today's value.
 */
@Composable
private fun MetricCard(entry: LifeEntry, enabled: Boolean, actions: DetailActions) {
    val tone = kindTone(entry.kind)
    val metric = Metrics.parse(entry.text(LifeField.METRIC))
    var value by rememberSaveable(entry.id) { mutableStateOf("") }
    if (metric == null) {
        if (enabled) Pill("Числовой показатель", actions.edit, icon = Icons.Rounded.Add)
        return
    }
    LifeCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ПОКАЗАТЕЛЬ", style = LifeType.section, modifier = Modifier.weight(1f))
                metric.fraction?.let { PercentLabel(it, tone.accent) }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(metric.current?.let(Metrics::format) ?: "—", style = LifeType.bigNumber, color = MaterialTheme.colorScheme.onSurface)
                Text("/ ${Metrics.format(metric.target)} ${metric.unit}".trim(), style = LifeType.muted, modifier = Modifier.padding(bottom = 6.dp))
            }
            if (metric.points.size >= 2) Sparkline(metric.points.map { it.second }, metric.target, tone.accent)
            LifePace.metric(entry)?.let { PaceLine(it, metric.unit.ifBlank { "ед." }) }
            if (enabled) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    if (value.isEmpty()) Text("Замер сегодня", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                    BasicTextField(value, { v -> value = v.filter { it.isDigit() || it == '.' || it == ',' || it == '-' } },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(tone.accent), singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { Metrics.number(value)?.let { actions.recordMetric(it); value = "" } }))
                }
                val parsed = Metrics.number(value)
                Pill("Записать", { parsed?.let { actions.recordMetric(it); value = "" } }, enabled = parsed != null,
                    container = tone.accent, content = tone.container)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, target: Double, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val min = minOf(values.min(), target)
        val max = maxOf(values.max(), target)
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height - ((v - min) / span * size.height)).toFloat()
        val targetY = y(target)
        drawLine(track, androidx.compose.ui.geometry.Offset(0f, targetY), androidx.compose.ui.geometry.Offset(size.width, targetY),
            strokeWidth = 2f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
        val step = size.width / (values.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        values.forEachIndexed { index, v -> if (index == 0) path.moveTo(0f, y(v)) else path.lineTo(index * step, y(v)) }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(5f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color, 8f, androidx.compose.ui.geometry.Offset((values.size - 1) * step, y(values.last())))
    }
}

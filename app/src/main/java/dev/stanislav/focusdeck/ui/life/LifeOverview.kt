package dev.stanislav.focusdeck.ui.life

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stanislav.focusdeck.data.life.Calibration
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeMomentum
import dev.stanislav.focusdeck.data.life.LifeProgress
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The compass: what moved, what needs a look, what is next. Everything on it is a door —
 * no explanatory paragraphs; an empty system gets one clear start instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LifeOverview(
    state: LifeUiState,
    onOpen: (LifeEntry) -> Unit,
    onKind: (LifeKind) -> Unit,
    onCreate: (LifeKind) -> Unit,
    onCreateSheet: () -> Unit,
    onPlanNext: (LifeEntry, LocalDate) -> Unit,
    onReview: (LifeEntry) -> Unit,
    onWeekly: (previousWeek: Boolean) -> Unit,
    onInitAreas: () -> Unit,
    onOpenVault: () -> Unit,
) {
    val snapshot = state.snapshot
    val entries = snapshot.entries.filter { !it.archived }
    val goals = entries.filter { it.kind == LifeKind.GOAL && it.status == LifeStatus.ACTIVE }
    val projects = entries.filter { it.kind == LifeKind.PROJECT && it.status == LifeStatus.ACTIVE }
        .sortedWith(compareByDescending<LifeEntry> { it.needsReview }
            .thenByDescending { LifeMomentum.lastDone(state.stepsOf(it)) ?: LocalDate.MIN }
            .thenBy { it.title.lowercase() })
    val parked = entries.filter { it.kind in listOf(LifeKind.PROJECT, LifeKind.GOAL) && it.status in listOf(LifeStatus.DRAFT, LifeStatus.PAUSED) }
    val areas = entries.filter { it.kind == LifeKind.AREA }
    val empty = snapshot.connected && !snapshot.loading && entries.none { it.kind != LifeKind.REVIEW }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!snapshot.connected && !snapshot.loading) {
            item { ConnectCard(onOpenVault) }
            return@LazyColumn
        }
        if (empty) {
            item { StartCard(onCreate) }
            return@LazyColumn
        }
        item(key = "pulse") { PulseCard(state) }
        val attention = attentionItems(state, projects)
        if (attention.isNotEmpty()) item(key = "attention") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(attention, key = { it.key }) { item ->
                    AttentionCard(item, onClick = {
                        when (item) {
                            is Attention.Review -> onReview(item.entry)
                            is Attention.NoNextStep -> onOpen(item.entry)
                            is Attention.Stale -> onReview(item.entry)
                            is Attention.Weekly -> onWeekly(item.previous)
                        }
                    })
                }
            }
        }
        if (goals.isNotEmpty()) {
            item(key = "goalsTitle") { SectionTitle("Цели · ${goals.size}") { MiniAdd { onCreate(LifeKind.GOAL) } } }
            item(key = "goals") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(goals, key = { it.id }) { goal -> GoalTile(goal, state, onClick = { onOpen(goal) }) }
                }
            }
        }
        item(key = "projectsTitle") { SectionTitle(if (projects.isEmpty()) "Проекты" else "В работе · ${projects.size}") { MiniAdd { onCreate(LifeKind.PROJECT) } } }
        if (projects.isEmpty()) item(key = "noProjects") {
            LifeCard(onClick = { onCreate(LifeKind.PROJECT) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(kindTone(LifeKind.PROJECT).icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Запустить проект", style = LifeType.title, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(projects, key = { it.id }) { project ->
            ProjectCard(project, state, onClick = { onOpen(project) }, onPlan = { date -> onPlanNext(project, date) },
                modifier = Modifier.animateItem())
        }
        if (parked.isNotEmpty()) item(key = "parked") { ParkedGroup(parked, onOpen) }
        item(key = "areas") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Сферы") { if (areas.isNotEmpty()) MiniAdd { onCreate(LifeKind.AREA) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    areas.sortedBy { it.title.lowercase() }.forEach { area ->
                        val count = entries.count { it.areaId == area.id && it.status == LifeStatus.ACTIVE && it.kind != LifeKind.AREA }
                        AreaChip(area, count, onClick = { onOpen(area) })
                    }
                    if (areas.isEmpty()) {
                        Pill("Сфера", { onCreate(LifeKind.AREA) }, icon = Icons.Rounded.Add)
                        Pill("Пять типовых", onInitAreas, enabled = !state.busy)
                    }
                }
            }
        }
        item(key = "doors") {
            val decisionsDue = entries.count { it.kind == LifeKind.DECISION && it.needsReview }
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (decisionsDue > 0) "Решения · $decisionsDue" else "Решения", { onKind(LifeKind.DECISION) }, icon = kindTone(LifeKind.DECISION).icon)
                Pill("Обзоры", { onKind(LifeKind.REVIEW) }, icon = Icons.Rounded.EventRepeat)
                Pill("Все проекты", { onKind(LifeKind.PROJECT) }, icon = kindTone(LifeKind.PROJECT).icon)
                Pill("Все цели", { onKind(LifeKind.GOAL) }, icon = kindTone(LifeKind.GOAL).icon)
            }
        }
        if (snapshot.problems.isNotEmpty()) item(key = "problems") { ProblemsRow(snapshot.problems) }
    }
}

/** Steps toward goals over four weeks: a record of effort, not a grade. */
@Composable
private fun PulseCard(state: LifeUiState) {
    val today = LocalDate.now()
    val linked = state.activity?.linkedSteps.orEmpty()
    val byDay = LifeMomentum.doneByDay(linked)
    val week = LifeMomentum.doneBetween(linked, today.minusDays(6), today)
    val previous = LifeMomentum.doneBetween(linked, today.minusDays(13), today.minusDays(7))
    val weeks = LifeMomentum.activeWeeks(linked, today)
    val accent = MaterialTheme.colorScheme.primary
    LifeCard(tint = MaterialTheme.colorScheme.primaryContainer, padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("ШАГИ К ЦЕЛЯМ", style = LifeType.section, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnimatedCount(week, LifeType.bigNumber, MaterialTheme.colorScheme.onSurface)
                    Text("за 7 дней", style = LifeType.muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                val delta = week - previous
                if (state.activity != null && (week > 0 || previous > 0)) Text(
                    when {
                        delta > 0 -> "+$delta к прошлой неделе"
                        delta < 0 -> "на ${-delta} меньше прошлой"
                        else -> "как на прошлой неделе"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (weeks > 0) LocalFocusDeckColors.current.attention else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp))
                    Text("$weeks из 4 недель в движении", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            HeatGrid(byDay, accent, cell = 13.dp)
        }
    }
}

private sealed interface Attention {
    val key: String
    data class Review(val entry: LifeEntry) : Attention { override val key get() = "r:${entry.id}" }
    data class NoNextStep(val entry: LifeEntry) : Attention { override val key get() = "n:${entry.id}" }
    data class Stale(val entry: LifeEntry, val days: Long) : Attention { override val key get() = "s:${entry.id}" }
    data class Weekly(val previous: Boolean) : Attention { override val key get() = "weekly" }
}

private fun attentionItems(state: LifeUiState, projects: List<LifeEntry>): List<Attention> = buildList {
    val today = LocalDate.now()
    state.snapshot.reviewsDue.filter { !it.archived && it.kind != LifeKind.REVIEW }.sortedBy { it.reviewDate }.forEach { add(Attention.Review(it)) }
    projects.filter { it.text(LifeField.NEXT_ACTION).isBlank() }.forEach { add(Attention.NoNextStep(it)) }
    // Open loops compete for attention (Masicampo & Baumeister 2011). A project with no completed
    // step for two weeks is asked a question — continue, change or pause — not scolded.
    if (state.activity != null) projects.filter { !it.needsReview && it.text(LifeField.NEXT_ACTION).isNotBlank() }.forEach { project ->
        val last = LifeMomentum.lastDone(state.stepsOf(project))
        val since = last ?: project.created.takeIf { it.year > 1970 } ?: return@forEach
        val days = java.time.temporal.ChronoUnit.DAYS.between(since, today)
        if (days >= 14) add(Attention.Stale(project, days))
    }
    // Weekly review at the week's edge; Monday is a fresh start (Dai, Milkman & Riis 2014) for the week just ended.
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun reviewed(from: LocalDate) = state.snapshot.entries.any { it.kind == LifeKind.REVIEW && it.periodStart == from && it.status == LifeStatus.DONE }
    if (today.dayOfWeek in listOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && !reviewed(start)) add(Attention.Weekly(false))
    if (today.dayOfWeek == DayOfWeek.MONDAY && !reviewed(start.minusWeeks(1))) add(Attention.Weekly(true))
}

@Composable
private fun AttentionCard(item: Attention, onClick: () -> Unit) {
    val deck = LocalFocusDeckColors.current
    val (label, title, icon) = when (item) {
        is Attention.Review -> Triple(if (item.entry.kind == LifeKind.DECISION) "Проверить решение" else "Свериться", item.entry.title, Icons.Rounded.EventRepeat)
        is Attention.NoNextStep -> Triple("Нет следующего шага", item.entry.title, Icons.Rounded.WarningAmber)
        is Attention.Stale -> Triple("Без шагов ${item.days} дн", item.entry.title, Icons.Rounded.EventRepeat)
        is Attention.Weekly -> if (item.previous) Triple("Новая неделя", "Разобрать прошлую", Icons.Rounded.CalendarMonth)
            else Triple("Конец недели", "Обзор недели", Icons.Rounded.CalendarMonth)
    }
    Column(
        Modifier.width(196.dp).height(96.dp).clip(RoundedCornerShape(22.dp)).background(deck.attentionContainer)
            .pressable(onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = deck.attention, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = deck.attention)
        }
        Text(title, style = LifeType.title.copy(fontSize = 16.sp, lineHeight = 20.sp), color = deck.onAttentionContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GoalTile(goal: LifeEntry, state: LifeUiState, onClick: () -> Unit) {
    val tone = kindTone(LifeKind.GOAL)
    val progress = state.progressOf(goal)
    val projects = state.snapshot.entries.count { it.kind == LifeKind.PROJECT && it.goalId == goal.id && !it.archived }
    Column(
        Modifier.width(210.dp).clip(RoundedCornerShape(24.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tone.container, MaterialTheme.colorScheme.surfaceContainer)))
            .pressable(onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress?.fraction, tone.accent, size = 52.dp, stroke = 6.dp) {
                if (progress != null) PercentLabel(progress.fraction, tone.onContainer, LifeType.number.copy(fontSize = 13.sp))
                else Icon(tone.icon, null, tint = tone.accent, modifier = Modifier.size(20.dp))
            }
            Box(Modifier.weight(1f))
            goal.dueDate?.let { DueChip(it) }
        }
        Text(goal.title, style = LifeType.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(48.dp))
        val done = state.snapshot.entries.count { it.kind == LifeKind.PROJECT && it.goalId == goal.id && it.status == LifeStatus.DONE }
        val metric = dev.stanislav.focusdeck.data.life.Metrics.parse(goal.text(LifeField.METRIC))
        Text(when {
            metric?.current != null -> "${dev.stanislav.focusdeck.data.life.Metrics.format(metric.current!!)} из ${dev.stanislav.focusdeck.data.life.Metrics.format(metric.target)} ${metric.unit}".trim()
            projects == 0 -> "нет проектов"
            done > 0 -> "${projectsWord(projects)} · готово $done"
            else -> projectsWord(projects)
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A project in one glance: measured progress, the next step, and one tap to plan it. */
@Composable
internal fun ProjectCard(
    project: LifeEntry,
    state: LifeUiState,
    onClick: () -> Unit,
    onPlan: ((LocalDate) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tone = kindTone(LifeKind.PROJECT)
    val progress = state.progressOf(project)
    val steps = state.stepsOf(project)
    val goal = state.snapshot.entry(project.goalId)
    val next = project.text(LifeField.NEXT_ACTION).trim()
    val planned = state.plannedNextStep(project)
    LifeCard(modifier = modifier, onClick = onClick, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProgressRing(progress?.fraction, tone.accent, size = 40.dp, stroke = 4.5.dp) {
                    if (progress != null) PercentLabel(progress.fraction, MaterialTheme.colorScheme.onSurface, LifeType.number.copy(fontSize = 11.sp))
                    else Icon(tone.icon, null, tint = tone.accent, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(project.title, style = LifeType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(goal?.title, project.needsReview.takeIf { it }?.let { "пора свериться" }).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                project.dueDate?.let { DueChip(it) }
            }
            if (progress != null && progress.unit == LifeProgress.ProgressUnit.MILESTONES) SegmentedBar(progress.done, progress.total, tone.accent)
            if (next.isEmpty() && onPlan != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Add, null, tint = LocalFocusDeckColors.current.attention, modifier = Modifier.size(16.dp))
                    Text("Задай следующий шаг", style = MaterialTheme.typography.labelLarge, color = LocalFocusDeckColors.current.attention)
                }
            }
            if (next.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = tone.accent, modifier = Modifier.size(18.dp))
                    Text(next, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when {
                        planned?.done == true -> Icon(Icons.Rounded.Check, "Сделано", tint = tone.accent, modifier = Modifier.padding(8.dp).size(18.dp))
                        planned != null -> Text(relativeDay(planned.date) + (planned.startMinute?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: ""),
                            style = MaterialTheme.typography.labelMedium, color = tone.accent, modifier = Modifier.padding(horizontal = 8.dp))
                        onPlan != null -> Pill("Сегодня", { onPlan(LocalDate.now()) }, icon = Icons.Rounded.Today,
                            container = tone.container, content = tone.onContainer)
                    }
                }
            }
            val recent = LifeMomentum.doneBetween(steps, LocalDate.now().minusDays(13), LocalDate.now())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeatStrip(LifeMomentum.doneByDay(steps), tone.accent, days = 14, cell = 8.dp)
                Text(if (recent == 0) "нет шагов за 2 недели" else "${stepsWord(recent)} за 2 недели",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ParkedGroup(parked: List<LifeEntry>, onOpen: (LifeEntry) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Идеи и пауза · ${parked.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) parked.sortedBy { it.title.lowercase() }.forEach { entry -> EntryRow(entry, onClick = { onOpen(entry) }) }
    }
}

@Composable
internal fun EntryRow(entry: LifeEntry, onClick: () -> Unit, trailing: String? = null) {
    val tone = kindTone(entry.kind)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(tone.container.copy(alpha = if (entry.inUse) 1f else 0.5f)), contentAlignment = Alignment.Center) {
            Icon(tone.icon, null, tint = tone.onContainer, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(trailing ?: entry.statusLabel.lowercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (entry.needsReview) Box(Modifier.size(8.dp).clip(CircleShape).background(LocalFocusDeckColors.current.attention))
    }
}

@Composable
private fun AreaChip(area: LifeEntry, count: Int, onClick: () -> Unit) {
    val tone = kindTone(LifeKind.AREA)
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(tone.container.copy(alpha = 0.7f)).pressable(onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(area.title, style = MaterialTheme.typography.labelLarge, color = tone.onContainer)
        if (count > 0) Text(count.toString(), style = LifeType.number.copy(fontSize = 13.sp), color = tone.accent)
    }
}

@Composable
private fun MiniAdd(onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Add, "Добавить", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ProblemsRow(problems: List<String>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable { open = !open }.padding(12.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, null, tint = LocalFocusDeckColors.current.attention, modifier = Modifier.size(16.dp))
            Text("Заметки с ошибками · ${problems.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) problems.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ConnectCard(onOpenVault: () -> Unit) {
    LifeCard(tint = MaterialTheme.colorScheme.tertiaryContainer, padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Подключи Obsidian", style = LifeType.hero.copy(fontSize = 22.sp))
            Text("Каждая цель и проект — обычная заметка в папке FocusDeck", style = LifeType.muted)
            Pill("Выбрать папку", onOpenVault, container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
        }
    }
}

/** First visit: one question, three doors. */
@Composable
private fun StartCard(onCreate: (LifeKind) -> Unit) {
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("С чего начнём?", style = LifeType.hero)
        Text("Хватит одного проекта — сферы и цели можно добавить потом", style = LifeType.muted)
        Gap(4.dp)
        listOf(
            Triple(LifeKind.PROJECT, "Проект", "Работа с понятным финалом"),
            Triple(LifeKind.GOAL, "Цель", "Изменение, к которому идёшь"),
            Triple(LifeKind.DECISION, "Решение", "Выбор, который стоит проверить"),
        ).forEach { (kind, title, text) -> KindTile(kind, title, text) { onCreate(kind) } }
    }
}

@Composable
private fun KindTile(kind: LifeKind, title: String, text: String, onClick: () -> Unit) {
    val tone = kindTone(kind)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(tone.container, MaterialTheme.colorScheme.surfaceContainer)))
            .pressable(onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(tone.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(tone.icon, null, tint = tone.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LifeType.title)
            Text(text, style = LifeType.muted)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateSheet(parent: LifeEntry?, onDismiss: () -> Unit, onPick: (LifeKind) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KindTile(LifeKind.PROJECT, "Проект", "Работа с понятным финалом") { onPick(LifeKind.PROJECT) }
            if (parent?.kind != LifeKind.GOAL) KindTile(LifeKind.GOAL, "Цель", "Изменение, к которому идёшь") { onPick(LifeKind.GOAL) }
            KindTile(LifeKind.DECISION, "Решение", "Выбор, который стоит проверить") { onPick(LifeKind.DECISION) }
            if (parent == null) KindTile(LifeKind.AREA, "Сфера", "То, что поддерживаешь постоянно") { onPick(LifeKind.AREA) }
        }
    }
}

/** Step → plan: what, which day, how long. Three rows, no form chrome. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlanSheet(entry: LifeEntry, onDismiss: () -> Unit, onConfirm: (String, LocalDate, Int, Int?) -> Unit) {
    val context = LocalContext.current
    val suggestion = entry.text(LifeField.NEXT_ACTION).ifBlank { if (entry.kind == LifeKind.REVIEW) entry.text(LifeField.COMMITMENT) else "" }
    var title by rememberSaveable(entry.id) { mutableStateOf(suggestion) }
    var date by rememberSaveable(entry.id) { mutableStateOf(LocalDate.now().toString()) }
    var minutes by rememberSaveable(entry.id) { mutableStateOf(DEFAULT_TASK_MINUTES) }
    var start by rememberSaveable(entry.id) { mutableStateOf<Int?>(null) }
    val day = LocalDate.parse(date)
    val tone = kindTone(entry.kind)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(entry.title, style = MaterialTheme.typography.labelLarge, color = tone.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                if (title.isEmpty()) Text("Что сделаешь?", style = LifeType.input, color = MaterialTheme.colorScheme.outline)
                BasicTextField(title, { title = it.replace('\n', ' ') }, textStyle = LifeType.input, cursorBrush = SolidColor(tone.accent), modifier = Modifier.fillMaxWidth())
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = LocalDate.now()
                ChoiceChip("Сегодня", day == today, { date = today.toString() }, tone.accent)
                ChoiceChip("Завтра", day == today.plusDays(1), { date = today.plusDays(1).toString() }, tone.accent)
                val custom = day != today && day != today.plusDays(1)
                ChoiceChip(if (custom) relativeDay(day) else "Дата", custom, {
                    DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d).toString() }, day.year, day.monthValue - 1, day.dayOfMonth).show()
                }, tone.accent, Icons.Rounded.CalendarMonth)
            }
            // An implementation intention names *when*: a time cue roughly doubles follow-through
            // in the meta-analyses (Gollwitzer & Sheeran 2006). Leaving it open stays possible.
            Text("Когда начнёшь?", style = LifeType.section)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(9 * 60 to "Утром · 9:00", 13 * 60 to "Днём · 13:00", 19 * 60 to "Вечером · 19:00").forEach { (minute, label) ->
                    ChoiceChip(label, start == minute, { start = if (start == minute) null else minute }, tone.accent)
                }
                val custom = start?.takeIf { it !in listOf(9 * 60, 13 * 60, 19 * 60) }
                ChoiceChip(custom?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "Другое время", custom != null, {
                    val initial = start ?: (java.time.LocalTime.now().hour + 1).coerceAtMost(22) * 60
                    android.app.TimePickerDialog(context, { _, h, m -> start = h * 60 + m }, initial / 60, initial % 60, true).show()
                }, tone.accent, Icons.Rounded.Schedule)
            }
            Text("Сколько займёт?", style = LifeType.section)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 25, 45, 60, 90).forEach { value ->
                    ChoiceChip("$value мин", minutes == value, { minutes = value }, tone.accent)
                }
            }
            val valid = title.count { it.isLetterOrDigit() } >= 3
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(if (valid) tone.accent else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .pressable({ if (valid) onConfirm(title.trim(), day, minutes, start) }, enabled = valid),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("В план на ${planDayLabel(day)}" + (start?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: ""), style = MaterialTheme.typography.labelLarge,
                    color = if (valid) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun LifeList(state: LifeUiState, kind: LifeKind, onOpen: (LifeEntry) -> Unit, onShowArchived: (Boolean) -> Unit, onWeekly: () -> Unit = {}) {
    val all = state.snapshot.entries.filter { it.kind == kind && (state.showArchived || !it.archived) }
    val groups = listOf(
        "Сейчас" to all.filter { it.status == LifeStatus.ACTIVE },
        "Идеи" to all.filter { it.status == LifeStatus.DRAFT },
        "Пауза" to all.filter { it.status == LifeStatus.PAUSED },
        "Завершено" to all.filter { it.status == LifeStatus.DONE },
        "Архив" to all.filter { it.status == LifeStatus.ARCHIVED },
    ).filter { it.second.isNotEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (kind == LifeKind.REVIEW) item(key = "weekly") {
            val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val current = state.snapshot.entries.firstOrNull { it.kind == LifeKind.REVIEW && it.periodStart == start && !it.archived }
            KindTile(LifeKind.REVIEW, if (current == null) "Обзор этой недели" else "Эта неделя", when (current?.status) {
                null -> "Факты недели и три коротких ответа"
                LifeStatus.DONE -> "Готов — можно дополнить"
                else -> "Черновик — продолжить"
            }) { if (current != null) onOpen(current) else onWeekly() }
        }
        if (kind == LifeKind.DECISION) Calibration.summary(state.snapshot.entries)?.let { summary -> item(key = "calibration") { CalibrationCard(summary) } }
        if (groups.isEmpty() && kind != LifeKind.REVIEW) item {
            Text(if (state.snapshot.loading) "" else "Пока пусто", style = LifeType.muted, modifier = Modifier.padding(top = 24.dp, start = 4.dp))
        }
        groups.forEach { (label, list) ->
            item(key = "t:$label") { SectionTitle("$label · ${list.size}") }
            if (kind == LifeKind.PROJECT && label == "Сейчас") {
                items(list.sortedBy { it.title.lowercase() }, key = { it.id }) { ProjectCard(it, state, onClick = { onOpen(it) }, onPlan = null) }
            } else item(key = "g:$label") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                    list.sortedWith(compareByDescending<LifeEntry> { it.periodStart ?: it.updated }.thenBy { it.title.lowercase() }).forEach { entry ->
                        EntryRow(entry, onClick = { onOpen(entry) }, trailing = listTrailing(entry, state))
                    }
                }
            }
        }
        item(key = "archive") {
            Pill(if (state.showArchived) "Скрыть архив" else "Показать архив", { onShowArchived(!state.showArchived) }, icon = Icons.Rounded.Inventory2)
        }
    }
}

private fun listTrailing(entry: LifeEntry, state: LifeUiState): String = when {
    entry.needsReview && entry.kind == LifeKind.DECISION -> "пора проверить"
    entry.needsReview -> "пора свериться"
    entry.kind == LifeKind.DECISION && entry.status == LifeStatus.ACTIVE && entry.reviewDate != null -> "проверка ${relativeDay(entry.reviewDate)}"
    entry.kind in listOf(LifeKind.GOAL, LifeKind.PROJECT) -> state.progressOf(entry)?.let { "${(it.fraction * 100).toInt()}% · ${entry.statusLabel.lowercase()}" }
        ?: entry.statusLabel.lowercase()
    else -> entry.statusLabel.lowercase()
}

internal fun projectsWord(count: Int): String = "$count " + when {
    count % 10 == 1 && count % 100 != 11 -> "проект"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "проекта"
    else -> "проектов"
}

internal fun stepsWord(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    val word = when {
        mod10 == 1 && mod100 != 11 -> "шаг"
        mod10 in 2..4 && mod100 !in 12..14 -> "шага"
        else -> "шагов"
    }
    return "$count $word"
}

/**
 * How well confidence matched reality across checked decisions. Brier score: 0 is perfect,
 * 0.25 is what always saying 50% gives. Feedback like this trains calibration (Mellers et al. 2014).
 */
@Composable
private fun CalibrationCard(summary: Calibration.Summary) {
    val tone = kindTone(LifeKind.DECISION)
    val gap = summary.meanConfidence - summary.hitRate
    LifeCard(tint = tone.container) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("КАЛИБРОВКА · ${summary.count} ${dev.stanislav.focusdeck.data.life.plural(summary.count, listOf("решение", "решения", "решений"))}", style = LifeType.section)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("${(summary.meanConfidence * 100).toInt()}%", style = LifeType.bigNumber.copy(fontSize = 30.sp, lineHeight = 32.sp))
                    Text("средняя уверенность", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column {
                    Text("${(summary.hitRate * 100).toInt()}%", style = LifeType.bigNumber.copy(fontSize = 30.sp, lineHeight = 32.sp), color = tone.accent)
                    Text("сбылось", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (summary.count >= 3) Text(when {
                gap > 0.15 -> "Прогнозы пока смелее реальности — стоит закладывать больше неопределённости"
                gap < -0.15 -> "Сбывается чаще, чем ожидаешь — можно доверять себе больше"
                else -> "Уверенность близка к тому, что сбывается"
            }, style = LifeType.muted)
            else Text("Выводы — после трёх проверенных решений", style = LifeType.muted)
        }
    }
}

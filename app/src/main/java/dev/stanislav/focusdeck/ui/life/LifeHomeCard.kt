package dev.stanislav.focusdeck.ui.life

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeMomentum
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.LocalDate

/**
 * The launcher's window into goals: the project that is moving now and its next step,
 * so the link between today and the bigger aim is visible without opening anything.
 */
@Composable
fun LifeHomeCard(state: LifeUiState, onOpen: () -> Unit) {
    val today = LocalDate.now()
    val projects = state.snapshot.entries.filter { it.kind == LifeKind.PROJECT && it.status == LifeStatus.ACTIVE }
    val focus: LifeEntry? = projects.minByOrNull { project ->
        val planned = state.plannedNextStep(project)
        when {
            planned != null && planned.date == today && !planned.done -> 0
            project.needsReview -> 2
            else -> 1
        } * 10_000L - (LifeMomentum.lastDone(state.stepsOf(project))?.toEpochDay() ?: 0L) / 1000
    }
    val week = LifeMomentum.doneBetween(state.activity?.linkedSteps.orEmpty(), today.minusDays(6), today)
    val due = state.snapshot.reviewsDue.count { !it.archived && it.kind != LifeKind.REVIEW }
    val tone = kindTone(LifeKind.PROJECT)
    LifeCard(onClick = onOpen, tint = if (focus != null) tone.container else null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ЦЕЛИ И ПРОЕКТЫ", style = LifeType.section, modifier = Modifier.weight(1f))
                if (week > 0) {
                    Icon(Icons.Rounded.LocalFireDepartment, null, tint = LocalFocusDeckColors.current.attention, modifier = Modifier.size(16.dp))
                    Text(stepsWord(week) + " за неделю", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            if (focus == null) {
                Text(if (state.snapshot.entries.isEmpty()) "Запусти первый проект" else "Нет проектов в работе", style = LifeType.title)
            } else {
                val progress = state.progressOf(focus)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProgressRing(progress?.fraction, tone.accent, size = 44.dp, stroke = 5.dp) {
                        if (progress != null) PercentLabel(progress.fraction, MaterialTheme.colorScheme.onSurface, LifeType.number.copy(fontSize = 12.sp))
                        else Icon(tone.icon, null, tint = tone.accent, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(focus.title, style = LifeType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val next = focus.text(LifeField.NEXT_ACTION).trim()
                        if (next.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = tone.accent, modifier = Modifier.size(14.dp))
                            Text(next, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        focus.text(LifeField.IF_THEN).trim().takeIf { it.isNotEmpty() }?.let { plan ->
                            Text(plan, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (due > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.EventRepeat, null, tint = LocalFocusDeckColors.current.attention, modifier = Modifier.size(16.dp))
                Text("Пора свериться: $due", style = MaterialTheme.typography.labelLarge, color = LocalFocusDeckColors.current.attention)
            }
        }
    }
}

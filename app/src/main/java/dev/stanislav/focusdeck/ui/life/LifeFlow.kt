package dev.stanislav.focusdeck.ui.life

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stanislav.focusdeck.data.life.Calibration
import dev.stanislav.focusdeck.data.life.GoalMotive
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeMomentum
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.data.life.meaningfulLifeText
import java.time.LocalDate

@Composable
internal fun LifeFlow(state: LifeUiState, viewModel: LifeViewModel, mode: String, onClose: () -> Unit, onDiscard: () -> Unit) {
    val draft = state.draft ?: return
    when {
        draft.kind == LifeKind.REVIEW -> WeeklyFlow(state, viewModel, onClose)
        mode == "review" && draft.kind == LifeKind.DECISION -> DecisionCheckFlow(state, viewModel, onClose)
        mode == "complete" -> CompleteFlow(state, viewModel, onClose)
        else -> CheckInFlow(state, viewModel, onClose)
    }
}

private enum class Verdict(val label: String, val note: String, val icon: ImageVector) {
    CONTINUE("Продолжаю", "Продолжаю без изменений", Icons.Rounded.PlayArrow),
    ADJUST("Меняю подход", "", Icons.Rounded.Tune),
    PAUSE("Пауза", "Ставлю на паузу", Icons.Rounded.Pause),
    FINISH("Готово", "Результат получен", Icons.Rounded.EmojiEvents),
    DROP("Больше не планирую", "Больше не планирую: ", Icons.Rounded.Archive),
}

/**
 * A check-in is a decision, not an essay: pick what happens next, add a line if there is one,
 * choose when to look again. Keeping things as they are is a valid outcome.
 */
@Composable
private fun CheckInFlow(state: LifeUiState, viewModel: LifeViewModel, onClose: () -> Unit) {
    val draft = state.draft ?: return
    val base = state.base ?: draft
    val tone = kindTone(draft.kind)
    val today = LocalDate.now()
    var verdict by rememberSaveable(draft.id) { mutableStateOf<Verdict?>(null) }
    val steps = state.stepsOf(base)
    val last = LifeMomentum.lastDone(steps)
    val progress = state.progressOf(base)
    fun status(v: Verdict?): LifeStatus = when (v) {
        Verdict.PAUSE -> LifeStatus.PAUSED
        Verdict.FINISH -> LifeStatus.DONE
        Verdict.DROP -> LifeStatus.ARCHIVED
        Verdict.CONTINUE, Verdict.ADJUST -> if (draft.copy(status = LifeStatus.ACTIVE).activationProblems().isEmpty()) LifeStatus.ACTIVE else draft.status
        null -> draft.status
    }
    val note = draft.text(LifeField.REVIEW_NOTE)
    val futureOk = draft.reviewDate?.isAfter(today) != false
    // Continuing an active project needs a next step; say so at the field, not in an error banner.
    val missing = if (verdict in listOf(Verdict.CONTINUE, Verdict.ADJUST) && draft.status == LifeStatus.ACTIVE)
        draft.activationProblems() else emptyList()
    val canSave = !state.busy && state.snapshot.connected && verdict != null && meaningfulLifeText(note) && futureOk && missing.isEmpty()
    StepScaffold(
        steps = 1, step = 0, accent = tone.accent, onClose = onClose,
        primaryLabel = if (verdict == Verdict.FINISH) "Завершить" else "Сохранить сверку",
        primaryEnabled = canSave,
        onPrimary = { viewModel.saveDraft(status(verdict), recordLifeReview = true) },
    ) {
        Text("Как идёт «${base.title}»?", style = LifeType.question)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(progress?.fraction, tone.accent, size = 48.dp, stroke = 5.dp) {
                if (progress != null) PercentLabel(progress.fraction, MaterialTheme.colorScheme.onSurface, LifeType.number.copy(fontSize = 12.sp))
                else Icon(tone.icon, null, tint = tone.accent, modifier = Modifier.size(18.dp))
            }
            Column {
                Text("${stepsWord(steps.count { it.done })} за 4 недели", style = MaterialTheme.typography.titleSmall)
                Text(last?.let { "последний — ${relativeDay(it)}" } ?: "шагов в плане пока не было", style = LifeType.muted)
            }
        }
        GoalMotive.of(base.text(LifeField.WHY))?.takeIf { !it.autonomous }?.let { motive ->
            Text("Мотив был «${motive.label}». Если цель держится только на этом, её честно можно отпустить или переформулировать.",
                style = LifeType.muted)
        }
        ChipGroup(null) {
            Verdict.entries.filter { it != Verdict.FINISH || draft.kind != LifeKind.AREA }.forEach { v ->
                ChoiceChip(if (v == Verdict.FINISH && draft.kind == LifeKind.GOAL) "Достигнута" else v.label, verdict == v, {
                    if (v == Verdict.FINISH && draft.kind == LifeKind.GOAL) {
                        viewModel.setDraftPurpose("complete")
                        return@ChoiceChip
                    }
                    val previousPreset = Verdict.entries.any { it.note.isNotEmpty() && note.trim() == it.note.trim() }
                    verdict = v
                    if (note.isBlank() || previousPreset) viewModel.updateField(LifeField.REVIEW_NOTE, v.note)
                }, tone.accent, v.icon)
            }
        }
        if (verdict != null) {
            QuestionField("Что стало ясно?", note, { viewModel.updateField(LifeField.REVIEW_NOTE, it) },
                "Одна-две фразы", tone.accent, focus = verdict == Verdict.ADJUST || verdict == Verdict.DROP)
            if (draft.kind == LifeKind.PROJECT && verdict in listOf(Verdict.CONTINUE, Verdict.ADJUST)) {
                QuestionField("Следующий шаг", draft.text(LifeField.NEXT_ACTION), { viewModel.updateField(LifeField.NEXT_ACTION, it.replace('\n', ' ')) },
                    "Одно действие", tone.accent, singleLine = true, focus = false,
                    hint = if (draft.text(LifeField.NEXT_ACTION).isBlank()) "Без него проект не двигается — одно конкретное действие" else null)
            }
            missing.filterNot { draft.kind == LifeKind.PROJECT && it.contains("следующее действие") }.takeIf { it.isNotEmpty() }?.let {
                Text("Для продолжения: ${it.joinToString(", ")}. Дописать можно через «Изменить».", style = LifeType.muted)
            }
            if (verdict in listOf(Verdict.CONTINUE, Verdict.ADJUST, Verdict.PAUSE)) ReminderChips(draft.reviewDate, tone.accent) { date ->
                viewModel.updateDraft { it.copy(reviewDate = date) }
            }
        }
    }
}

@Composable
private fun ReminderChips(value: LocalDate?, accent: androidx.compose.ui.graphics.Color, onPick: (LocalDate?) -> Unit) {
    val today = LocalDate.now()
    ChipGroup("Свериться снова") {
        listOf(7L to "Через неделю", 14L to "2 недели", 30L to "Месяц").forEach { (days, label) ->
            ChoiceChip(label, value == today.plusDays(days), { onPick(today.plusDays(days)) }, accent)
        }
        ChoiceChip("Без напоминания", value == null, { onPick(null) }, accent)
    }
}

/** The expectation saved at the time of the decision is shown first; the outcome is written separately. */
@Composable
private fun DecisionCheckFlow(state: LifeUiState, viewModel: LifeViewModel, onClose: () -> Unit) {
    val draft = state.draft ?: return
    val base = state.base ?: draft
    val tone = kindTone(LifeKind.DECISION)
    val context = LocalContext.current
    val today = LocalDate.now()
    var again by rememberSaveable(draft.id) { mutableStateOf(false) }
    val outcome = draft.text(LifeField.OUTCOME)
    val future = draft.reviewDate?.isAfter(today) == true
    val grounds = draft.activationProblems()
    val enabled = !state.busy && state.snapshot.connected && meaningfulLifeText(Calibration.stripVerdict(outcome))
    StepScaffold(
        steps = 1, step = 0, accent = tone.accent, onClose = onClose,
        primaryLabel = if (again) "Проверить ещё раз" else "Закрыть решение",
        primaryEnabled = enabled && (!again || (future && grounds.isEmpty())),
        onPrimary = {
            if (again) viewModel.saveDraft(LifeStatus.ACTIVE, recordDecisionReview = true)
            else viewModel.saveDraft(LifeStatus.DONE, recordDecisionReview = true)
        },
    ) {
        Text(base.title, style = LifeType.question)
        base.text(LifeField.PREDICTION).takeIf { it.isNotBlank() }?.let { prediction ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text((base.decidedOn?.let { "ОЖИДАНИЕ ОТ ${relativeDay(it).uppercase()}" } ?: "ОЖИДАНИЕ"), style = LifeType.section, color = tone.accent)
                    Text(Calibration.stripConfidence(prediction), style = LifeType.quote)
                }
            }
        } ?: Text("Исходное ожидание не сохранено", style = LifeType.muted)
        QuestionField("Что произошло на самом деле?", Calibration.stripVerdict(outcome),
            { viewModel.updateField(LifeField.OUTCOME, Calibration.withVerdict(it, Calibration.verdict(outcome))) }, "Факты, без оценки выбора", tone.accent)
        val verdict = Calibration.verdict(outcome)
        ChipGroup("Ожидание сбылось?" + (Calibration.confidence(base.text(LifeField.PREDICTION))?.let { " · была уверенность $it%" } ?: "")) {
            Calibration.Verdict.entries.forEach { option ->
                ChoiceChip(option.label, verdict == option, {
                    viewModel.updateField(LifeField.OUTCOME, Calibration.withVerdict(Calibration.stripVerdict(outcome), if (verdict == option) null else option))
                }, tone.accent)
            }
        }
        QuestionField("Что возьмёшь в следующий выбор?", draft.text(LifeField.LESSON), { viewModel.updateField(LifeField.LESSON, it) },
            "Необязательно", tone.accent, focus = false)
        ChipGroup(null) {
            ChoiceChip("Итог ясен", !again, { again = false }, tone.accent)
            ChoiceChip("Проверить позже ещё раз", again, {
                again = true
                if (draft.reviewDate?.isAfter(today) != true) viewModel.updateDraft { it.copy(reviewDate = today.plusDays(14)) }
            }, tone.accent)
        }
        if (again) {
            ChipGroup("Когда") {
                listOf(7L to "Неделя", 14L to "2 недели", 30L to "Месяц").forEach { (days, label) ->
                    ChoiceChip(label, draft.reviewDate == today.plusDays(days), { viewModel.updateDraft { it.copy(reviewDate = today.plusDays(days)) } }, tone.accent)
                }
                ChoiceChip("Дата", false, {
                    val initial = draft.reviewDate?.takeIf { it.isAfter(today) } ?: today.plusDays(14)
                    DatePickerDialog(context, { _, y, m, d -> viewModel.updateDraft { it.copy(reviewDate = LocalDate.of(y, m + 1, d)) } },
                        initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                }, tone.accent, Icons.Rounded.CalendarMonth)
            }
            if (grounds.isNotEmpty()) Text("Для повторной проверки нужны основания: ${grounds.joinToString(", ")}. Их можно дописать через «Изменить».",
                style = LifeType.muted)
        }
        Text("Пока рано судить", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.pressable({
                viewModel.discardDraft()
                viewModel.postponeReview(today.plusDays(7))
            }).padding(vertical = 8.dp))
    }
}

/** Achievement needs a fact that matches the criterion agreed earlier — shown right above the field. */
@Composable
private fun CompleteFlow(state: LifeUiState, viewModel: LifeViewModel, onClose: () -> Unit) {
    val draft = state.draft ?: return
    val tone = kindTone(draft.kind)
    val evidence = draft.text(LifeField.EVIDENCE)
    val needsEvidence = draft.kind == LifeKind.GOAL
    StepScaffold(
        steps = 1, step = 0, accent = tone.accent, onClose = onClose,
        primaryLabel = if (draft.kind == LifeKind.GOAL) "Цель достигнута" else "Завершить",
        primaryEnabled = !state.busy && state.snapshot.connected && (!needsEvidence || meaningfulLifeText(evidence)),
        onPrimary = { viewModel.saveDraft(LifeStatus.DONE) },
    ) {
        Text(draft.title, style = LifeType.question)
        draft.text(LifeField.SUCCESS).takeIf { it.isNotBlank() }?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ДОГОВАРИВАЛИСЬ СЧИТАТЬ ГОТОВЫМ, КОГДА", style = LifeType.section, color = tone.accent)
                Text(it, style = LifeType.quote)
            }
        }
        QuestionField("Что это подтверждает?", evidence, { viewModel.updateField(LifeField.EVIDENCE, it) }, "Факт: дата, число, результат", tone.accent)
    }
}

/** Weekly review: facts first, then three short answers. «Keep as is» is a complete answer. */
@Composable
private fun WeeklyFlow(state: LifeUiState, viewModel: LifeViewModel, onClose: () -> Unit) {
    val draft = state.draft ?: return
    val tone = kindTone(LifeKind.REVIEW)
    var step by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    val questions = listOf(
        Triple(LifeField.WINS, "Что получилось?", "На какие факты опираешься"),
        Triple(LifeField.FRICTION, "Что мешало?", "Где план разошёлся с реальностью"),
        Triple(LifeField.CHANGE, "Что изменишь — или оставишь как есть?", "Один эксперимент на неделю"),
        Triple(LifeField.COMMITMENT, "Главное на следующую неделю?", "Одна вещь, достойная времени"),
    )
    val total = questions.size + 1
    val last = step == total - 1
    val canFinish = meaningfulLifeText(draft.text(LifeField.CHANGE))
    val enabled = !state.busy && state.snapshot.connected
    BackHandler(enabled = step > 0) { step-- }
    StepScaffold(
        steps = total, step = step, accent = tone.accent, onClose = onClose,
        primaryLabel = when {
            step == 0 -> "Начать"
            !last -> "Дальше"
            canFinish -> "Завершить обзор"
            else -> "Сохранить черновик"
        },
        primaryEnabled = enabled,
        onPrimary = { if (!last) step++ else if (canFinish) viewModel.saveDraft(LifeStatus.DONE) else viewModel.saveDraft() },
        onSkip = if (step in 1 until total - 1) ({ step++ }) else null,
        canGoBack = step > 0,
        onBack = { step-- },
    ) {
        AnimatedContent(step, transitionSpec = {
            val forward = targetState > initialState
            (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(180)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(140)))
        }, label = "weekly") { index ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (index == 0) {
                    Text("Как прошла неделя", style = LifeType.question)
                    WeekSummary(draft, state, viewModel::loadWeekFacts)
                } else {
                    val (field, question, placeholder) = questions[index - 1]
                    QuestionField(question, draft.text(field), { viewModel.updateField(field, it) }, placeholder, tone.accent)
                    if (field == LifeField.CHANGE) ChipGroup(null) {
                        ChoiceChip("Сохраню текущий режим", draft.text(field).trim() == "Сохраню текущий режим",
                            { viewModel.updateField(field, "Сохраню текущий режим") }, tone.accent)
                    }
                }
            }
        }
    }
}

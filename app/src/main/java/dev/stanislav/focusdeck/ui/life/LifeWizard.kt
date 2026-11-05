package dev.stanislav.focusdeck.ui.life

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.life.Calibration
import dev.stanislav.focusdeck.data.life.GoalMotive
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.Metrics
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.data.life.Milestones
import dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Shared frame for every step-by-step flow: a close button and step dots on top,
 * one question in the middle, one action at the bottom that rides above the keyboard.
 * From the second step a round back button sits beside it, where the thumb already is.
 */
@Composable
internal fun StepScaffold(
    steps: Int,
    step: Int,
    accent: Color,
    onClose: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    onSkip: (() -> Unit)? = null,
    canGoBack: Boolean = false,
    onBack: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(120.dp)) { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (steps > 1) repeat(steps) { index ->
                    val width by animateDpAsState(if (index == step) 22.dp else 7.dp, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow), label = "dot")
                    val color by animateColorAsState(if (index <= step) accent else MaterialTheme.colorScheme.surfaceContainerHighest, tween(250), label = "dotColor")
                    Box(Modifier.padding(horizontal = 3.dp).width(width).height(7.dp).clip(CircleShape).background(color))
                }
            }
            Box(Modifier.width(120.dp), contentAlignment = Alignment.CenterEnd) {
                if (onSkip != null) Text("Пропустить", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.clip(CircleShape).pressable(onSkip).padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnimatedVisibility(canGoBack, enter = fadeIn(tween(180)) + expandHorizontally(tween(220)),
                exit = fadeOut(tween(120)) + shrinkHorizontally(tween(200))) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).pressable(onBack),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            val bg by animateColorAsState(if (primaryEnabled) accent else MaterialTheme.colorScheme.surfaceContainerHighest, tween(200), label = "primary")
            Row(
                Modifier.weight(1f).height(56.dp).clip(CircleShape).background(bg).pressable(onPrimary, primaryEnabled),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val fg = if (primaryEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant
                Text(primaryLabel, style = MaterialTheme.typography.titleSmall, color = fg)
                Icon(if (step == steps - 1) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward, null, tint = fg,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
    }
}

/** The question is the label. The answer is typed straight under it, in a large face, without a box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuestionField(
    question: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    accent: Color,
    singleLine: Boolean = false,
    hint: String? = null,
    focus: Boolean = true,
    onNext: (() -> Unit)? = null,
) {
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Between steps the keyboard is already up: focus passes straight from the leaving field to this
    // one, so the keyboard stays. Only a fresh open waits for the screen to settle before raising it.
    val keyboardUp = WindowInsets.isImeVisible
    LaunchedEffect(question) {
        if (focus) { if (!keyboardUp) delay(280); runCatching { requester.requestFocus(); keyboard?.show() } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(question, style = LifeType.question)
        hint?.let { Text(it, style = LifeType.muted) }
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            if (value.isEmpty()) Text(placeholder, style = LifeType.input, color = MaterialTheme.colorScheme.outline)
            BasicTextField(
                value = value, onValueChange = { onValue(if (singleLine) it.replace('\n', ' ') else it) },
                textStyle = LifeType.input, cursorBrush = SolidColor(accent), singleLine = singleLine,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
                keyboardActions = KeyboardActions(onNext = { onNext?.invoke() }),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
        }
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(if (value.isEmpty()) MaterialTheme.colorScheme.surfaceContainerHighest else accent.copy(alpha = 0.6f)))
    }
}

private data class WizardStep(
    val question: String,
    val field: LifeField?,
    val placeholder: String = "",
    val optional: Boolean = false,
    val singleLine: Boolean = false,
    val hint: String? = null,
    val extra: Extra = Extra.NONE,
) { enum class Extra { NONE, PLAN_CHOICE, MILESTONES, PROJECT_LINKS, GOAL_LINKS, DECISION_LINKS, MOTIVE, METRIC, WOOP, CONFIDENCE } }

private fun stepsFor(kind: LifeKind): List<WizardStep> = when (kind) {
    LifeKind.PROJECT -> listOf(
        WizardStep("Какой проект?", null, "Запустить портфолио", singleLine = true),
        WizardStep("Как поймёшь, что готово?", LifeField.SUCCESS, "Сайт открыт, в нём пять работ"),
        WizardStep("С какого шага начнёшь?", LifeField.NEXT_ACTION, "Набросать структуру за 25 минут", singleLine = true,
            extra = WizardStep.Extra.PLAN_CHOICE),
        WizardStep("Какие этапы по пути?", LifeField.MILESTONES, "Структура\nТексты\nДизайн\nЗапуск", optional = true,
            hint = "Каждая строка — этап. Из них сложится процент", extra = WizardStep.Extra.MILESTONES),
        // Mental contrasting + implementation intention (WOOP), framed as a pre-mortem.
        WizardStep("Представь, что проект встал. Что помешало?", LifeField.OBSTACLE, "Нет времени на неделе", optional = true,
            extra = WizardStep.Extra.WOOP),
        WizardStep("К чему он относится?", null, optional = true, extra = WizardStep.Extra.PROJECT_LINKS),
    )
    LifeKind.GOAL -> listOf(
        WizardStep("Какая цель?", null, "Пробежать полумарафон", singleLine = true),
        WizardStep("Почему это важно именно тебе?", LifeField.WHY, "Хочу снова чувствовать себя сильным", extra = WizardStep.Extra.MOTIVE),
        WizardStep("Как поймёшь, что достиг?", LifeField.SUCCESS, "Финиш 21 км до 1 мая"),
        WizardStep("Какое число покажет движение?", LifeField.METRIC, optional = true, extra = WizardStep.Extra.METRIC),
        WizardStep("Представь: срок прошёл, цель не достигнута. Что помешало?", LifeField.OBSTACLE, "Устану после работы и отложу", optional = true),
        WizardStep("Если это случится — что сделаешь?", LifeField.IF_THEN, "Если пропущу пробежку, выйду на 15 минут вечером", optional = true),
        WizardStep("Сфера и срок", null, optional = true, extra = WizardStep.Extra.GOAL_LINKS),
    )
    LifeKind.DECISION -> listOf(
        WizardStep("Что решаешь?", null, "Менять ли работу этой осенью", singleLine = true),
        WizardStep("Какие есть варианты?", LifeField.OPTIONS, "Остаться · перейти · подождать до весны"),
        WizardStep("Что выбираешь и почему?", LifeField.CHOICE, "Перехожу: рост важнее стабильности сейчас"),
        WizardStep("Чего ожидаешь и к какому сроку?", LifeField.PREDICTION, "Через 3 месяца доход выше, стресс не больше",
            extra = WizardStep.Extra.CONFIDENCE),
        WizardStep("Когда проверить ожидание?", null, extra = WizardStep.Extra.DECISION_LINKS),
    )
    LifeKind.AREA -> listOf(
        WizardStep("Какая сфера жизни?", null, "Здоровье и энергия", singleLine = true),
        WizardStep("Как выглядит «достаточно»?", LifeField.STANDARD, "Сплю 7 часов, двигаюсь 3 раза в неделю", optional = true),
    )
    LifeKind.REVIEW -> emptyList()
}

@Composable
internal fun LifeWizard(state: LifeUiState, viewModel: LifeViewModel, onClose: () -> Unit) {
    val draft = state.draft ?: return
    val steps = remember(draft.kind) { stepsFor(draft.kind) }
    var step by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var planChoice by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var milestonesInput by rememberSaveable(draft.id) { mutableStateOf(draft.text(LifeField.MILESTONES).lines()
        .joinToString("\n") { it.trim().removePrefix("- [ ]").removePrefix("- [x]").trim() }) }
    val tone = kindTone(draft.kind)
    val current = steps[step.coerceIn(0, steps.lastIndex)]
    val last = step >= steps.lastIndex
    val enabled = !state.busy && state.snapshot.connected
    val active = draft.copy(status = LifeStatus.ACTIVE)
    val canActivate = active.activationProblems().isEmpty()
    fun answer(): String = if (current.field == null) draft.title else shownText(current, draft)
    val answered = current.extra == WizardStep.Extra.DECISION_LINKS || current.extra == WizardStep.Extra.PROJECT_LINKS ||
        current.extra == WizardStep.Extra.GOAL_LINKS || answer().any(Char::isLetterOrDigit) ||
        (current.extra == WizardStep.Extra.MILESTONES && milestonesInput.any(Char::isLetterOrDigit))

    fun finish() {
        if (!draft.title.any(Char::isLetterOrDigit)) { step = 0; return }
        val status = when {
            draft.kind == LifeKind.AREA -> LifeStatus.ACTIVE
            canActivate -> LifeStatus.ACTIVE
            else -> LifeStatus.DRAFT
        }
        val plan = when (planChoice) {
            "today" -> LocalDate.now() to DEFAULT_TASK_MINUTES
            "tomorrow" -> LocalDate.now().plusDays(1) to DEFAULT_TASK_MINUTES
            else -> null
        }
        viewModel.saveDraft(status, planAfterSave = plan?.takeIf { status == LifeStatus.ACTIVE })
    }
    fun next() {
        if (current.extra == WizardStep.Extra.MILESTONES) viewModel.updateField(LifeField.MILESTONES, Milestones.fromLines(milestonesInput))
        if (last) finish() else step++
    }
    BackHandler(enabled = step > 0) { step-- }

    val primary = when {
        !last -> "Дальше"
        draft.kind == LifeKind.AREA -> "Добавить сферу"
        !canActivate -> "Сохранить как идею"
        draft.kind == LifeKind.PROJECT -> "Запустить проект"
        draft.kind == LifeKind.GOAL -> "Поставить цель"
        else -> "Принять решение"
    }
    StepScaffold(
        steps = steps.size, step = step, accent = tone.accent, onClose = onClose,
        primaryLabel = primary,
        primaryEnabled = enabled && (current.optional || answered || last && draft.title.any(Char::isLetterOrDigit)),
        onPrimary = ::next,
        onSkip = if (current.optional && !last) ({ step++ }) else null,
        canGoBack = step > 0,
        onBack = { step-- },
    ) {
        AnimatedContent(
            step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(140)))
            },
            label = "wizardStep",
        ) { index ->
            val shown = steps[index.coerceIn(0, steps.lastIndex)]
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (index == 0) NewKindBadge(draft.kind)
                when (shown.extra) {
                    WizardStep.Extra.MILESTONES -> QuestionField(shown.question, milestonesInput, { milestonesInput = it }, shown.placeholder, tone.accent, hint = shown.hint)
                    WizardStep.Extra.PROJECT_LINKS, WizardStep.Extra.GOAL_LINKS, WizardStep.Extra.DECISION_LINKS -> {
                        Text(shown.question, style = LifeType.question)
                        LinkChoices(shown.extra, draft, state, viewModel)
                    }
                    WizardStep.Extra.METRIC -> MetricStep(shown.question, draft, tone.accent) { viewModel.updateField(LifeField.METRIC, it) }
                    else -> QuestionField(
                        shown.question,
                        if (shown.field == null) draft.title else shownText(shown, draft),
                        { value -> if (shown.field == null) viewModel.updateDraft { it.copy(title = value) } else viewModel.updateField(shown.field, storedText(shown, value, draft)) },
                        shown.placeholder, tone.accent, singleLine = shown.singleLine, hint = shown.hint,
                        onNext = { if (answered || shown.optional) next() },
                    )
                }
                when (shown.extra) {
                    WizardStep.Extra.MOTIVE -> MotiveChoice(draft, tone.accent, viewModel)
                    WizardStep.Extra.CONFIDENCE -> ConfidenceChoice(draft, tone.accent, viewModel)
                    WizardStep.Extra.WOOP -> QuestionField("Если это случится — что сделаешь?", draft.text(LifeField.IF_THEN),
                        { viewModel.updateField(LifeField.IF_THEN, it) }, "Если неделя забита — сделаю 20 минут в субботу утром", tone.accent, focus = false)
                    else -> Unit
                }
                if (shown.extra == WizardStep.Extra.PLAN_CHOICE) PlanChoice(planChoice, tone.accent) { planChoice = if (planChoice == it) null else it }
                if (index == steps.lastIndex && !canActivate && draft.kind != LifeKind.AREA) {
                    Text("Чтобы запустить: ${active.activationProblems().joinToString(", ") { it.replaceFirstChar(Char::lowercase) }}",
                        style = LifeType.muted)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanChoice(selected: String?, accent: Color, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChip("Сделаю сегодня", selected == "today", { onPick("today") }, accent)
        ChoiceChip("Завтра", selected == "tomorrow", { onPick("tomorrow") }, accent)
    }
}

/** Links are chips of what already exists: pick in one tap, no search dialog for a handful of items. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkChoices(extra: WizardStep.Extra, draft: LifeEntry, state: LifeUiState, viewModel: LifeViewModel) {
    val context = LocalContext.current
    val entries = state.snapshot.entries.filter { !it.archived }
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (extra == WizardStep.Extra.PROJECT_LINKS) {
            val goals = entries.filter { it.kind == LifeKind.GOAL && it.status != LifeStatus.DONE }
            if (goals.isNotEmpty()) ChipGroup("Цель") {
                goals.forEach { goal ->
                    ChoiceChip(goal.title, draft.goalId == goal.id, {
                        viewModel.updateDraft { it.copy(goalId = if (it.goalId == goal.id) null else goal.id, areaId = if (it.goalId == goal.id) it.areaId else goal.areaId) }
                    }, kindTone(LifeKind.GOAL).accent, kindTone(LifeKind.GOAL).icon)
                }
            }
        }
        if (extra == WizardStep.Extra.DECISION_LINKS) {
            ChipGroup(null) {
                listOf(7L to "Через неделю", 14L to "Через 2 недели", 30L to "Через месяц", 90L to "Через 3 месяца").forEach { (days, label) ->
                    ChoiceChip(label, draft.reviewDate == today.plusDays(days), { viewModel.updateDraft { it.copy(reviewDate = today.plusDays(days)) } },
                        kindTone(LifeKind.DECISION).accent)
                }
                val custom = draft.reviewDate?.takeIf { d -> listOf(7L, 14L, 30L, 90L).none { today.plusDays(it) == d } }
                ChoiceChip(custom?.let(::relativeDay) ?: "Дата", custom != null, {
                    val initial = draft.reviewDate ?: today.plusDays(7)
                    DatePickerDialog(context, { _, y, m, d -> viewModel.updateDraft { it.copy(reviewDate = LocalDate.of(y, m + 1, d)) } },
                        initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                }, kindTone(LifeKind.DECISION).accent, Icons.Rounded.CalendarMonth)
            }
            val projects = entries.filter { it.kind == LifeKind.PROJECT && it.status in listOf(LifeStatus.ACTIVE, LifeStatus.DRAFT) }
            if (projects.isNotEmpty()) ChipGroup("Проект") {
                projects.forEach { project ->
                    ChoiceChip(project.title, draft.projectId == project.id, {
                        viewModel.updateDraft {
                            if (it.projectId == project.id) it.copy(projectId = null)
                            else it.copy(projectId = project.id, goalId = project.goalId, areaId = project.areaId)
                        }
                    }, kindTone(LifeKind.PROJECT).accent, kindTone(LifeKind.PROJECT).icon)
                }
            }
        }
        val areas = entries.filter { it.kind == LifeKind.AREA }
        if (areas.isNotEmpty() && extra != WizardStep.Extra.DECISION_LINKS) ChipGroup("Сфера") {
            areas.forEach { area ->
                ChoiceChip(area.title, draft.areaId == area.id, {
                    viewModel.updateDraft { current ->
                        val keep = current.areaId != area.id
                        val goal = entries.firstOrNull { it.id == current.goalId }
                        current.copy(areaId = if (keep) area.id else null, goalId = current.goalId.takeIf { keep && goal?.areaId == area.id })
                    }
                }, kindTone(LifeKind.AREA).accent, kindTone(LifeKind.AREA).icon)
            }
        }
        if (extra == WizardStep.Extra.GOAL_LINKS) ChipGroup("Срок") {
            listOf(30L to "Месяц", 91L to "3 месяца", 182L to "Полгода", 365L to "Год").forEach { (days, label) ->
                ChoiceChip(label, draft.dueDate == today.plusDays(days), {
                    viewModel.updateDraft { it.copy(dueDate = if (it.dueDate == today.plusDays(days)) null else today.plusDays(days)) }
                }, kindTone(LifeKind.GOAL).accent)
            }
            val custom = draft.dueDate?.takeIf { d -> listOf(30L, 91L, 182L, 365L).none { today.plusDays(it) == d } }
            ChoiceChip(custom?.let { relativeDay(it) } ?: "Дата", custom != null, {
                val initial = draft.dueDate ?: today.plusMonths(3)
                DatePickerDialog(context, { _, y, m, d -> viewModel.updateDraft { it.copy(dueDate = LocalDate.of(y, m + 1, d)) } },
                    initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
            }, kindTone(LifeKind.GOAL).accent, Icons.Rounded.CalendarMonth)
        }
        if (extra == WizardStep.Extra.PROJECT_LINKS && areas.isEmpty() && entries.none { it.kind == LifeKind.GOAL })
            Text("Пока не с чем связать — это можно сделать позже", style = LifeType.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipGroup(label: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        label?.let { Text(it.uppercase(), style = LifeType.section) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun NewKindBadge(kind: LifeKind) {
    val tone = kindTone(kind)
    Row(
        Modifier.clip(CircleShape).background(tone.container).padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tone.icon, null, tint = tone.onContainer, modifier = Modifier.size(14.dp))
        Text(when (kind) {
            LifeKind.GOAL -> "Новая цель"
            LifeKind.AREA -> "Новая сфера"
            LifeKind.DECISION -> "Новое решение"
            else -> "Новый ${kindShort(kind).lowercase()}"
        }, style = MaterialTheme.typography.labelMedium, color = tone.onContainer)
    }
}

/** Motive and confidence lines live inside their fields as readable Markdown; the input shows only the prose. */
private fun shownText(step: WizardStep, draft: LifeEntry): String {
    val field = step.field ?: return draft.title
    val text = draft.text(field)
    return when (step.extra) {
        WizardStep.Extra.MOTIVE -> GoalMotive.strip(text)
        WizardStep.Extra.CONFIDENCE -> Calibration.stripConfidence(text)
        else -> text
    }
}

private fun storedText(step: WizardStep, value: String, draft: LifeEntry): String {
    val field = step.field ?: return value
    val old = draft.text(field)
    return when (step.extra) {
        WizardStep.Extra.MOTIVE -> GoalMotive.with(value, GoalMotive.of(old))
        WizardStep.Extra.CONFIDENCE -> Calibration.withConfidence(value, Calibration.confidence(old))
        else -> value
    }
}

/** Autonomous vs controlled reasons (Sheldon & Elliot 1999). Honest, not judged — only a nudge. */
@Composable
private fun MotiveChoice(draft: LifeEntry, accent: Color, viewModel: LifeViewModel) {
    val why = draft.text(LifeField.WHY)
    val motive = GoalMotive.of(why)
    ChipGroup("Скорее потому что") {
        GoalMotive.entries.forEach { option ->
            ChoiceChip(option.label, motive == option, {
                viewModel.updateField(LifeField.WHY, GoalMotive.with(GoalMotive.strip(why), if (motive == option) null else option))
            }, accent)
        }
    }
    if (motive?.autonomous == false) Text("Цели на «надо» обычно держатся хуже. Что здесь важно лично тебе — или её стоит отпустить?",
        style = LifeType.muted)
}

/** A probability written before the outcome makes later calibration possible (Mellers et al. 2014). */
@Composable
private fun ConfidenceChoice(draft: LifeEntry, accent: Color, viewModel: LifeViewModel) {
    val prediction = draft.text(LifeField.PREDICTION)
    val confidence = Calibration.confidence(prediction)
    ChipGroup("Насколько уверен, что так и будет") {
        listOf(50, 60, 70, 80, 90).forEach { value ->
            ChoiceChip("$value%", confidence == value, {
                viewModel.updateField(LifeField.PREDICTION, Calibration.withConfidence(Calibration.stripConfidence(prediction), if (confidence == value) null else value))
            }, accent)
        }
    }
}

/** Target, unit and a starting point — the scale the ring and sparkline are drawn on. */
@Composable
private fun MetricStep(question: String, draft: LifeEntry, accent: Color, onChange: (String) -> Unit) {
    val existing = Metrics.parse(draft.text(LifeField.METRIC))
    var target by rememberSaveable(draft.id) { mutableStateOf(existing?.target?.let(Metrics::format).orEmpty()) }
    var unit by rememberSaveable(draft.id) { mutableStateOf(existing?.unit.orEmpty()) }
    var start by rememberSaveable(draft.id) { mutableStateOf(existing?.start?.let(Metrics::format).orEmpty()) }
    fun commit(t: String = target, u: String = unit, s: String = start) {
        val number = Metrics.number(t)
        onChange(if (number == null) "" else Metrics.create(number, u, Metrics.number(s)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(question, style = LifeType.question)
        Text("Например 21 км, 80 кг, 10 000 подписчиков. Прогресс будет видно по замерам", style = LifeType.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NumberBox("Цель", target, accent, Modifier.weight(1f)) { target = it; commit(t = it) }
            NumberBox("Единица", unit, accent, Modifier.weight(1f), numeric = false) { unit = it; commit(u = it) }
        }
        NumberBox("Сейчас — необязательно", start, accent, Modifier.fillMaxWidth()) { start = it; commit(s = it) }
    }
}

@Composable
private fun NumberBox(label: String, value: String, accent: Color, modifier: Modifier, numeric: Boolean = true, onValue: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label.uppercase(), style = LifeType.section)
        BasicTextField(value, { v -> onValue(if (numeric) v.filter { it.isDigit() || it in ".,- " } else v.replace('\n', ' ').take(20)) },
            textStyle = LifeType.input, cursorBrush = SolidColor(accent), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text),
            modifier = Modifier.fillMaxWidth())
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(if (value.isEmpty()) MaterialTheme.colorScheme.surfaceContainerHighest else accent.copy(alpha = 0.6f)))
    }
}

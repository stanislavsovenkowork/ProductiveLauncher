package dev.stanislav.focusdeck.ui.ritual

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.stanislav.focusdeck.ui.life.AnimatedCount
import dev.stanislav.focusdeck.ui.life.ChoiceChip
import dev.stanislav.focusdeck.ui.life.LifeCard
import dev.stanislav.focusdeck.ui.life.LifeType
import dev.stanislav.focusdeck.ui.life.ProgressRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.vault.VaultTask
import dev.stanislav.focusdeck.ui.components.*
import dev.stanislav.focusdeck.ui.planning.formatMinuteOfDay
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
private val weekdayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))

private enum class RitualStep { YESTERDAY, SUMMARY, TAILS, JOURNAL, FOOD, PLAN }
private enum class RitualSheet { RULES, HELP }

/** A next step of an active project, offered as a one-tap addition to tomorrow's plan. */
data class RitualProjectStep(val entryId: String, val project: String, val title: String)

/** Facts the ritual shows but does not own: focus time and goal steps live elsewhere. */
data class RitualInsights(
    val focusMinutes: Int? = null,
    val goalSteps: Int = 0,
    val projectSteps: List<RitualProjectStep> = emptyList(),
)

private fun RitualStep.label(evening: Boolean) = when (this) {
    RitualStep.YESTERDAY -> "Вчера"
    RitualStep.SUMMARY -> "Итог"
    RitualStep.TAILS -> "Хвосты"
    RitualStep.JOURNAL -> "Дневник"
    RitualStep.FOOD -> "Еда"
    RitualStep.PLAN -> if (evening) "Завтра" else "План"
}

/**
 * Morning and evening as a short guided sequence, one question per screen.
 *
 * Evening: what the day gave (no typing) → close the loose ends → write → food → shape tomorrow.
 * Morning: yesterday's lesson and today's main thing → write → confirm the plan.
 * Thresholds and what gets written to the note are unchanged; only the path is new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RitualScreen(
    state: RitualUiState,
    onOpenPlan: () -> Unit,
    onOpenVault: () -> Unit,
    onChoosePriority: (String) -> Unit,
    onCarryTask: (VaultTask) -> Unit,
    onJournalChange: (String) -> Unit,
    onIfThenChange: (String) -> Unit,
    onOpenFood: () -> Unit,
    onConfirmFood: () -> Unit,
    onFinish: (journal: String, ifThen: String) -> Unit,
    onEmergency: () -> Unit,
    onExitRitual: () -> Unit,
    onClosePreview: () -> Unit,
    restrictionStatus: String,
    onPinScreen: () -> Unit,
    onOpenDraftConflict: () -> Unit = {},
    onResolveDraftConflict: (keepLocal: Boolean) -> Unit = {},
    onDismissDraftConflict: () -> Unit = {},
    canPinScreen: Boolean = true,
    insights: RitualInsights = RitualInsights(),
    onLetGo: (VaultTask) -> Unit = {},
    onAddProjectStep: (RitualProjectStep) -> Unit = {},
) {
    val evening = state.window.phase == RitualPhase.EVENING
    val plan = state.progress?.plan
    val steps = buildList {
        if (evening) {
            add(RitualStep.SUMMARY)
            if (state.reviewedTasks.isNotEmpty()) add(RitualStep.TAILS)
            add(RitualStep.JOURNAL); add(RitualStep.FOOD)
        } else {
            if (state.yesterday.isNotBlank() || plan?.priority != null) add(RitualStep.YESTERDAY)
            add(RitualStep.JOURNAL)
        }
        add(RitualStep.PLAN)
    }
    // Null means this window has neither a restored choice nor a completed initial
    // selection. Keep that distinction across loading, refresh and saved-state restore.
    var selectedStep by rememberSaveable(state.window.toString()) { mutableStateOf<RitualStep?>(null) }
    var sheet by remember(state.window) { mutableStateOf<RitualSheet?>(null) }
    var recoveryExpanded by rememberSaveable(state.window.toString()) { mutableStateOf(state.ifThen.isNotBlank()) }
    val focusManager = LocalFocusManager.current
    val draftConflict = state.draftConflict
    LaunchedEffect(draftConflict?.key) {
        if (draftConflict != null) {
            focusManager.clearFocus()
            sheet = null
        }
    }
    val journal = remember(state.journal) { journalReadiness(state.journal) }
    // Read readiness from the engine: confirmation belongs to these exact meal contents.
    val foodReady = state.progress?.nutritionReady == true
    val canConfirmFood = state.meals.isNotEmpty() && state.meals.all {
        it.dayEpochDay == state.window.journalDate.toEpochDay() && meaningfulTitle(it.name) && it.calories >= 0
    }
    fun ready(item: RitualStep) = when (item) {
        RitualStep.JOURNAL -> journal.isReady
        RitualStep.FOOD -> foodReady
        RitualStep.PLAN -> plan?.isReady == true
        else -> true
    }
    val required = steps.filter { it == RitualStep.JOURNAL || it == RitualStep.FOOD || it == RitualStep.PLAN }
    val allReady = required.all(::ready)
    val missingStep = required.firstOrNull { !ready(it) }
    val loaded = !state.loading && state.progress?.window == state.window
    // A fresh ritual starts at the beginning; one already under way resumes where work is missing.
    val initialStep = when {
        !loaded -> steps.first()
        journal.words == 0 -> steps.first()
        else -> missingStep ?: steps.last()
    }
    val step = selectedStep?.takeIf { it in steps } ?: initialStep
    val index = steps.indexOf(step)
    val last = step == steps.last()
    LaunchedEffect(state.window, loaded) {
        if (selectedStep == null && loaded) selectedStep = initialStep
    }
    fun selectStep(item: RitualStep) {
        focusManager.clearFocus()
        selectedStep = item
    }
    fun showSheet(item: RitualSheet) {
        focusManager.clearFocus()
        sheet = item
    }

    val foodNeedsConfirmation = step == RitualStep.FOOD && !foodReady
    val goBackToMissing = last && missingStep != null && missingStep != RitualStep.PLAN
    val actionLabel = when {
        state.busy -> "Сохраняю…"
        foodNeedsConfirmation && !canConfirmFood -> "Добавить еду"
        foodNeedsConfirmation -> "Всё верно"
        !last -> steps[index + 1].label(evening)
        goBackToMissing -> missingStep!!.label(evening)
        evening -> "Закрыть день"
        else -> "Начать день"
    }
    val advance = {
        focusManager.clearFocus()
        when {
            foodNeedsConfirmation && !canConfirmFood -> onOpenFood()
            foodNeedsConfirmation -> onConfirmFood()
            !last -> selectStep(steps[index + 1])
            goBackToMissing -> missingStep?.let(::selectStep)
            allReady -> onFinish(state.journal, state.ifThen)
        }
        Unit
    }
    val tails = state.reviewedTasks
    val decided = tails.count { it.title in state.carriedTitles || it.title in state.letGo }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        // One 48 dp row: where you are and the way out. It never grows with the keyboard.
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.preview && !state.mandatory) {
                RitualIconButton(Icons.Filled.Close, "Закрыть ритуал") { focusManager.clearFocus(); onClosePreview() }
            } else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(if (evening) Icons.Filled.NightsStay else Icons.Filled.WbSunny,
                    if (evening) "Завершение дня" else "Начало дня", Modifier.size(18.dp),
                    tint = LocalFocusDeckColors.current.attention)
            }
            Column(Modifier.weight(1f)) {
                Text(step.label(evening), style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    modifier = Modifier.semantics { heading() })
                Text(state.window.journalDate.format(weekdayFormat), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (state.mandatory) TextButton(onClick = { showSheet(RitualSheet.HELP) }) { Text("Выход") }
            RitualIconButton(Icons.Filled.Info, "Помощь и срочный доступ") { showSheet(RitualSheet.HELP) }
        }
        // The steps as a segmented track: done segments fill, the current one glows; each is a tap target.
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            steps.forEach { item ->
                StepSegment(item.label(evening), selected = item == step,
                    done = if (item in required) ready(item) else steps.indexOf(item) < index, Modifier.weight(1f)) { selectStep(item) }
            }
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = steps.indexOf(targetState) >= steps.indexOf(initialState)
                (slideInHorizontally(tween(260)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(140))) using null
            },
            label = "ritualStep",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { shown ->
            when (shown) {
                RitualStep.JOURNAL -> JournalStep(state, evening, step == RitualStep.JOURNAL) { value ->
                    if (selectedStep == null) selectedStep = RitualStep.JOURNAL
                    onJournalChange(value)
                }
                RitualStep.SUMMARY -> StepColumn { SummaryStep(state, insights) }
                RitualStep.TAILS -> StepColumn { TailsStep(state, onCarryTask, onLetGo) }
                RitualStep.YESTERDAY -> StepColumn { YesterdayStep(state) }
                RitualStep.FOOD -> StepColumn { FoodStep(state, foodReady) { focusManager.clearFocus(); onOpenFood() } }
                RitualStep.PLAN -> StepColumn {
                    PlanStep(
                        state = state,
                        evening = evening,
                        insights = insights,
                        recoveryExpanded = recoveryExpanded,
                        onToggleRecovery = { if (recoveryExpanded) focusManager.clearFocus(); recoveryExpanded = !recoveryExpanded },
                        onOpenPlan = { focusManager.clearFocus(); onOpenPlan() },
                        onChoosePriority = onChoosePriority,
                        onIfThenChange = { if (selectedStep == null) selectedStep = RitualStep.PLAN; onIfThenChange(it) },
                        onAddProjectStep = onAddProjectStep,
                    )
                }
            }
        }

        if (!state.vaultConnected || state.error != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error ?: "Подключи Obsidian", Modifier.weight(1f), maxLines = 2,
                    style = MaterialTheme.typography.bodySmall, color = LocalFocusDeckColors.current.attention)
                TextButton(onClick = { focusManager.clearFocus(); if (state.vaultConnected) onOpenDraftConflict() else onOpenVault() }, enabled = !state.busy) {
                    Text(if (state.vaultConnected) "Сравнить" else "Подключить")
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).clip(FocusRadius.field)
                .clickable { showSheet(if (step == RitualStep.JOURNAL) RitualSheet.RULES else RitualSheet.HELP) }
                .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fraction = when (step) {
                    RitualStep.JOURNAL -> journal.fraction
                    RitualStep.FOOD -> if (foodReady) 1f else 0f
                    RitualStep.PLAN -> if (plan?.isReady == true) 1f
                        else ((plan?.plannedMinutes ?: 0).toFloat() / PLAN_REQUIRED_MINUTES).coerceIn(0f, 0.97f)
                    RitualStep.TAILS -> if (tails.isEmpty()) 1f else decided.toFloat() / tails.size
                    else -> null
                }
                if (fraction != null) ProgressRing(fraction, if (fraction >= 1f) MaterialTheme.colorScheme.primary
                    else LocalFocusDeckColors.current.attention, size = 22.dp, stroke = 3.dp) {
                    if (fraction >= 1f) Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(when (step) {
                    RitualStep.JOURNAL -> if (journal.isReady) "Запись готова"
                        else if (journal.words < JOURNAL_MIN_WORDS) "${journal.words} из $JOURNAL_MIN_WORDS слов" else journalHint(journal)
                    RitualStep.FOOD -> if (foodReady) "Проверено" else "${state.meals.size} ${mealsWord(state.meals.size)}"
                    RitualStep.PLAN -> if (plan?.isReady == true) "План готов" else if ((plan?.remainingMinutes ?: PLAN_REQUIRED_MINUTES) > 0)
                        "Ещё ${duration(plan?.remainingMinutes ?: PLAN_REQUIRED_MINUTES)}" else "Выбери главное"
                    RitualStep.TAILS -> "Решено $decided из ${tails.size}"
                    else -> ""
                }, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FocusButton(
                text = actionLabel,
                onClick = advance,
                icon = if (last && allReady) Icons.Filled.Check else Icons.AutoMirrored.Filled.ArrowForward,
                enabled = !state.busy && (!last || goBackToMissing || allReady),
            )
        }
    }


    if (draftConflict != null) key(draftConflict.key) {
        ModalBottomSheet(
            onDismissRequest = onDismissDraftConflict,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Сравнить записи", Modifier.weight(1f).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge)
                        RitualIconButton(Icons.Filled.Close, "Закрыть и оставить черновик", onDismissDraftConflict)
                    }
                    SupportingText("${when (draftConflict.field) {
                        "journal" -> "Дневник"
                        "ifthen" -> "Если отвлекусь, то…"
                        else -> "Запись"
                    }} · ${draftConflict.day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru")))}")
                    SupportingText("В Obsidian и на устройстве разные версии. Выбери, какой текст оставить. Любую версию можно выделить и скопировать.")
                    RitualConflictText("Мой текст", draftConflict.localText)
                    RitualConflictText("Текст из Obsidian", draftConflict.remoteText)
                    SupportingText("«Сохранить мой текст» заменит текст в Obsidian, только если версия перед записью останется такой, как показано здесь. Если она изменится снова, потребуется новое сравнение.")
                    SupportingText("«Взять из Obsidian» заменит местный черновик показанным текстом из заметки. Закрытие этого окна оставит черновик без изменений.")
                    state.error?.let { SupportingText(it) }
                }
                RitualButton("Сохранить мой текст", { onResolveDraftConflict(true) },
                    modifier = Modifier.fillMaxWidth(), enabled = !state.busy)
                RitualButton("Взять из Obsidian", { onResolveDraftConflict(false) },
                    modifier = Modifier.fillMaxWidth(), style = FocusButtonStyle.Tonal, enabled = !state.busy)
                QuietAction("Решить позже", onDismissDraftConflict)
            }
        }
    }

    sheet?.takeIf { draftConflict == null }?.let { currentSheet ->
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = FocusRadius.card,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (currentSheet) { RitualSheet.RULES -> "Объём записи"; RitualSheet.HELP -> "Помощь с ритуалом" },
                        Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    RitualIconButton(Icons.Filled.Close, "Закрыть") { sheet = null }
                }
                if (currentSheet == RitualSheet.RULES) {
                    FocusCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            RequirementRow("Слова", "${journal.words} / $JOURNAL_MIN_WORDS", journal.words >= JOURNAL_MIN_WORDS)
                            RequirementRow("Буквы и цифры", "${journal.characters} / $JOURNAL_MIN_CHARACTERS", journal.characters >= JOURNAL_MIN_CHARACTERS)
                            RequirementRow("Разные слова", "${journal.distinctWords} / 20", journal.distinctWords >= 20)
                        }
                    }
                    SupportingText("Нужны все три условия. Слова считаются от двух букв; пробелы и знаки препинания не увеличивают объём записи.")
                    SupportingText("Запись сохраняется автоматически. При подключённой папке она попадёт в дневник Obsidian.")
                } else {
                    if (state.mandatory) {
                        FocusCard {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Срочно нужен телефон?", style = MaterialTheme.typography.titleMedium)
                                SupportingText(if (state.emergencyAvailable)
                                    "Доступ на 15 минут, один раз за ритуал. После паузы при возвращении в FocusDeck снова откроется незавершённый ритуал."
                                    else "15 минут срочного доступа для этого ритуала уже использованы.")
                                if (state.emergencyAvailable) RitualButton(
                                    "Открыть доступ на 15 минут",
                                    { sheet = null; onEmergency() },
                                    Modifier.fillMaxWidth(),
                                    style = FocusButtonStyle.Tonal,
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                    if (state.mandatory) FocusCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Остановить ритуал", style = MaterialTheme.typography.titleMedium)
                            SupportingText("Ритуал останется незавершённым, записи и задачи сохранятся.")
                            RitualButton("Остановить на это окно", { sheet = null; onExitRitual() },
                                Modifier.fillMaxWidth(), style = FocusButtonStyle.Outlined, enabled = !state.busy)
                        }
                    }
                    FocusCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Что нужно для завершения", style = MaterialTheme.typography.titleMedium)
                            SupportingText("Дневник: $JOURNAL_MIN_WORDS слов, $JOURNAL_MIN_CHARACTERS букв и цифр, минимум 20 разных слов.")
                            SupportingText("План: минимум 60% времени с 08:00 до 23:00 — 9 часов — и одно главное дело. Отдых, еда и дорога тоже считаются. Пересечения учитываются один раз.")
                            if (evening) SupportingText("Питание: все приёмы пищи внесены и подтверждены. Подтверждение относится к точным записям и сбрасывается при изменениях.")
                            QuietAction("Проверить объём записи") { sheet = RitualSheet.RULES }
                        }
                    }
                    if (state.mandatory) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Закрепление экрана", style = MaterialTheme.typography.titleSmall)
                            SupportingText(restrictionStatus)
                            if (canPinScreen) ActionRow("Системное закрепление", "Включить", !state.busy) { sheet = null; onPinScreen() }
                        }
                    }
                    ActionRow("Папка Obsidian", "Открыть", true) { sheet = null; onOpenVault() }
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun RitualConflictText(label: String, text: String) {
    FocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            if (text.isEmpty()) SupportingText("Пустая запись")
            else SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private fun journalHint(journal: JournalReadiness): String = when {
    journal.words < JOURNAL_MIN_WORDS -> "Минимум $JOURNAL_MIN_WORDS слов"
    journal.characters < JOURNAL_MIN_CHARACTERS -> "Ещё ${JOURNAL_MIN_CHARACTERS - journal.characters} букв и цифр"
    journal.distinctWords < 20 -> "Добавь разные слова: ${journal.distinctWords} из 20"
    else -> "Запись готова"
}

private fun duration(minutes: Int): String = when {
    minutes % 60 == 0 -> "${minutes / 60} ч"
    minutes < 60 -> "$minutes мин"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

@Composable
private fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Reflow supporting values below their labels instead of shrinking the text. */
@Composable
private fun RitualDetailPair(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        if (availableWidth / LocalDensity.current.fontScale < 280.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                leading()
                trailing()
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { leading() }
                Box(Modifier.widthIn(max = availableWidth / 2)) { trailing() }
            }
        }
    }
}

/** Keep FocusButton's colors, shape and interaction; allow its label to grow. */
@Composable
private fun RitualButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: FocusButtonStyle = FocusButtonStyle.Filled,
    enabled: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = MaterialTheme.typography.labelLarge
    BoxWithConstraints(modifier) {
        val textWidth = with(density) { (maxWidth - 40.dp).roundToPx().coerceAtLeast(1) }
        val measured = measurer.measure(AnnotatedString(text), style = textStyle,
            constraints = Constraints(maxWidth = textWidth))
        val minimumHeight = maxOf(48.dp, with(density) { measured.size.height.toDp() } + 24.dp)
        FocusButton(text, onClick,
            Modifier.fillMaxWidth().heightIn(min = minimumHeight).semantics { role = Role.Button },
            style = style, enabled = enabled)
    }
}

@Composable
private fun RitualIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(FocusRadius.pill).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietAction(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clip(FocusRadius.field).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun PriorityRow(task: VaultTask, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    RitualDetailPair(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(FocusRadius.field)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        leading = { Text(task.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(task.startMinuteOfDay?.let(::formatMinuteOfDay) ?: "—", style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun RequirementRow(label: String, value: String, ready: Boolean) {
    RitualDetailPair(
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = if (ready) "Готово" else "Недостаточно" },
        leading = { SupportingText(label) },
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.labelLarge,
                    color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (ready) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun ActionRow(title: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    RitualDetailPair(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(FocusRadius.field)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        leading = { Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) },
        trailing = { Text(action, style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun StepColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun StepSegment(label: String, selected: Boolean, done: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val color by animateColorAsState(when {
        selected -> LocalFocusDeckColors.current.attention
        done -> primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }, tween(260), label = "segment")
    Box(modifier.height(20.dp).selectable(selected, role = Role.Tab, onClick = onClick)
        .semantics { contentDescription = label; stateDescription = if (done) "Готово" else "Не завершено" },
        contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(FocusRadius.pill).background(color))
    }
}

@Composable
private fun JournalStep(state: RitualUiState, evening: Boolean, active: Boolean, onChange: (String) -> Unit) {
    // The editor receives the entire remaining viewport; the questions are a placeholder, never text.
    val prompts = if (evening) "Что сегодня получилось?\nЧто понял — о деле, о себе?\nЧто завтра сделаю иначе?"
        else "Как самочувствие?\nЧто сегодня главное и почему?\nЧто может помешать — и что тогда сделаю?"
    BasicTextField(
        value = state.journal,
        onValueChange = onChange,
        readOnly = state.busy || !active,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        singleLine = false,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
        modifier = Modifier.fillMaxSize().padding(vertical = 16.dp).semantics {
            contentDescription = if (evening) "Вечерняя запись в дневнике" else "Утренняя запись в дневнике"
        },
        decorationBox = { inner ->
            Box {
                if (state.journal.isEmpty()) Text(prompts, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                inner()
            }
        },
    )
}

/** The day's result before any question: progress made visible is what keeps a practice alive. */
@Composable
private fun SummaryStep(state: RitualUiState, insights: RitualInsights) {
    val planned = state.plannedToday
    val done = state.doneToday
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProgressRing(if (planned > 0) done.toFloat() / planned else if (state.doneTasks.isNotEmpty()) 1f else 0f,
            MaterialTheme.colorScheme.primary, size = 132.dp, stroke = 11.dp) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCount(if (planned > 0) done else state.doneTasks.size, LifeType.number.copy(fontSize = 40.sp))
                if (planned > 0) Text(" / $planned", style = LifeType.number.copy(fontSize = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
        Text(if (planned > 0) "дел по плану" else "дел отмечено", style = LifeType.muted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryTile(insights.focusMinutes?.takeIf { it > 0 }?.let(::duration) ?: "—", "фокус", Modifier.weight(1f))
        SummaryTile(insights.goalSteps.toString(), "шаги к целям", Modifier.weight(1f), highlight = insights.goalSteps > 0)
        SummaryTile(state.unplannedDoneToday.toString(), "вне плана", Modifier.weight(1f))
    }
    if (state.doneTasks.isEmpty()) {
        Text("Отметок нет. Бывает — разберём, что перенести, и соберём завтра.", style = LifeType.muted)
    } else LifeCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ПОЛУЧИЛОСЬ", style = LifeType.section)
            state.doneTasks.take(6).forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(task.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                }
            }
            if (state.doneTasks.size > 6) Text("и ещё ${state.doneTasks.size - 6}", style = LifeType.muted)
        }
    }
}

@Composable
private fun SummaryTile(value: String, label: String, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier.clip(FocusRadius.block).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = LifeType.number.copy(fontSize = 20.sp),
            color = if (highlight) LocalFocusDeckColors.current.attention else MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Every open loop gets a decision — carried or let go — so it stops pulling at attention tonight. */
@Composable
private fun TailsStep(state: RitualUiState, onCarry: (VaultTask) -> Unit, onLetGo: (VaultTask) -> Unit) {
    Text("Не успел — куда?", style = LifeType.title)
    state.reviewedTasks.forEach { task ->
        val carried = task.title in state.carriedTitles
        val gone = task.title in state.letGo && !carried
        val settled = carried || gone
        val alpha by animateFloatAsState(if (settled) 0.6f else 1f, tween(240), label = "tail")
        LifeCard(Modifier.graphicsLayer { this.alpha = alpha }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    task.startMinuteOfDay?.let { Text(formatMinuteOfDay(it), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(if (carried) "На завтра" else "На завтра", carried,
                        { if (!settled && !state.busy) onCarry(task) }, icon = if (carried) Icons.Filled.Check else null)
                    ChoiceChip(if (gone) "Отпущено" else "Отпустить", gone,
                        { if (!settled && !state.busy) onLetGo(task) },
                        accent = MaterialTheme.colorScheme.onSurfaceVariant, icon = if (gone) Icons.Filled.Check else null)
                }
            }
        }
    }
}

@Composable
private fun YesterdayStep(state: RitualUiState) {
    val priority = state.progress?.plan?.priority
    val priorityTask = state.planTasks.firstOrNull { it.title == priority }
    if (priority != null) LifeCard(tint = MaterialTheme.colorScheme.secondaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ГЛАВНОЕ СЕГОДНЯ", style = LifeType.section)
            Text(priority, style = LifeType.hero.copy(fontSize = 24.sp))
            priorityTask?.startMinuteOfDay?.let { Text("в ${formatMinuteOfDay(it)}", style = LifeType.muted) }
            state.ifThen.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (state.yesterday.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ВЧЕРА ТЫ ЗАПИСАЛ", style = LifeType.section, modifier = Modifier.padding(start = 4.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(FocusRadius.pill).background(LocalFocusDeckColors.current.attention))
            Text(state.yesterday, style = LifeType.quote, maxLines = 12, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun FoodStep(state: RitualUiState, foodReady: Boolean, onOpenFood: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedCount(state.meals.sumOf { it.calories }, LifeType.number.copy(fontSize = 36.sp),
                    if (foodReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(" ккал", style = LifeType.muted, modifier = Modifier.padding(bottom = 6.dp))
            }
            Text(state.window.journalDate.format(dateFormat), style = LifeType.muted)
        }
        FocusButton(if (state.meals.isEmpty()) "Добавить" else "Изменить", onOpenFood,
            style = FocusButtonStyle.Tonal, enabled = !state.busy)
    }
    if (state.meals.isEmpty()) Text("Внеси всё, что съел за день, затем подтверди список.", style = LifeType.muted)
    else LifeCard {
        Column {
            state.meals.forEach { meal ->
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(meal.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("${meal.calories}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanStep(
    state: RitualUiState,
    evening: Boolean,
    insights: RitualInsights,
    recoveryExpanded: Boolean,
    onToggleRecovery: () -> Unit,
    onOpenPlan: () -> Unit,
    onChoosePriority: (String) -> Unit,
    onIfThenChange: (String) -> Unit,
    onAddProjectStep: (RitualProjectStep) -> Unit,
) {
    val plan = state.progress?.plan
    val minutes = plan?.plannedMinutes ?: 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ProgressRing((minutes.toFloat() / PLAN_REQUIRED_MINUTES).coerceIn(0f, 1f),
            if (plan?.isReady == true) MaterialTheme.colorScheme.primary else LocalFocusDeckColors.current.attention,
            size = 60.dp, stroke = 6.dp) {
            Text("${minutes * 100 / PLAN_REQUIRED_MINUTES.coerceAtLeast(1)}%".takeIf { minutes < PLAN_REQUIRED_MINUTES } ?: "✓",
                style = LifeType.number.copy(fontSize = 14.sp))
        }
        Column(Modifier.weight(1f)) {
            Text(state.window.planDate.format(dateFormat), style = LifeType.title, maxLines = 1)
            Text("${duration(minutes)} из ${duration(PLAN_REQUIRED_MINUTES)}", style = LifeType.muted)
        }
        FocusButton("Расписание", onOpenPlan, style = FocusButtonStyle.Tonal, icon = Icons.Filled.CalendarMonth)
    }
    if (plan?.hasInvalidTime == true) Text("Проверь время и названия дел", color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium)
    if (state.planTasks.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (plan?.priority == null) "ЧТО ГЛАВНОЕ? НАЖМИ НА ДЕЛО" else "ГЛАВНОЕ", style = LifeType.section,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            state.planTasks.forEach { task ->
                PriorityRow(task, plan?.priority == task.title, !state.busy) { onChoosePriority(task.title) }
            }
        }
    } else Text("В плане пока пусто. Открой расписание и расставь дела, отдых и дорогу.", style = LifeType.muted)

    if (insights.projectSteps.isNotEmpty()) {
        var added by rememberSaveable(state.window.toString()) { mutableStateOf(listOf<String>()) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ШАГИ ПРОЕКТОВ", style = LifeType.section, modifier = Modifier.padding(start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                insights.projectSteps.forEach { step ->
                    val done = step.entryId in added || step.title in state.carriedTitles
                    ChoiceChip(step.title, done, {
                        if (!done) { added = added + step.entryId; onAddProjectStep(step) }
                    }, icon = if (done) Icons.Filled.Check else Icons.Filled.Add)
                }
            }
        }
    }

    Column {
        Row(Modifier.fillMaxWidth().clip(FocusRadius.field).clickable(onClick = onToggleRecovery).heightIn(min = 48.dp)
            .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Если помешает — то…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(if (recoveryExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(recoveryExpanded) {
            BasicTextField(
                value = state.ifThen, onValueChange = onIfThenChange,
                readOnly = state.busy, minLines = 2, maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().clip(FocusRadius.field).background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(12.dp).semantics { contentDescription = "Если помешает, то" },
                decorationBox = { inner ->
                    Box {
                        if (state.ifThen.isEmpty()) Text("Если застряну в телефоне — положу его в другую комнату",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                        inner()
                    }
                },
            )
        }
    }
}

private fun mealsWord(count: Int): String = when {
    count % 100 in 11..14 -> "записей"
    count % 10 == 1 -> "запись"
    count % 10 in 2..4 -> "записи"
    else -> "записей"
}

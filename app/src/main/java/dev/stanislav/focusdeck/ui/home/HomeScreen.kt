package dev.stanislav.focusdeck.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import dev.stanislav.focusdeck.ui.apps.reliefRemainingMillis
import dev.stanislav.focusdeck.ui.restrictions.AppGateService
import dev.stanislav.focusdeck.ui.restrictions.AppGateStatus
import dev.stanislav.focusdeck.ui.restrictions.appGateStatus
import dev.stanislav.focusdeck.ui.restrictions.ProtectionWindow
import dev.stanislav.focusdeck.ui.restrictions.windowActive
import dev.stanislav.focusdeck.ui.restrictions.windowClockUncertain
import dev.stanislav.focusdeck.ui.apps.safetyExempt
import dev.stanislav.focusdeck.ui.restrictions.remaining
import dev.stanislav.focusdeck.ui.restrictions.reliefRemaining
import dev.stanislav.focusdeck.ui.restrictions.exitRemaining
import dev.stanislav.focusdeck.ui.restrictions.exitClockUncertain
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.data.vault.VaultState
import dev.stanislav.focusdeck.data.vault.VaultTask
import dev.stanislav.focusdeck.ui.components.DayTape
import dev.stanislav.focusdeck.ui.components.FocusButton
import dev.stanislav.focusdeck.ui.components.FocusButtonStyle
import dev.stanislav.focusdeck.ui.components.FocusCard
import dev.stanislav.focusdeck.ui.components.FocusCardPadding
import dev.stanislav.focusdeck.ui.components.UpcomingGapItem
import dev.stanislav.focusdeck.ui.components.UpcomingItem
import dev.stanislav.focusdeck.ui.components.UpcomingStrip
import dev.stanislav.focusdeck.ui.components.SectionHeader
import dev.stanislav.focusdeck.ui.components.SectionTone
import dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES
import dev.stanislav.focusdeck.ui.planning.formatDuration
import dev.stanislav.focusdeck.ui.planning.formatMinuteOfDay
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.LocalFocusDeckColors
import dev.stanislav.focusdeck.ui.theme.tabularNums
import dev.stanislav.focusdeck.ui.ritual.shouldOfferEveningPreparation
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenNutrition: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onOpenPlanning: () -> Unit = {},
    onOpenRitual: () -> Unit = {},
    onOpenLife: () -> Unit = {},
    activeProjects: Int = 0,
    selectedWork: Int = 0,
    selectedStudy: Int = 0,
    reviewsDue: Int = 0,
    lifeCard: (@Composable () -> Unit)? = null,
    usageAccessGranted: Boolean = true,
    onRequestUsageAccess: () -> Unit = {},
    onOpenApps: (search: Boolean) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    night: NightPlan? = null,
) {
    var dayShown by rememberSaveable(night?.planDate?.toString()) { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsState()
    val app = LocalContext.current.applicationContext as FocusDeckApp
    val storedProtection by app.protectionStore.window.collectAsState()
    val protectionFailure by app.protectionStore.failure.collectAsState()
    var freeTitle by rememberSaveable { mutableStateOf("") }
    val protection = storedProtection?.takeIf { windowActive(it, app.protectionStore.clock()) }
    var scheduleOpen by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf<Pair<VaultTask?, String>?>(null) }
    var categoryConflict by remember { mutableStateOf<Pair<VaultTask?, String>?>(null) }
    fun start(task: VaultTask?, category: String, protect: Boolean) {
        if (task != null) viewModel.startTaskBlock(task, category, protect)
        else viewModel.startBlock(title = freeTitle.trim().ifEmpty { "Фокус-блок" }.take(200), plannedMinutes = SESSION_MINUTES, category = category, protect = protect)
    }
    pendingStart?.let { (task, category) ->
        val count = if (category == "work") selectedWork else selectedStudy
        AlertDialog(onDismissRequest = { pendingStart = null }, title = { Text("Защита на 25 минут") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("Выбрано приложений: $count. " +
                "Окно защиты сохранится, даже если остановить работу. Один перерыв на 5 минут продлевает срок до 30 минут: можно сохранить ограничения или отдельно открыть выбранные приложения. Полный выход доступен после ожидания 15 секунд. Системное разрешение можно отозвать в Android.")
                if (category == "study" && workMessagesOptionalAfter15(Instant.ofEpochMilli(state.nowMillis)))
                    Text("После 15:00 по Минску рабочие сообщения для учёбы не обязательны. Набор приложений для учёбы выбираешь отдельно.") } },
            confirmButton = { TextButton(onClick = { pendingStart = null; start(task, category, true) }) { Text("Начать с защитой") } },
            dismissButton = { TextButton(onClick = { pendingStart = null }) { Text("Отмена") } })
    }
    if (scheduleOpen) ScheduleProtectionDialog(app = app, onClose = { scheduleOpen = false })
    categoryConflict?.let { (task, category) ->
        val until = protection?.let { window ->
            val end = java.time.LocalTime.now().plusSeconds((remaining(window, app.protectionStore.clock()) + 999) / 1000)
            (if (end.second == 0 && end.nano == 0) end else end.plusMinutes(1))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        } ?: "окончания окна"
        AlertDialog(onDismissRequest = { categoryConflict = null },
        title = { Text("Действует другое окно защиты") },
        text = { Text("До $until сохранится прежний набор ограничений. Новая сессия будет записана в выбранном режиме. Чтобы изменить ограничения, можно сначала снять защиту на главном экране.") },
        confirmButton = { TextButton(onClick = {
            categoryConflict = null
            val current = app.protectionStore.current()
            val count = if (category == "work") selectedWork else selectedStudy
            if (current == null && app.protectionStore.reserved() == null && count > 0) pendingStart = task to category
            else if (current != null && current.id != protection?.id) categoryConflict = task to category
            else start(task, category, false)
        }) { Text("Продолжить") } },
        dismissButton = { TextButton(onClick = { categoryConflict = null }) { Text("Отмена") } })
    }
    val localTime = Instant.ofEpochMilli(state.nowMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    val eveningPrompt = shouldOfferEveningPreparation(
        localTime, state.tomorrowPrepared, state.activeBlock != null,
    )

    Column(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (night != null) item { NightCard(night, dayShown) { dayShown = !dayShown } }
        if (night == null || dayShown) {
        state.cue?.let { cue ->
            item {
                CueRow(
                    cue = cue,
                    onClick = {
                        when (cue) {
                            is HomeCue.JournalYesterdayEmpty -> onOpenJournal()
                            is HomeCue.MealMissing -> onOpenNutrition()
                        }
                    },
                )
            }
        }
        if (!usageAccessGranted) {
            item { UsageAccessBanner(onClick = onRequestUsageAccess) }
        }
        if (eveningPrompt) {
            item {
                FocusCard(onClick = onOpenRitual) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Подвести итоги · по желанию",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text("Итог · дневник · еда · завтра →",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (protectionFailure) item { Text("Не удалось надёжно сохранить состояние защиты. Её действие неизвестно; проверь настройки и перезапусти приложение.", color = MaterialTheme.colorScheme.error) }
        if (storedProtection?.policyVersion == 2 && protection == null && remaining(storedProtection!!, app.protectionStore.clock()) > 0) item {
            val planned = storedProtection!!
            val zone = ZoneId.of("Europe/Minsk")
            val fmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
            FocusCard { Column {
                Text("${if (planned.strict) "Строгая защита" else "Защита"} запланирована: ${Instant.ofEpochMilli(planned.startWall).atZone(zone).format(fmt)}–${Instant.ofEpochMilli(planned.endWall).atZone(zone).format(fmt)} · Минск")
                if (windowClockUncertain(planned, app.protectionStore.clock())) Text("Часы изменились или устройство перезапущено: соответствие времени расписанию неизвестно.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { app.protectionStore.disableSchedule() }) { Text("Отключить расписание") }
            } }
        }
        if (storedProtection == null || remaining(storedProtection!!, app.protectionStore.clock()) == 0L) item {
            TextButton(onClick = { scheduleOpen = true }) { Text("Заранее выбрать окно защиты") }
        }
        protection?.let { window -> item {
            ProtectionStatusCard(window, state.nowMillis, app, hasWork = state.activeBlock != null)
        } }
        item {
            val active = state.activeBlock
            if (active != null) {
                FocusBlockCard(
                    title = active.title,
                    category = active.category,
                    selectedRestrictions = protection?.packages?.size ?: 0,
                    remainingSeconds = state.remainingSecondsInBlock,
                    plannedMinutes = sessionTargetMinutes(active),
                    progress = state.blockProgress,
                    // Привязка переживает смену дня/папки. Ошибка разрешения допускает повтор,
                    // но свободные и старые непривязанные таймеры не отмечают задачи.
                    canComplete = active.taskVaultRoot != null,
                    busy = state.blockActionBusy,
                    breakStartedAt = active.breakStartedAt,
                    breakCount = active.breakCount,
                    protection = protection,
                    protectionExpired = storedProtection != null && protection == null,
                    workBreakRemaining = reliefRemainingMillis(active, SystemClock.elapsedRealtime(),
                        Settings.Global.getInt(LocalContext.current.contentResolver, Settings.Global.BOOT_COUNT, -1)),
                    onPause = viewModel::pauseActiveBlock,
                    onResume = viewModel::resumeActiveBlock,
                    onComplete = viewModel::completeActiveBlock,
                    onInterrupt = { viewModel.finishActiveBlock(interrupted = true) },
                    onFinish = { viewModel.finishActiveBlock(interrupted = false) },
                )
            } else {
                NextUpCard(
                    nextUp = state.nextUp,
                    priority = state.priority,
                    onStart = { task, category ->
                        val count = if (category == "work") selectedWork else selectedStudy
                        val current = app.protectionStore.current()
                        if (current != null && current.category != category) categoryConflict = task to category
                        else if (current == null && app.protectionStore.reserved() == null && count > 0) pendingStart = task to category
                        else start(task, category, false)
                    },
                    onPlan = onOpenPlanning,
                    freeTitle = freeTitle,
                    onFreeTitleChange = { freeTitle = it.take(200) },
                )
            }
        }
        state.blockActionError?.let { error ->
            item {
                // Keep the existing task/finish buttons available for retry after the operation.
                Text(error, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.summaryStatus?.let { status ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(status, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FocusButton(text = "Повторить запись итогов", onClick = viewModel::retrySummaryWrites,
                            enabled = !state.summaryBusy, style = FocusButtonStyle.Outlined)
                        Box(Modifier.size(20.dp)) {
                            if (state.summaryBusy) CircularProgressIndicator(
                                modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
        state.recoveryCue?.let { cue ->
            item {
                FocusCard(onClick = onOpenPlanning) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("План сдвинулся",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(cue, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item {
            TaskCard(
                tasks = state.tasks,
                vaultState = state.vaultState,
                onToggle = viewModel::toggleTask,
            )
        }
        item { DayTapeCard(state = state, onScrub = viewModel::scrubTo) }
        if (lifeCard != null) item { lifeCard() } else item {
            FocusCard(onClick = onOpenLife) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Цели и проекты", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (reviewsDue > 0) "Пора пересмотреть: $reviewsDue"
                            else if (activeProjects > 0) "В работе: $activeProjects"
                            else "Открыть",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (!eveningPrompt) item {
            Row(
                Modifier.fillMaxWidth().clip(FocusRadius.field).clickable(onClick = onOpenRitual)
                    .padding(horizontal = 4.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.NightsStay, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Подготовить завтра заранее", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        }
    }
    LauncherDock(onSearch = { onOpenApps(true) }, onSettings = onOpenSettings)
    }
}

/** After the evening ritual the home screen goes quiet: tomorrow's main thing, nothing to scroll. */
data class NightPlan(
    val planDate: LocalDate,
    val priority: String?,
    val priorityMinute: Int?,
    val firstTitle: String?,
    val firstMinute: Int?,
)

@Composable
private fun NightCard(night: NightPlan, dayShown: Boolean, onToggleDay: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.NightsStay, null, Modifier.size(22.dp), tint = LocalFocusDeckColors.current.attention)
            Text("День закрыт", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        }
        if (night.priority != null) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Завтра главное", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(night.priority, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            night.priorityMinute?.let {
                Text("в ${formatMinuteOfDay(it)}", style = MaterialTheme.typography.bodyLarge.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (night.firstTitle != null && night.firstTitle != night.priority) Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(night.firstMinute?.let(::formatMinuteOfDay) ?: "", style = MaterialTheme.typography.titleMedium.tabularNums(),
                color = MaterialTheme.colorScheme.primary)
            Text("первое: ${night.firstTitle}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (dayShown) "Скрыть сегодняшний день" else "Показать сегодняшний день",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(FocusRadius.field).clickable(onClick = onToggleDay).padding(vertical = 8.dp),
        )
    }
}

/** Apps are one tap away but never a destination of their own: a search row and a gear. */
@Composable
private fun LauncherDock(onSearch: () -> Unit, onSettings: () -> Unit) {
    val searchPress = remember { MutableInteractionSource() }
    val searchPressed by searchPress.collectIsPressedAsState()
    val searchScale by animateFloatAsState(
        if (searchPressed) 0.97f else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "dockPress",
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).height(48.dp)
                .graphicsLayer { scaleX = searchScale; scaleY = searchScale }
                .clip(FocusRadius.pill)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(interactionSource = searchPress, indication = ripple(), onClick = onSearch)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
            Text("Приложения", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier.size(48.dp).clip(FocusRadius.pill).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Settings, "Настройки", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CueRow(cue: HomeCue, onClick: () -> Unit) {
    val (icon, title, trailing) = when (cue) {
        is HomeCue.JournalYesterdayEmpty ->
            Triple(Icons.AutoMirrored.Filled.MenuBook, "Дневник за вчера пуст", "1 минута")
        is HomeCue.MealMissing ->
            Triple(Icons.Filled.Restaurant, cue.label, cue.expectedTime)
    }
    // Подсказки необязательные, поэтому пунктирная строка, а не залитая.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FocusRadius.pill)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, FocusRadius.pill)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(FocusRadius.pill)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = trailing,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun UsageAccessBanner(onClick: () -> Unit) {
    val colors = LocalFocusDeckColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FocusRadius.block)
            .background(colors.attentionContainer)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = colors.onAttentionContainer,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Экранное время отключено. Разрешение для статистики необязательно.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onAttentionContainer,
        )
    }
}

/**
 * Чем заняться прямо сейчас — одна задача крупно, а не список из трёх мелких.
 *
 * Раньше карточка была большой только когда задач нет, а когда они есть —
 * рассыпалась на строки одинакового веса. Получалось наоборот: пустой день
 * кричал, а полный предлагал выбирать заново. Теперь размер всегда один,
 * и решение внутри уже принято — кнопка называет задачу по имени.
 */
@Composable
private fun NextUpCard(
    nextUp: NextUp,
    priority: String? = null,
    onStart: (VaultTask?, String) -> Unit,
    onPlan: () -> Unit,
    freeTitle: String,
    onFreeTitleChange: (String) -> Unit,
) {
    when (nextUp) {
        is NextUp.Now -> TaskToStartCard(
            label = if (nextUp.task.title == priority) "Главное · сейчас" else "Сейчас по плану",
            task = nextUp.task,
            tone = SectionTone.Primary,
            timing = "${formatMinuteOfDay(nextUp.task.startMinuteOfDay!!)} – " +
                formatMinuteOfDay(
                    nextUp.task.startMinuteOfDay + (nextUp.task.durationMinutes ?: DEFAULT_TASK_MINUTES),
                ),
            onStart = { category -> onStart(nextUp.task, category) },
        )
        is NextUp.Overdue -> TaskToStartCard(
            label = (if (nextUp.task.title == priority) "Главное · " else "") +
                "осталось с ${formatMinuteOfDay(nextUp.task.startMinuteOfDay!!)}",
            task = nextUp.task,
            tone = SectionTone.Attention,
            timing = formatDuration(nextUp.task.durationMinutes ?: DEFAULT_TASK_MINUTES),
            onStart = { category -> onStart(nextUp.task, category) },
        )
        is NextUp.Rest -> RestCard(
            nextStartMinute = nextUp.nextStartMinute,
            onPlan = onPlan,
            onStartFree = { category -> onStart(null, category) },
            freeTitle = freeTitle,
            onFreeTitleChange = onFreeTitleChange,
        )
    }
}

@Composable
private fun TaskToStartCard(
    label: String,
    task: VaultTask,
    tone: SectionTone,
    timing: String,
    onStart: (String) -> Unit,
) {
    FocusCard {
        Column {
            SectionHeader(icon = Icons.Filled.PlayArrow, title = label, tone = tone)
            Text(
                text = task.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = timing,
                style = MaterialTheme.typography.bodyMedium.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            StartChoices(onStart, Modifier.padding(top = 20.dp))
        }
    }
}

/**
 * Нечего начинать — и это нормальное состояние, а не пустая заглушка.
 * Свободный блок остался вторым действием: иногда сесть за работу нужно
 * раньше, чем появится строчка в плане.
 */
@Composable
private fun RestCard(nextStartMinute: Int?, onPlan: () -> Unit, onStartFree: (String) -> Unit,
                     freeTitle: String, onFreeTitleChange: (String) -> Unit) {
    FocusCard {
        Column {
            // «Ничего не запланировано» при четырёх задачах на вечер — неправда,
            // поэтому заголовок называет границу свободного времени, а не пустоту.
            SectionHeader(
                icon = Icons.Filled.Bolt,
                title = nextStartMinute
                    ?.let { "Свободно до ${formatMinuteOfDay(it)}" }
                    ?: "Ничего не запланировано",
                tone = SectionTone.Neutral,
            )
            Text(
                text = "Можно начать прямо сейчас",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Начни локальный блок или добавь задачу в план.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            OutlinedTextField(value = freeTitle, onValueChange = onFreeTitleChange,
                label = { Text("Над чем работаешь? Необязательно") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("Блок на $SESSION_MINUTES минут", modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            StartChoices(onStartFree, Modifier.padding(top = 8.dp))
            FocusButton(text = "Запланировать задачу", icon = Icons.Filled.EditCalendar, onClick = onPlan,
                style = FocusButtonStyle.Tonal, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

@Composable
private fun ScheduleProtectionDialog(app: FocusDeckApp, onClose: () -> Unit) {
    var start by remember { mutableStateOf("15:00") }
    var end by remember { mutableStateOf("16:00") }
    var category by remember { mutableStateOf("study") }
    var weekdaysOnly by remember { mutableStateOf(true) }
    val domainState by app.domainFilter.state.collectAsState()
    var onlineMode by remember { mutableStateOf(false) }
    var exitDelay by remember { mutableStateOf(60L) }
    var strict by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var appLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(app) { appLabels = app.appsRepository.launchableApps().associate { it.packageName to it.label } }
    val selectedPackages = remember(category) { app.getSharedPreferences("launcher-restrictions", android.content.Context.MODE_PRIVATE)
        .getStringSet(category, emptySet()).orEmpty().filterNot { safetyExempt(app, it) }.toSet() }
    AlertDialog(onDismissRequest = onClose, title = { Text("Повторяющееся окно защиты") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Время по Минску. Начало не раньше 15:00; конец в тот же день. Если сейчас внутри окна, оно начнётся сейчас и закончится в выбранное время.")
            OutlinedTextField(start, { start = it }, label = { Text("Начало HH:mm") }, singleLine = true)
            OutlinedTextField(end, { end = it }, label = { Text("Конец HH:mm") }, singleLine = true)
            Row { TextButton(onClick = { category = "work" }) { Text(if (category == "work") "✓ Работа" else "Работа") }; TextButton(onClick = { category = "study" }) { Text(if (category == "study") "✓ Учёба" else "Учёба") } }
            Row { TextButton(onClick = { weekdaysOnly = true }) { Text(if (weekdaysOnly) "✓ Пн–Пт" else "Пн–Пт") }; TextButton(onClick = { weekdaysOnly = false }) { Text(if (!weekdaysOnly) "✓ Каждый день" else "Каждый день") } }
            Row {
                TextButton(onClick = { onlineMode = false }) { Text(if (!onlineMode) "✓ Без браузеров/лент" else "Без браузеров/лент") }
                TextButton(onClick = { onlineMode = true }) { Text(if (onlineMode) "✓ Онлайн · DNS" else "Онлайн · DNS") }
            }
            Text(if (!onlineMode) "Будут целиком ограничены браузеры и приложения, заранее отмеченные для выбранного режима. Материалы вне выбранных приложений останутся доступны. Без включённой службы доступности ограничен только запуск через FocusDeck." else
                "Выбранные браузеры остаются доступны. DNS-фильтр частичный: DoH, прямые IP, кэш, IPv6 и собственный DNS могут обходить его. Один VPN-слот Android; действующий другой VPN не заменяется. VPN нужно отдельно запустить в активном окне.")
            if (!onlineMode) Text("Выбрано: ${selectedPackages.size}. " + selectedPackages.map { appLabels[it] ?: it }.sorted().joinToString(", "), style = MaterialTheme.typography.bodySmall)
            if (onlineMode && !domainState.setup.enabledForWindows) Text("Сначала настройте DNS сайтов и дайте согласие в параметрах.", color = MaterialTheme.colorScheme.error)
            Row { TextButton(onClick = { strict = false }) { Text(if (!strict) "✓ Обычный" else "Обычный") }; TextButton(onClick = { strict = true }, enabled = false) { Text("Строгий недоступен") } }
            Text("После перезапуска нельзя надёжно подтвердить время окончания без доверенных часов. Строгий режим пока недоступен.", style = MaterialTheme.typography.bodySmall)
            if (strict) Text("Строгий режим доступен для выбранных приложений без DNS: без временного послабления; полный выход после часа ожидания или естественного конца окна. Если после перезапуска время нельзя проверить, окно может продлиться: доступен полный выход после часа. Отдых сохраняет ограничения. Android позволяет владельцу отключить службу или удалить приложение; полной защиты это не гарантирует.")
            else {
                Text("Ожидание перед полным выходом: выбранная гипотеза, не научная норма")
                Row { TextButton(onClick = { exitDelay = 15 }) { Text(if (exitDelay == 15L) "✓ 15 с" else "15 с") }; TextButton(onClick = { exitDelay = 60 }) { Text(if (exitDelay == 60L) "✓ 60 с" else "60 с") } }
                Text("Одно послабление до 5 минут на календарный день резервируется целиком, даже при раннем возврате. Повторное включение в тот же день не восстанавливает бюджет. Отдых без лент сохраняет ограничения и бюджет. Конец окна не продлевается.")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val zone = ZoneId.of("Europe/Minsk")
        val date = java.time.LocalDate.now(zone)
        val times = runCatching { listOf(start, end).map { parseProtectionTime(it).atDate(date).atZone(zone).toInstant().toEpochMilli() } }.getOrNull()
        val packages = if (onlineMode) emptySet() else selectedPackages
        val domain = if (onlineMode) app.domainFilter.snapshotForSession(java.util.UUID.randomUUID().toString()) else null
        if (times != null && !(strict && onlineMode) && (!onlineMode || domain != null) && app.protectionStore.schedule(category, packages, times[0], times[1], if (weekdaysOnly) (1..5).toSet() else (1..7).toSet(), exitDelay * 1_000, domain, strict)) onClose()
        else error = if (strict && onlineMode) "Строгий режим доступен только без DNS: выберите приложения или обычный режим." else if (onlineMode && domain == null) "Сначала выберите сайты и браузеры в настройках DNS." else "Проверьте время и заранее выбранные приложения. Конец должен быть позже начала в тот же день."
    }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } })
}

@Composable
private fun ProtectionStatusCard(window: ProtectionWindow, nowMillis: Long, app: FocusDeckApp, hasWork: Boolean) {
    val clock = remember(nowMillis, window) { app.protectionStore.clock() }
    val remainingSeconds = (remaining(window, clock) + 999) / 1000
    val reliefSeconds = (reliefRemaining(window, clock) + 999) / 1000
    val exitSeconds = (exitRemaining(window, clock) + 999) / 1000
    val connected by AppGateService.connected.collectAsState()
    val ready by AppGateService.ready.collectAsState()
    val failed by AppGateService.failure.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    FocusCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${if (window.policyVersion == 2 && window.packages.isNotEmpty()) "Без браузеров/лент" else if (window.policyVersion == 2 && window.domainPolicyJson != null) "Онлайн · частичный DNS" else "Защита"} · ${if (window.category == "work") "работа" else "учёба"} · ${formatMinSec(remainingSeconds)}",
                style = MaterialTheme.typography.titleMedium)
            if (window.strict) Text("Строгий режим · послабление недоступно, выход после часа ожидания или конца окна", style = MaterialTheme.typography.bodySmall)
            if (window.strictRecovery) Text("После перезапуска время конца нельзя подтвердить: окно сохранено на срок исходной длительности. Можно запросить полный выход с ожиданием один час.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(when {
                window.resting -> "Отдых без лент · ограничения сохранены"
                reliefSeconds > 0 -> "Послабление · ${formatMinSec(reliefSeconds)}"
                window.packages.isEmpty() -> "Приложения целиком не ограничены"
                appGateStatus(connected, ready, failed) == AppGateStatus.READY -> "Служба контроля приложений подключена"
                appGateStatus(connected, ready, failed) == AppGateStatus.INITIALIZING -> "Только запуск через FocusDeck · служба контроля запускается"
                appGateStatus(connected, ready, failed) == AppGateStatus.UNAVAILABLE -> "Только запуск через FocusDeck · системный контроль недоступен"
                else -> "Только запуск через FocusDeck · системная служба не подключена"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (windowClockUncertain(window, clock)) Text("Часы изменились или устройство перезапущено: соответствие расписанию по календарю неизвестно.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (window.domainPolicyJson != null) {
                val dns by dev.stanislav.focusdeck.domainfilter.DomainFilterVpnService.status.collectAsState()
                Text(if (dns.active && dns.sessionId == window.id) "DNS выбранных приложений: служба активна, охват частичный" else "DNS сайтов не активен. Открой настройки и запусти текущий снимок; другие VPN не заменяются автоматически.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Остановка таймера не снимает ограничения. Выбрано приложений: ${window.packages.size}. Доступ к системным функциям сохраняется; разрешения Android можно отозвать в настройках.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if ((!hasWork && reliefSeconds > 0) || (window.policyVersion == 2 && window.resting)) TextButton(onClick = {
                if (!app.protectionStore.endRelief()) error = "Не удалось вернуть ограничения."
                else if (window.resting) app.recordAudit("rest_returned", app.auditContext(window), windowId = window.id, resolveActive = false)
            }) { Text("Вернуть ограничения сейчас") }
            if (!window.strict && !hasWork && !window.reliefUsed && !window.resting) TextButton(onClick = {
                if (!app.protectionStore.beginRelief()) error = "Не удалось начать послабление."
                else app.recordAudit("bounded_relief_started", app.auditContext(window), windowId = window.id, resolveActive = false)
            }) { Text("Послабление на 5 минут") }
            if (window.policyVersion == 2 && !window.resting && reliefSeconds == 0L) TextButton(onClick = {
                if (app.protectionStore.beginRest()) app.recordAudit("rest_without_feeds", app.auditContext(window), windowId = window.id, resolveActive = false)
                else error = "Не удалось начать отдых."
            }) { Text("Отдых без лент") }
            if (window.policyVersion == 2 && app.protectionStore.scheduleEnabled()) TextButton(onClick = {
                if (!app.protectionStore.disableSchedule()) error = "Не удалось отключить расписание."
                else error = "Следующие окна отключены; текущее действует до конца или полного выхода."
            }) { Text("Отключить будущие окна") }
            if (exitClockUncertain(window, clock)) Text("Время изменилось или состояние загрузки неизвестно. Ожидание идёт по монотонным часам; после потери процесса начинается заново. Можно отменить ожидание и запросить выход снова.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (window.exitWall == null) TextButton(onClick = {
                if (!app.protectionStore.requestExit()) error = "Не удалось начать выход."
            }) { Text("Полный выход из защиты") }
            else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { app.protectionStore.cancelExit() }) { Text("Отмена") }
                TextButton(enabled = exitSeconds == 0L, onClick = {
                    if (app.protectionStore.exit()) app.recordAudit("protection_exit", app.auditContext(window), windowId = window.id, resolveActive = false)
                    else error = "Не удалось снять защиту."
                }) { Text(if (exitSeconds > 0) "Выход через ${exitSeconds} с" else "Снять защиту") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun StartChoices(onStart: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FocusButton(text = "Работа", onClick = { onStart("work") }, modifier = Modifier.weight(1f))
        FocusButton(text = "Учёба", onClick = { onStart("study") }, modifier = Modifier.weight(1f),
            style = FocusButtonStyle.Tonal)
    }
}

@Composable
private fun FocusBlockCard(
    title: String,
    category: String?,
    selectedRestrictions: Int,
    remainingSeconds: Long,
    plannedMinutes: Int,
    progress: Float,
    canComplete: Boolean,
    busy: Boolean,
    breakStartedAt: Long?,
    breakCount: Int,
    protection: ProtectionWindow?,
    protectionExpired: Boolean,
    workBreakRemaining: Long,
    onPause: (Boolean) -> Unit,
    onResume: () -> Unit,
    onComplete: () -> Unit,
    onInterrupt: () -> Unit,
    onFinish: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as FocusDeckApp
    val gateConnected by AppGateService.connected.collectAsState()
    val gateReady by AppGateService.ready.collectAsState()
    val gateFailed by AppGateService.failure.collectAsState()
    var chooseBreak by remember { mutableStateOf(false) }
    if (chooseBreak) AlertDialog(
        onDismissRequest = { chooseBreak = false },
        title = { Text("Как провести перерыв?") },
        text = { Text(if (protection?.strict == true) "Пять минут без учёта работы. Строгие ограничения сохраняются." else "Пять минут без учёта работы. В первом варианте выбранные ограничения сохраняются. Во втором выбранные приложения откроются на пять минут, затем ограничения вернутся. Это единственное послабление в текущем окне.") },
        confirmButton = { TextButton(onClick = { chooseBreak = false; onPause(false) }) { Text("Отдых без лент") } },
        dismissButton = { if (protection?.strict != true) TextButton(onClick = { chooseBreak = false; onPause(true) }) { Text("Открыть выбранные") } },
    )
    FocusCard {
        Column {
            SectionHeader(icon = Icons.Filled.PlayArrow, title = when (category) {
                "work" -> "Работа"
                "study" -> "Учёба"
                else -> "Идёт блок"
            })
            if (category == "study" && workMessagesOptionalAfter15(Instant.now())) Text(
                "После 15:00 по Минску рабочие сообщения для учёбы не обязательны.",
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = formatMinSec(remainingSeconds),
                    style = MaterialTheme.typography.displayMedium.tabularNums(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "из $plannedMinutes:00",
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            ProgressTrack(
                progress = progress,
                height = 8.dp,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (remainingSeconds == 0L && breakStartedAt == null) Text(
                "Время блока закончилось. Учёт продолжается, пока не остановишь блок.",
                modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            Text(when {
                protectionExpired && breakStartedAt == null -> "Учёт идёт · срок ограничений закончился"
                protectionExpired -> "Срок ограничений закончился · таймер на паузе"
                protection == null -> "Окно защиты не активно"
                selectedRestrictions == 0 -> "Приложения целиком не ограничены"
                gateConnected && gateFailed -> "Системный контроль недоступен · проверь настройки"
                gateConnected && gateReady -> "Служба контроля приложений подключена"
                else -> "Ограничено приложений: $selectedRestrictions · только при запуске через FocusDeck"
            }, modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (breakStartedAt != null) {
                val remaining = (workBreakRemaining + 999) / 1000
                val guardRelief = protection?.let { reliefRemaining(it, app.protectionStore.clock()) } ?: Long.MAX_VALUE
                Text(if (protection != null && guardRelief == 0L) "Таймер на паузе · ограничения остаются"
                    else if (remaining > 0) "Перерыв · ${formatMinSec(remaining)}" else "Перерыв закончился · можно вернуться",
                    modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Text("Напомним примерно через 5 минут, если уведомления разрешены. Android и режим «Не беспокоить» могут задержать или скрыть напоминание.",
                    modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FocusButton(text = "Вернуться к блоку", onClick = onResume, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else if (breakCount == 0) {
                FocusButton(text = if (protection?.reliefUsed == true) "Пауза таймера · ограничения остаются" else "Перерыв на 5 минут", onClick = {
                    if (protection != null && !protection.reliefUsed) chooseBreak = true else onPause(false)
                }, enabled = !busy,
                    style = FocusButtonStyle.Tonal, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            // Главное действие — отметить задачу сделанной, и оно заняло всю
            // ширину. Раньше блок можно было только закрыть или сорвать, а
            // галочку всё равно приходилось искать в списке ниже.
            if (canComplete) {
                FocusButton(
                    text = "Задача выполнена",
                    icon = Icons.Filled.TaskAlt,
                    onClick = onComplete,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (canComplete) 8.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FocusButton(
                    text = "Остановить",
                    icon = Icons.Filled.Stop,
                    style = if (canComplete) FocusButtonStyle.Outlined else FocusButtonStyle.Tonal,
                    onClick = onInterrupt,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                // Без задачи «закончить» — основное действие; с задачей оно
                // означает «время вышло, а дело ещё нет», и тон у него тише.
                FocusButton(
                    text = "Закончить",
                    icon = Icons.Filled.Check,
                    style = if (canComplete) FocusButtonStyle.Outlined else FocusButtonStyle.Filled,
                    onClick = onFinish,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    tasks: List<VaultTask>,
    vaultState: VaultState,
    onToggle: (VaultTask) -> Unit,
) {
    // Карточка без внутреннего отступа: его отбивают заголовок и строки сами,
    // иначе подсветка нажатой строки обрывалась, не доходя до краёв карточки.
    FocusCard(contentPadding = 0.dp) {
        Column(modifier = Modifier.padding(vertical = FocusCardPadding)) {
            SectionHeader(
                modifier = Modifier.padding(horizontal = FocusCardPadding),
                icon = Icons.Filled.TaskAlt,
                title = "Сегодня",
                tone = SectionTone.Secondary,
            ) {
                Text(
                    text = "${tasks.count { it.done }} из ${tasks.size}",
                    style = MaterialTheme.typography.labelLarge.tabularNums(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tasks.isEmpty()) {
                Text(
                    // Пустой список и отсутствующий источник — разные вещи, и путать их
                    // нельзя: «задач нет» вместо «папка не выбрана» выглядит как рабочий
                    // пустой день, хотя приложение просто ничего не читает.
                    text = when (vaultState) {
                        VaultState.NOT_CONNECTED ->
                            "Подключи папку Obsidian"
                        VaultState.NOTE_MISSING ->
                            "Сегодняшней заметки нет"
                        VaultState.ERROR -> "Заметку не удалось прочитать"
                        VaultState.READY ->
                            "На сегодня задач нет"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, start = FocusCardPadding, end = FocusCardPadding),
                )
            }
            tasks.forEach { task -> TaskRow(task = task, onToggle = { onToggle(task) }) }
        }
    }
}

@Composable
private fun TaskRow(task: VaultTask, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = FocusCardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(FocusRadius.chip)
                .background(
                    if (task.done) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (task.done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (task.done) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
            task.startMinuteOfDay?.let {
                Text(
                    text = "%02d:%02d".format(it / 60, it % 60),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        task.durationMinutes?.let {
            Text(
                text = "${it}м",
                style = MaterialTheme.typography.labelMedium.tabularNums(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DayTapeCard(state: HomeUiState, onScrub: (Int?) -> Unit) {
    val window = state.upcoming
    FocusCard {
        Column {
            SectionHeader(
                icon = Icons.Filled.Timeline,
                // Заголовок не обещает «ближайшие»: окно можно отвести пальцем
                // в любую точку суток, и тогда название врало бы.
                title = "Ход дня",
                tone = SectionTone.Neutral,
                // Время окна стоит здесь всегда, а не появляется строкой ниже,
                // когда окно отвели. Появляющаяся строка сдвигала бы ленту вниз
                // ровно в тот момент, когда по ней ведут пальцем, — и лента
                // уезжала бы из-под пальца на первом же касании.
                trailing = {
                    Text(
                        text = formatMinuteOfDay(window.startMinuteOfDay) + " – " +
                            formatMinuteOfDay(window.endMinuteOfDay),
                        style = MaterialTheme.typography.labelMedium.tabularNums(),
                        color = if (window.followsNow) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (!window.followsNow) {
                        Text(
                            text = "Сейчас",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .clip(FocusRadius.pill)
                                .clickable { onScrub(null) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                },
            )
            UpcomingStrip(
                items = window.blocks.map { block ->
                    UpcomingItem(
                        startFraction = block.startFraction,
                        widthFraction = block.widthFraction,
                        title = block.title,
                        timing = formatMinuteOfDay(block.startMinuteOfDay) + " – " +
                            formatMinuteOfDay(block.endMinuteOfDay),
                        fill = block.fill,
                        clippedStart = block.clippedStart,
                    )
                },
                gaps = window.gaps.map { gap ->
                    UpcomingGapItem(
                        startFraction = gap.startFraction,
                        widthFraction = gap.widthFraction,
                        label = "${gap.minutes} мин свободно",
                    )
                },
                hourMarks = window.hourMarks,
                nowFraction = window.nowFraction,
                modifier = Modifier.padding(top = 12.dp),
            )

            // Сутки целиком — контекст и одновременно орган управления: по ним
            // видно плотность дня, место окна в нём, и по ним же окно водится.
            ScrubbableDayTape(state = state, onScrub = onScrub)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Лента покрывает полные сутки 00:00–24:00 — подписи совпадают с этим диапазоном.
                listOf("00", "06", "12", "18", "24").forEach { hour ->
                    Text(
                        text = hour,
                        style = MaterialTheme.typography.bodySmall.tabularNums(),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/**
 * Лента суток, по которой водят пальцем.
 *
 * Сама полоска в 8dp для пальца слишком тонкая, поэтому она лежит внутри
 * коробки высотой в палец: касание ловит коробка, а рисуется полоска.
 *
 * Жест горизонтальный: вертикальные сюда не попадают, и главный экран
 * продолжает прокручиваться прямо поверх ленты.
 */
@Composable
private fun ScrubbableDayTape(state: HomeUiState, onScrub: (Int?) -> Unit) {
    val minutesInDay = 24 * 60
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                fun minuteAt(x: Float): Int =
                    (x / size.width * minutesInDay).toInt().coerceIn(0, minutesInDay)
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onScrub(minuteAt(offset.x)) },
                ) { change, _ -> onScrub(minuteAt(change.position.x)) }
            }
            // Отдельным обработчиком: одиночное касание должно переносить окно
            // сразу, не дожидаясь, пока палец сдвинется на порог перетаскивания.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onScrub((offset.x / size.width * minutesInDay).toInt().coerceIn(0, minutesInDay))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        DayTape(
            segments = state.tapeSegments,
            nowFraction = nowFractionOfDay(state.nowMillis),
            window = state.upcoming.tapeRange,
            height = 12.dp,
            markerGutter = 6.dp,
        )
    }
}

@Composable
private fun ProgressTrack(
    progress: Float,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
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

private fun nowFractionOfDay(nowMillis: Long): Float {
    val zone = ZoneId.systemDefault()
    val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    return ((nowMillis - dayStart).toFloat() / (24 * 60 * 60 * 1000f)).coerceIn(0f, 1f)
}

private fun formatMinSec(totalSeconds: Long): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}


/**
 * Итог дня в заметку — по нажатию, а не сам.
 * Ту же секцию правит скрипт на Mac, и две автоматические записи по расписанию
 * рано или поздно наложились бы друг на друга.
 */

package dev.stanislav.focusdeck.ui.planning

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.vault.VaultState
import dev.stanislav.focusdeck.ui.components.DayHeader
import dev.stanislav.focusdeck.ui.components.FocusButton
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun PlanningScreen(
    viewModel: PlanningViewModel,
    allowDateSelection: Boolean = true,
    onOpenVaultSettings: () -> Unit = {},
    onCreationVisibilityChanged: (Boolean) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val dragState = rememberTimelineDragState()
    var daysExpanded by remember(allowDateSelection) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var creationMinute by rememberSaveable(state.selectedDate) { mutableStateOf<Int?>(null) }
    var creationPending by remember { mutableStateOf(false) }
    var sheetHeightPx by remember { mutableIntStateOf(with(density) { 64.dp.roundToPx() }) }
    val lift by animateFloatAsState(
        targetValue = if (creationMinute != null) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 300f),
        label = "task-form-lift",
    )
    val dismissCreation = {
        if (!creationPending) {
            focusManager.clearFocus()
            keyboard?.hide()
            creationMinute = null
        }
    }
    BackHandler(enabled = creationMinute != null) { dismissCreation() }
    LaunchedEffect(creationMinute, lift == 0f) {
        onCreationVisibilityChanged(creationMinute != null || lift > 0f)
    }
    DisposableEffect(Unit) {
        onDispose { onCreationVisibilityChanged(false) }
    }

    // Линия «сейчас» должна ползти — раз в минуту достаточно.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            viewModel.refreshNow()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        val availableHeightPx = with(density) { maxHeight.toPx() }
        Column(modifier = Modifier.fillMaxSize()) {
            // Дата с выпадающей полосой дней. Развёрнутой полоса постоянно съедала бы
            // высоту у таймлайна, хотя нужна редко: по умолчанию день — сегодняшний.
            DayHeader(
                selected = state.selectedDate,
                summary = state.summary,
                daysWithNotes = state.daysWithNotes,
                expanded = allowDateSelection && daysExpanded,
                allowDateSelection = allowDateSelection,
                onToggle = {
                    if (allowDateSelection) {
                        daysExpanded = !daysExpanded
                        if (daysExpanded) viewModel.loadNoteDates()
                    }
                },
                onSelect = { day ->
                    if (allowDateSelection) {
                        daysExpanded = false
                        viewModel.selectDate(day)
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
                trailing = {
                    IconAction(Icons.AutoMirrored.Filled.MenuBook, "Obsidian", onOpenVaultSettings)
                },
            )

            if (state.vaultState != VaultState.READY) {
                VaultNotice(
                    state = state.vaultState,
                    busy = state.busy,
                    onConnect = onOpenVaultSettings,
                    onCreateNote = viewModel::createNote,
                    onRetry = viewModel::refreshNote,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                return@Column
            }

            state.error?.takeIf { creationMinute == null }?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = viewModel::clearError).padding(vertical = 8.dp))
            }

            Box(modifier = Modifier.weight(1f)) {
                DayTimeline(
                    items = state.placed,
                    freeWindows = state.freeWindows,
                    // На других днях линии «сейчас» быть не должно: она бы врала.
                    nowMinuteOfDay = if (state.isToday) state.nowMinuteOfDay else -1,
                    dragState = dragState,
                    scrollState = scrollState,
                    highlightDurationMinutes = state.highlightDurationMinutes,
                    onMoveTask = viewModel::moveTask,
                    onResizeTask = viewModel::resizeTask,
                    onUnscheduleTask = viewModel::unscheduleTask,
                    // Свободные окна занимают почти всю пустоту таймлайна и перехватывают
                    // касание, поэтому снимать выделение приходится здесь: обработчик
                    // «мимо всего» под ними просто не срабатывал, и ручки оставались висеть.
                    // Когда задача выбрана в пуле, касание по-прежнему ставит её сюда —
                    // это основной способ размещения, отменяется он крестиком в панели.
                    onLongPressEmpty = { minute ->
                        if (!state.busy) {
                            dragState.select(null)
                            viewModel.selectTask(null)
                            viewModel.clearError()
                            creationMinute = minute
                        }
                    },
                    onTapFreeWindow = { minute ->
                        val task = state.selectedTask
                        if (task != null) viewModel.placeTaskAt(task, minute) else dragState.select(null)
                    },
                )

            }

            Spacer(Modifier.height(if (creationMinute != null || lift > 0f) 72.dp
                else with(density) { sheetHeightPx.toDp() } + 8.dp))
        }

        // A failed write can change the vault state. Keep the draft mounted so
        // the user can retry or cancel without losing the entered title.
        if (state.vaultState == VaultState.READY || creationMinute != null || lift > 0f) {
            if (creationMinute != null || lift > 0f) {
                Box(Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f * lift))
                    .pointerInput(creationPending) { detectTapGestures { dismissCreation() } })
            }
            UnscheduledSheet(
                tasks = state.unscheduled,
                projects = state.projects,
                onAssignProject = viewModel::assignProject,
                selectedTaskId = state.selectedTaskId,
                suggestedSlots = state.suggestedSlots,
                onSelectTask = viewModel::selectTask,
                onPlaceAt = viewModel::placeTaskAt,
                onAddTask = viewModel::addTask,
                onRenameTask = viewModel::renameTask,
                onSetDuration = viewModel::setTaskDuration,
                onToggleTask = { task -> viewModel.toggleTask(task, !task.done) },
                onDeleteTask = viewModel::deleteTask,
                creationMinute = creationMinute,
                creationLift = lift,
                creationError = state.error,
                onCancelCreation = dismissCreation,
                onAddTaskAt = { title, duration, project, minute, onResult ->
                    creationPending = true
                    viewModel.addTaskAt(title, duration, project, minute) { success ->
                        creationPending = false
                        onResult(success)
                        if (success) dismissCreation()
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .offset { IntOffset(0, (-((availableHeightPx - sheetHeightPx) / 2f) * lift).roundToInt()) }
                    .heightIn(max = maxHeight)
                    .onSizeChanged { sheetHeightPx = it.height },
            )
        }
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(FocusRadius.pill)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Что показывать вместо таймлайна, когда заметки нет.
 * Разделено по причинам: «подключить папку» и «создать заметку» — разные действия,
 * и одно общее «что-то не так» заставило бы гадать, какое из них нужно.
 */
@Composable
private fun VaultNotice(
    state: VaultState,
    busy: Boolean,
    onConnect: () -> Unit,
    onCreateNote: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, description, action) = when (state) {
        VaultState.NOT_CONNECTED -> Triple(
            "Vault не подключён",
            "Выбери папку Obsidian.",
            "Выбрать папку" to onConnect,
        )
        VaultState.NOTE_MISSING -> Triple(
            "Заметки за этот день нет",
            "Создать запись для этого дня?",
            "Создать заметку" to onCreateNote,
        )
        VaultState.ERROR -> Triple(
            "Заметку не удалось прочитать",
            "Повтори чтение или заново выбери папку Obsidian.",
            "Попробовать снова" to onRetry,
        )
        VaultState.READY -> Triple("", "", "" to {})
    }

    Column(
        modifier = modifier.padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FocusButton(
            text = if (busy) "Минуту…" else action.first,
            onClick = action.second,
            enabled = !busy,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

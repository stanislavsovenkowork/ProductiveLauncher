package dev.stanislav.focusdeck.ui.planning

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stanislav.focusdeck.data.vault.VaultTask
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeStatus
import dev.stanislav.focusdeck.ui.components.FocusButton
import dev.stanislav.focusdeck.ui.components.FocusButtonStyle
import dev.stanislav.focusdeck.ui.components.FocusTextField
import dev.stanislav.focusdeck.ui.theme.FocusRadius
import dev.stanislav.focusdeck.ui.theme.tabularNums

/** Длительность задачи: от четверти часа до четырёх, шагами по 15 минут. */
private const val MIN_DURATION = 15
private const val MAX_DURATION = 240

/**
 * Единый горизонтальный отступ всей шторки и шаг вертикального ритма.
 *
 * Раньше заголовок, список и формы отступали от края по-разному (16 и 12),
 * а вертикальные промежутки шли вразнобой — 8, 10, 12, 16. На глаз это читалось
 * как перекос, хотя каждое значение по отдельности выглядело разумным.
 */
private val SHEET_PADDING: Dp = 20.dp
private val SHEET_GAP: Dp = 12.dp

private val HEADER_HEIGHT: Dp = 64.dp
private val ROW_HEIGHT: Dp = 56.dp
private val ADD_FORM_HEIGHT: Dp = 64.dp
private val DURATION_ROW_HEIGHT: Dp = 48.dp
private val DURATION_LABEL_WIDTH: Dp = 84.dp
private val SLIDER_HEIGHT: Dp = DURATION_ROW_HEIGHT + 12.dp
private val PROJECT_ROW_HEIGHT: Dp = 48.dp

/** Доводка шторки: быстро, без раскачки. */
private val SHEET_SPRING = spring<Dp>(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)

/** Панель размещения, включая одну компактную строку проекта. */
private val PLACEMENT_HEIGHT: Dp = 284.dp

/** На сколько раскрыта шторка. */
private enum class SheetMode { COLLAPSED, LIST, FULL }

/**
 * Пул задач без времени.
 *
 * Шторка тянется: в свёрнутом виде это одна строка, и таймлайну достаётся весь экран.
 * Раньше она занимала треть экрана всегда, даже когда пул был пуст.
 *
 * Основной путь размещения — выбрать задачу и нажать на свободное место на таймлайне:
 * на телефоне это быстрее и не промахивается.
 */
@Composable
fun UnscheduledSheet(
    tasks: List<VaultTask>,
    projects: List<LifeEntry>,
    selectedTaskId: Long?,
    suggestedSlots: List<Int>,
    onSelectTask: (VaultTask?) -> Unit,
    onPlaceAt: (task: VaultTask, startMinute: Int) -> Unit,
    onAddTask: (title: String, durationMinutes: Int, project: LifeEntry?, onResult: (Boolean) -> Unit) -> Unit,
    onAssignProject: (VaultTask, LifeEntry?) -> Unit,
    onRenameTask: (task: VaultTask, title: String) -> Unit,
    onSetDuration: (task: VaultTask, durationMinutes: Int) -> Unit,
    onToggleTask: (task: VaultTask) -> Unit,
    onDeleteTask: (task: VaultTask) -> Unit,
    modifier: Modifier = Modifier,
    creationMinute: Int? = null,
    creationLift: Float = 0f,
    creationError: String? = null,
    onCancelCreation: () -> Unit = {},
    onAddTaskAt: (String, Int, LifeEntry?, Int, (Boolean) -> Unit) -> Unit = { _, _, _, _, result -> result(false) },
) {
    val density = LocalDensity.current
    val projectChoices = remember(projects) {
        // Keep every known title for existing task links, including draft/closed projects.
        // The picker separately limits which projects can be newly selected.
        projects.filter { it.kind == LifeKind.PROJECT }
    }
    val addFormState = rememberSaveableStateHolder()
    // Keep the in-flight form outside the conditional footer: collapsing the sheet
    // or selecting a task must not detach the result from the current draft.
    var addTitle by rememberSaveable { mutableStateOf("") }
    var addDuration by rememberSaveable { mutableIntStateOf(DEFAULT_TASK_MINUTES) }
    var addProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var addPending by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // The same surface and draft travel from the footer to the center and back.
    // Retain the creation content until the return animation finishes.
    var displayedCreationMinute by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(creationMinute) {
        if (creationMinute != null) {
            displayedCreationMinute = creationMinute
            addDuration = addDuration.coerceIn(MIN_DURATION, minOf(MAX_DURATION, DAY_END_MINUTE - creationMinute))
        }
    }
    LaunchedEffect(creationMinute, creationLift == 0f) {
        if (creationMinute == null && creationLift == 0f) displayedCreationMinute = null
    }

    fun submit(startMinute: Int? = null) {
        if (addPending || addTitle.isBlank()) return
        val submittedTitle = addTitle
        val submittedDuration = addDuration
        val submittedProjectId = addProjectId
        val submittedProject = projectChoices.firstOrNull { it.id == submittedProjectId }
        if (submittedProjectId != null && submittedProject == null) return
        addPending = true
        val result: (Boolean) -> Unit = { success ->
            addPending = false
            if (success && addTitle == submittedTitle && addDuration == submittedDuration && addProjectId == submittedProjectId) {
                addTitle = ""
                addProjectId = null
                if (startMinute != null) {
                    addDuration = DEFAULT_TASK_MINUTES
                    focusManager.clearFocus()
                }
            }
        }
        if (startMinute == null) onAddTask(submittedTitle, submittedDuration, submittedProject, result)
        else onAddTaskAt(submittedTitle, submittedDuration, submittedProject, startMinute, result)
    }
    // Размер берём у окна, а не у конфигурации: screenHeightDp при edge-to-edge
    // отдаёт не то, что реально досталось экрану, и шторка схлопывалась в заголовок.
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    // Высота по содержимому: пустой пул не должен занимать треть экрана,
    // а список из десяти задач — упираться в потолок в 320dp.
    val visibleRows = tasks.size.coerceIn(0, 5)
    val listHeight = (HEADER_HEIGHT + ROW_HEIGHT * visibleRows + ADD_FORM_HEIGHT)
        .coerceAtMost(minOf(400.dp, windowHeight * 0.46f))
        .coerceAtLeast(HEADER_HEIGHT + ADD_FORM_HEIGHT)
    // Запас под панель выбранной задачи сюда не входит: у неё своя высота,
    // а иначе форма добавления висела с полосой пустоты под ползунком.
    val fullHeight = (listHeight + SLIDER_HEIGHT + PROJECT_ROW_HEIGHT + 16.dp)
        .coerceAtMost(minOf(520.dp, windowHeight * 0.62f))
        .coerceAtLeast(listHeight)

    val selected = tasks.firstOrNull { it.id == selectedTaskId }

    // У панели выбранной задачи своя высота: она не зависит от длины пула,
    // а высота списка оставляла под ней полосу пустоты.
    fun heightOf(mode: SheetMode): Dp = when {
        creationMinute != null -> if (creationError == null) 256.dp else 316.dp
        displayedCreationMinute != null -> HEADER_HEIGHT
        mode == SheetMode.COLLAPSED -> HEADER_HEIGHT
        selected != null -> PLACEMENT_HEIGHT
        mode == SheetMode.LIST -> listHeight
        else -> fullHeight
    }

    // Экран открывается ради таймлайна, а не ради пула: раскрытая шторка
    // съедала бы у него треть высоты ещё до того, как её попросили.
    var mode by rememberSaveable { mutableStateOf(SheetMode.COLLAPSED) }

    // Пока палец на экране, высоту держит обычное состояние и меняет её синхронно.
    // Доводка живёт в Animatable, и перед передачей управления анимация ставится
    // в ту же точку, где палец отпустил. Иначе шторка дёргается: раньше каждый шаг
    // перетаскивания запускал свою корутину, они обгоняли доводку и роняли высоту
    // назад, а потом она заново ехала вверх.
    val settle = remember { Animatable(heightOf(SheetMode.COLLAPSED), Dp.VectorConverter) }
    var dragHeight by remember { mutableStateOf<Dp?>(null) }
    val height = dragHeight ?: settle.value

    // selectedTaskId в ключах обязателен: выбор задачи меняет нужную высоту,
    // даже когда режим остался прежним.
    LaunchedEffect(mode, selectedTaskId, listHeight, fullHeight, creationMinute, displayedCreationMinute, creationError) {
        if (dragHeight == null) settle.animateTo(heightOf(mode), SHEET_SPRING)
    }

    // Выбрали задачу — шторка раскрывается под панель размещения, а по закрытию
    // возвращается ровно в то положение, в котором была: иначе после каждого выбора
    // она оставалась раскрытой с ползунком наружу.
    var modeBeforeSelection by rememberSaveable { mutableStateOf<SheetMode?>(null) }
    LaunchedEffect(selectedTaskId, creationMinute) {
        if (creationMinute != null) {
            modeBeforeSelection = null
            mode = SheetMode.COLLAPSED
        } else if (selectedTaskId != null) {
            if (modeBeforeSelection == null) modeBeforeSelection = mode
            mode = SheetMode.FULL
        } else {
            modeBeforeSelection?.let { mode = it }
            modeBeforeSelection = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(FocusRadius.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (displayedCreationMinute != null) Modifier.pointerInput(Unit) {
                // Empty padding inside the card belongs to the form, not the backdrop.
                detectTapGestures { }
            } else Modifier),
    ) {
        val anchor = displayedCreationMinute
        if (anchor != null) {
            Row(
                Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = SHEET_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${formatMinuteOfDay(anchor)} — ${formatMinuteOfDay(anchor + addDuration.coerceAtMost(DAY_END_MINUTE - anchor))}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall.tabularNums(),
                    color = MaterialTheme.colorScheme.primary,
                )
                Box(Modifier.size(40.dp).clip(FocusRadius.pill)
                    .clickable(enabled = !addPending, onClick = onCancelCreation), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Отменить создание задачи", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                addFormState.SaveableStateProvider("new-task") {
                    AddTaskForm(
                        projects = projectChoices,
                        expanded = true,
                        onExpand = {},
                        title = addTitle,
                        onTitleChange = { if (!addPending) addTitle = it },
                        duration = addDuration.coerceAtMost(DAY_END_MINUTE - anchor),
                        onDurationChange = { if (!addPending) addDuration = it },
                        projectId = addProjectId,
                        onProjectChange = { if (!addPending) addProjectId = it },
                        pending = addPending,
                        onSubmit = { if (creationMinute != null) submit(anchor) },
                        maxDuration = minOf(MAX_DURATION, DAY_END_MINUTE - anchor),
                        autoFocus = creationMinute != null && creationLift > 0.95f,
                    )
                }
                creationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = SHEET_PADDING, vertical = SHEET_GAP))
                }
            }
            return@Column
        }

        SheetHeader(
            tasks = tasks,
            mode = mode,
            onToggleMode = {
                mode = if (mode == SheetMode.COLLAPSED) SheetMode.LIST else SheetMode.COLLAPSED
            },
            modifier = Modifier.draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    // Синхронно, без корутины: палец не должен ждать очереди.
                    dragHeight = ((dragHeight ?: settle.value) - with(density) { delta.toDp() })
                        .coerceIn(HEADER_HEIGHT, maxOf(fullHeight, if (selected != null) PLACEMENT_HEIGHT else HEADER_HEIGHT))
                },
                onDragStopped = { velocity ->
                    val released = dragHeight ?: settle.value
                    // Ближайшее из трёх положений, с поправкой на бросок: рывок вниз
                    // должен закрывать шторку, даже если палец не дошёл до середины.
                    val projected = released - with(density) { (velocity * 0.1f).toDp() }
                    val target = SheetMode.entries.minByOrNull { candidate ->
                        kotlin.math.abs((heightOf(candidate) - projected).value)
                    } ?: SheetMode.LIST

                    // Порядок важен: сперва ставим анимацию туда, где отпустили,
                    // и только потом отдаём ей управление — иначе будет кадр со скачком.
                    settle.snapTo(released)
                    dragHeight = null
                    mode = target
                    settle.animateTo(heightOf(target), SHEET_SPRING)
                },
            ),
        )

        if (height <= HEADER_HEIGHT + 1.dp) return@Column

        if (selected != null) {
            PlacementPanel(
                task = selected,
                projects = projectChoices,
                onAssignProject = { onAssignProject(selected, it) },
                suggestedSlots = suggestedSlots,
                onPlaceAt = { minute -> onPlaceAt(selected, minute) },
                onRename = { title -> onRenameTask(selected, title) },
                onSetDuration = { minutes -> onSetDuration(selected, minutes) },
                onDelete = { onDeleteTask(selected) },
                onCancel = { onSelectTask(null) },
            )
        } else {
            PoolList(
                tasks = tasks,
                projects = projectChoices,
                onSelectTask = onSelectTask,
                onToggleTask = onToggleTask,
                modifier = Modifier.weight(1f),
                footer = {
                    addFormState.SaveableStateProvider("new-task") {
                        AddTaskForm(
                            projects = projectChoices,
                            expanded = mode == SheetMode.FULL,
                            onExpand = { mode = SheetMode.FULL },
                            title = addTitle,
                            onTitleChange = { addTitle = it },
                            duration = addDuration,
                            onDurationChange = { addDuration = it },
                            projectId = addProjectId,
                            onProjectChange = { addProjectId = it },
                            pending = addPending,
                            onSubmit = { submit() },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun SheetHeader(
    tasks: List<VaultTask>,
    mode: SheetMode,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalMinutes = tasks.sumOf { it.durationMinutes ?: DEFAULT_TASK_MINUTES }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .clickable(onClick = onToggleMode)
            .padding(horizontal = SHEET_PADDING),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(FocusRadius.pill)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SHEET_GAP),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (tasks.isEmpty()) {
                    "Все задачи расставлены"
                } else {
                    "${tasks.size} ${pluralTasks(tasks.size)} без времени"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (tasks.isNotEmpty()) {
                Text(
                    text = formatDuration(totalMinutes),
                    style = MaterialTheme.typography.labelLarge.tabularNums(),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (mode == SheetMode.COLLAPSED) {
                Text(
                    text = "потяни вверх",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun PoolList(
    tasks: List<VaultTask>,
    projects: List<LifeEntry>,
    onSelectTask: (VaultTask) -> Unit,
    onToggleTask: (VaultTask) -> Unit,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit,
) {
    val projectsById = remember(projects) { projects.associateBy { it.id } }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(tasks, key = { it.id }) { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SHEET_PADDING - 8.dp)
                    .clip(FocusRadius.field)
                    .clickable { onSelectTask(task) }
                    .padding(horizontal = 8.dp, vertical = SHEET_GAP - 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SHEET_GAP),
            ) {
                // Отметить сделанной можно прямо из пула: не всё, что запланировано,
                // обязательно попадает на таймлайн.
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(FocusRadius.pill)
                        .clickable { onToggleTask(task) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(FocusRadius.chip)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (task.done) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    task.projectId?.let { projectId ->
                        Text(
                            text = projectsById[projectId]?.title ?: "Проект недоступен",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = formatDuration(task.durationMinutes ?: DEFAULT_TASK_MINUTES),
                    style = MaterialTheme.typography.labelMedium.tabularNums(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        item(key = "add-task") { footer() }
    }
}

/** Выбранная задача: размещение, название, длительность и необязательный проект. */
@Composable
private fun PlacementPanel(
    task: VaultTask,
    projects: List<LifeEntry>,
    onAssignProject: (LifeEntry?) -> Unit,
    suggestedSlots: List<Int>,
    onPlaceAt: (Int) -> Unit,
    onRename: (String) -> Unit,
    onSetDuration: (Int) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val titleFocus = remember { FocusRequester() }
    var title by rememberSaveable(task.id) { mutableStateOf(task.title) }
    var duration by rememberSaveable(task.id) {
        mutableIntStateOf(task.durationMinutes ?: DEFAULT_TASK_MINUTES)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Вариантов времени может быть сколько угодно, а высота шторки ограничена:
            // без прокрутки кнопка удаления уезжала за нижнюю кромку.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SHEET_PADDING),
    ) {
        InlineProjectPicker(
            projects = projects,
            selectedProjectId = task.projectId,
            onSelect = { project -> if (project?.id != task.projectId) onAssignProject(project) },
            onRestoreEditorFocus = { titleFocus.requestFocus() },
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "Название",
                        container = false,
                        modifier = Modifier.weight(1f).focusRequester(titleFocus),
                    )
                    // Удаление — иконкой в той же строке. Кнопка во всю ширину внизу занимала
                    // место и притягивала взгляд сильнее, чем выбор времени, ради которого
                    // панель и открывают.
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(FocusRadius.pill)
                            .clickable(onClick = onDelete),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Удалить задачу",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(FocusRadius.pill)
                            .clickable {
                                if (title.isNotBlank() && title != task.title) onRename(title)
                                onCancel()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Закрыть",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DurationRow(
                    minutes = duration,
                    onChange = { duration = it },
                    onChangeFinished = { onSetDuration(duration) },
                )
            }
        }

        if (suggestedSlots.isEmpty()) {
            Text(
                text = "Свободного окна такой длины нет — сократи задачу или освободи место",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SHEET_GAP),
            )
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = SHEET_GAP, bottom = SHEET_GAP),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestedSlots.forEach { minute ->
                    FocusButton(
                        text = formatMinuteOfDay(minute),
                        style = FocusButtonStyle.Tonal,
                        onClick = { onPlaceAt(minute) },
                    )
                }
            }
        }
    }

}

@Composable
private fun AddTaskForm(
    projects: List<LifeEntry>,
    expanded: Boolean,
    onExpand: () -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    duration: Int,
    onDurationChange: (Int) -> Unit,
    projectId: String?,
    onProjectChange: (String?) -> Unit,
    pending: Boolean,
    onSubmit: () -> Unit,
    maxDuration: Int = MAX_DURATION,
    autoFocus: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
    val project = projects.firstOrNull { it.id == projectId }
    // An unavailable saved choice is explicit; never silently drop a project.
    val projectAvailable = projectId == null || project != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_PADDING, vertical = SHEET_GAP / 2),
    ) {
        val showProject = expanded || title.isNotBlank()
        InlineProjectPicker(
            projects = projects,
            selectedProjectId = projectId,
            onSelect = { onProjectChange(it?.id) },
            enabled = !pending,
            showProject = showProject,
            onRestoreEditorFocus = { focusRequester.requestFocus() },
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SHEET_GAP),
                ) {
                    FocusTextField(
                        value = title,
                        onValueChange = {
                            onTitleChange(it)
                            if (it.isNotBlank()) onExpand()
                        },
                        placeholder = "Новая задача",
                        container = false,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    )
                    val enabled = title.isNotBlank() && projectAvailable && !pending
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(FocusRadius.pill)
                            .background(
                                if (enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                            )
                            .clickable(enabled = enabled, role = Role.Button, onClick = onSubmit)
                            .semantics { contentDescription = if (pending) "Сохраняю задачу" else "Добавить задачу" },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (pending) CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) else Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (enabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                }
                if (showProject) DurationRow(minutes = duration, onChange = onDurationChange, maxDuration = maxDuration)
            }
        }
        if (showProject && !projectAvailable) Text("Выбери другой проект или «Без проекта».",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Длительность одной строкой: значение слева, ползунок занимает остальное.
 *
 * Подписи «Длительность» нет намеренно — ползунок с минутами рядом не нуждается
 * в объяснении, а вынесенное вправо число висело под кнопкой добавления и читалось
 * как её часть. Фиксированная ширина у значения не даёт ползунку дёргаться,
 * когда «45 мин» сменяется на «1 ч 30 мин».
 */
@Composable
private fun DurationRow(
    minutes: Int,
    onChange: (Int) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
    maxDuration: Int = MAX_DURATION,
) {
    var raw by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DURATION_ROW_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SHEET_GAP),
    ) {
        Text(
            text = formatDuration(minutes),
            style = MaterialTheme.typography.labelLarge.tabularNums(),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.width(DURATION_LABEL_WIDTH),
        )
        // The last slot of a day may have only one valid duration.
        val sliderMax = maxOf(MIN_DURATION + SNAP_MINUTES, maxDuration)
        Slider(
            value = raw.coerceIn(MIN_DURATION.toFloat(), sliderMax.toFloat()),
            onValueChange = {
                raw = it
                onChange(snapMinutes(it.toInt()).coerceIn(MIN_DURATION, maxDuration))
            },
            onValueChangeFinished = { onChangeFinished?.invoke() },
            valueRange = MIN_DURATION.toFloat()..sliderMax.toFloat(),
            steps = (sliderMax - MIN_DURATION) / SNAP_MINUTES - 1,
            enabled = maxDuration > MIN_DURATION,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.4f),
                inactiveTickColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/** «45 мин», «1 ч», «1 ч 30 мин» — читается быстрее, чем «90м». */
internal fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest мин"
        rest == 0 -> "$hours ч"
        else -> "$hours ч $rest мин"
    }
}

private fun pluralTasks(count: Int): String {
    val mod10 = count % 10
    val mod100 = count % 100
    return when {
        mod100 in 11..14 -> "задач"
        mod10 == 1 -> "задача"
        mod10 in 2..4 -> "задачи"
        else -> "задач"
    }
}

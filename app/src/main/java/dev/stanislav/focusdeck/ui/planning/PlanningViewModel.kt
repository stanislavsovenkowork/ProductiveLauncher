package dev.stanislav.focusdeck.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.life.LifeTaskParents
import dev.stanislav.focusdeck.data.life.LifeRepository
import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeSnapshot
import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultState
import dev.stanislav.focusdeck.data.vault.VaultTask
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanningUiState(
    val vaultState: VaultState = VaultState.NOT_CONNECTED,
    val selectedDate: LocalDate = LocalDate.now(),
    val daysWithNotes: Set<LocalDate> = emptySet(),
    val scheduled: List<ScheduledItem> = emptyList(),
    val placed: List<PlacedItem> = emptyList(),
    val unscheduled: List<VaultTask> = emptyList(),
    val freeWindows: List<FreeWindow> = emptyList(),
    val selectedTaskId: Long? = null,
    val nowMinuteOfDay: Int = 0,
    val notePath: String? = null,
    val busy: Boolean = false,
    val projects: List<LifeEntry> = emptyList(),
    val error: String? = null,
) {
    /** Линия «сейчас» имеет смысл только на сегодняшнем дне. */
    val isToday: Boolean get() = selectedDate == LocalDate.now()

    val plannedMinutes: Int get() = scheduled.sumOf { it.durationMinutes }

    val selectedTask: VaultTask? get() = unscheduled.firstOrNull { it.id == selectedTaskId }

    val highlightDurationMinutes: Int?
        get() = selectedTask?.let { it.durationMinutes ?: DEFAULT_TASK_MINUTES }

    val suggestedSlots: List<Int>
        get() = if (selectedDate.isBefore(LocalDate.now())) emptyList() else selectedTask?.let { task ->
            suggestSlots(
                windows = freeWindows,
                durationMinutes = task.durationMinutes ?: DEFAULT_TASK_MINUTES,
                afterMinute = if (isToday) nowMinuteOfDay else DAY_START_MINUTE,
            )
        }.orEmpty()

    val summary: String
        get() = when (vaultState) {
            VaultState.NOT_CONNECTED -> "Vault не подключён"
            VaultState.NOTE_MISSING -> "Заметки за этот день нет"
            VaultState.ERROR -> "Заметку не удалось прочитать"
            VaultState.READY -> if (scheduled.isEmpty()) {
                "День пока пустой"
            } else {
                val freeHours = freeWindows.sumOf { it.durationMinutes } / 60
                "Запланировано %d:%02d · свободно ~%d ч".format(
                    plannedMinutes / 60,
                    plannedMinutes % 60,
                    freeHours,
                )
            }
        }
}

const val DEFAULT_TASK_MINUTES = 50

class PlanningViewModel(private val dayStore: VaultDayStore, private val life: LifeRepository) : ViewModel() {

    private val selectedTaskId = MutableStateFlow<Long?>(null)
    private val nowMinute = MutableStateFlow(currentMinuteOfDay())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PlanningUiState> = combine(
        dayStore.note,
        dayStore.state,
        selectedTaskId,
        nowMinute,
        busy,
        dayStore.selectedDate,
        dayStore.datesWithNotes,
        life.snapshot,
        error,
    ) { values ->
        val note = values[0] as DailyNote?
        val vaultState = values[1] as VaultState
        val selected = values[2] as Long?
        val now = values[3] as Int
        val working = values[4] as Boolean
        val date = values[5] as LocalDate

        @Suppress("UNCHECKED_CAST")
        val noteDays = values[6] as Set<LocalDate>

        val tasks = note?.tasks.orEmpty()
        val scheduled = buildScheduled(tasks)
        PlanningUiState(
            vaultState = vaultState,
            scheduled = scheduled,
            placed = layoutLanes(scheduled),
            unscheduled = tasks.filter { !it.isScheduled && !it.done },
            freeWindows = computeFreeWindows(scheduled),
            selectedTaskId = selected,
            nowMinuteOfDay = now,
            notePath = note?.path,
            busy = working,
            selectedDate = date,
            daysWithNotes = noteDays,
            projects = (values[7] as LifeSnapshot).entries.filter { it.kind == LifeKind.PROJECT },
            error = values[8] as String?,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningUiState())

    init {
        refreshNote()
    }

    private fun buildScheduled(tasks: List<VaultTask>): List<ScheduledItem> {
        val taskBlocks = tasks
            .filter { it.isScheduled && !it.done }
            .map { task ->
                ScheduledItem.TaskBlock(
                    task = task,
                    startMinuteOfDay = task.startMinuteOfDay!!,
                    durationMinutes = task.durationMinutes ?: DEFAULT_TASK_MINUTES,
                )
            }
        return taskBlocks.sortedBy { it.startMinuteOfDay }
    }

    /** Пересчитать положение линии «сейчас». */
    fun refreshNow() {
        nowMinute.value = currentMinuteOfDay()
    }

    /** Перечитать заметку — после возврата в приложение или по жесту обновления. */
    fun refreshNote() {
        viewModelScope.launch { withBusy { dayStore.refresh() } }
    }

    fun createNote() {
        viewModelScope.launch { withBusy { dayStore.createNote() } }
    }

    fun selectDate(date: LocalDate, createIfMissing: Boolean = false) {
        viewModelScope.launch {
            selectedTaskId.value = null
            withBusy {
                if (createIfMissing) {
                    dayStore.editNoteOn(date, createIfMissing = true) { it.lines.joinToString("\n") }
                }
                dayStore.selectDate(date)
            }
        }
    }

    /** Обновляет отметки заведённых дней. Один обход папки, зовётся при открытии полосы. */
    fun loadNoteDates() {
        viewModelScope.launch { dayStore.loadNoteDates() }
    }

    fun selectTask(task: VaultTask?) {
        selectedTaskId.value = task?.id
    }

    fun placeTaskAt(task: VaultTask, startMinute: Int) {
        viewModelScope.launch {
            withBusy {
                dayStore.scheduleTask(
                    task = task,
                    startMinuteOfDay = startMinute,
                    durationMinutes = task.durationMinutes ?: DEFAULT_TASK_MINUTES,
                )
            }
            selectedTaskId.value = null
        }
    }

    fun moveTask(taskId: Long, newStartMinute: Int) {
        val task = scheduledTask(taskId) ?: return
        viewModelScope.launch {
            withBusy {
                dayStore.scheduleTask(task, newStartMinute, task.durationMinutes)
            }
        }
    }

    fun resizeTask(taskId: Long, startMinute: Int, durationMinutes: Int) {
        val task = scheduledTask(taskId) ?: return
        viewModelScope.launch {
            withBusy { dayStore.scheduleTask(task, startMinute, durationMinutes) }
        }
    }

    /** Снять задачу с расписания — обратный жест к размещению. */
    fun unscheduleTask(taskId: Long) {
        val task = scheduledTask(taskId) ?: return
        viewModelScope.launch {
            withBusy { dayStore.unscheduleTask(task) }
        }
    }



    /**
     * Добавляет задачу и сразу делает её выбранной.
     *
     * Почти всегда следующее действие после «плюса» — поставить задачу на время,
     * а раньше для этого приходилось искать её в пуле и нажимать второй раз.
     * Ключ задачи — номер строки в файле, и до записи он неизвестен, поэтому
     * ищем её в свежей заметке по названию: добавление дописывает строку в конец
     * секции, так что нужная — последняя совпавшая.
     */
    fun addTask(title: String, durationMinutes: Int, project: LifeEntry? = null, onResult: (Boolean) -> Unit = {}) {
        createTask(title, durationMinutes, project, startMinute = null, onResult = onResult)
    }

    fun addTaskAt(title: String, durationMinutes: Int, project: LifeEntry?, startMinute: Int, onResult: (Boolean) -> Unit) {
        createTask(title, durationMinutes, project, startMinute, onResult)
    }

    private fun createTask(title: String, durationMinutes: Int, project: LifeEntry?, startMinute: Int?, onResult: (Boolean) -> Unit) {
        val trimmed = title.replace('\n', ' ').replace('\r', ' ').trim()
        val stableId = java.util.UUID.randomUUID().toString()
        val targetDay = dayStore.selectedDate.value
        val root = dayStore.rootIdentity
        viewModelScope.launch {
            val saved = withBusy {
                require(trimmed.isNotBlank()) { "Введи название задачи" }
                require(durationMinutes > 0 && (startMinute == null ||
                    (startMinute in 0 until DAY_END_MINUTE && durationMinutes <= DAY_END_MINUTE - startMinute))) {
                    "Задача должна закончиться до полуночи"
                }
                val parents = project?.let { life.taskParents(it) } ?: LifeTaskParents(null, null, null)
                check(dayStore.addTask(trimmed, durationMinutes, startMinuteOfDay = startMinute, projectId = parents.projectId,
                    goalId = parents.goalId, areaId = parents.areaId, stableId = stableId, expectedRoot = root, targetDay = targetDay, noteLink = parents.noteLink)) {
                    "Не удалось добавить задачу. Обнови заметку и попробуй ещё раз."
                }
            }
            onResult(saved)
            if (saved && dayStore.selectedDate.value == targetDay) selectedTaskId.value = if (startMinute != null) null
                else dayStore.note.value?.tasks?.singleOrNull { it.stableId == stableId }?.id
        }
    }

    fun assignProject(task: VaultTask, project: LifeEntry?) {
        val targetDay = dayStore.selectedDate.value
        val root = dayStore.rootIdentity
        viewModelScope.launch { withBusy {
            val parents = project?.let { life.taskParents(it) } ?: LifeTaskParents(null, null, null)
            check(dayStore.linkTask(task, parents.projectId, parents.goalId, parents.areaId, expectedRoot = root, targetDay = targetDay, noteLink = parents.noteLink)) {
                "Не удалось изменить проект задачи. Обнови заметку."
            }
        } }
    }

    fun clearError() { error.value = null }

    fun renameTask(task: VaultTask, title: String) {
        viewModelScope.launch { withBusy { dayStore.renameTask(task, title) } }
    }

    /** Длительность задачи в пуле — без времени начала: время она получит при размещении. */
    fun setTaskDuration(task: VaultTask, durationMinutes: Int) {
        viewModelScope.launch {
            withBusy {
                if (task.isScheduled) {
                    dayStore.scheduleTask(task, task.startMinuteOfDay!!, durationMinutes)
                } else {
                    dayStore.setDuration(task, durationMinutes)
                }
            }
        }
    }

    fun deleteTask(task: VaultTask) {
        viewModelScope.launch {
            withBusy { dayStore.deleteTask(task) }
            if (selectedTaskId.value == task.id) selectedTaskId.value = null
        }
    }

    fun toggleTask(task: VaultTask, done: Boolean) {
        viewModelScope.launch { withBusy { dayStore.toggleTask(task, done) } }
    }

    private fun scheduledTask(taskId: Long): VaultTask? = uiState.value.scheduled
        .filterIsInstance<ScheduledItem.TaskBlock>()
        .firstOrNull { it.task.id == taskId }
        ?.task

    /**
     * Запись в заметку — это чтение файла, правка и запись обратно, то есть заметно
     * дольше, чем строчка в базе. Флаг нужен, чтобы экран показал, что идёт работа,
     * а не выглядел зависшим на пол-секунды.
     */
    private suspend fun withBusy(block: suspend () -> Unit): Boolean {
        busy.value = true
        error.value = null
        return try {
            block()
            true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error.value = failure.message ?: "Не удалось сохранить изменение"
            false
        } finally {
            busy.value = false
        }
    }

    private fun currentMinuteOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

    class Factory(private val dayStore: VaultDayStore, private val life: LifeRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlanningViewModel(dayStore, life) as T
    }
}

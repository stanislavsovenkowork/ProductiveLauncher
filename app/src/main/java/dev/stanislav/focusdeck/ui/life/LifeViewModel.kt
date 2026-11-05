package dev.stanislav.focusdeck.ui.life

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.life.*
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultTask
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WeekFacts(
    val start: LocalDate,
    val end: LocalDate,
    val availableDays: Int = 0,
    val elapsedDays: Int = 0,
    val scheduled: Int = 0,
    val completedScheduled: Int = 0,
    val journalDays: Int = 0,
    val loading: Boolean = false,
    val failedDays: Int = 0,
)

/** One-shot moment worth marking: something real was finished. */
data class Celebration(val title: String, val subtitle: String, val big: Boolean, val id: Long = System.nanoTime())

data class LifeUiState(
    val snapshot: LifeSnapshot = LifeSnapshot(),
    val kindFilter: LifeKind? = null,
    val selectedId: String? = null,
    val draft: LifeEntry? = null,
    val base: LifeEntry? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val openPlanDate: LocalDate? = null,
    val todayTasks: List<VaultTask> = emptyList(),
    val weekFacts: WeekFacts? = null,
    val showArchived: Boolean = false,
    val draftSaved: Boolean = true,
    val draftPurpose: String = "edit",
    val activity: LifeActivity? = null,
    val activityLoading: Boolean = false,
    /** Background writes that must not block the screen (checkboxes, next step). */
    val syncing: Boolean = false,
    val celebration: Celebration? = null,
) {
    fun progressOf(entry: LifeEntry): LifeProgress? = LifeProgressRules.of(entry, snapshot.entries)
    fun stepsOf(entry: LifeEntry): List<LifeStep> = activity?.stepsFor(entry).orEmpty()

    /** The open daily task that carries this entry's next action, if it is already planned. */
    fun plannedNextStep(entry: LifeEntry): LifeStep? {
        val next = entry.text(LifeField.NEXT_ACTION).trim().takeIf { it.isNotEmpty() } ?: return null
        return stepsOf(entry).filter { it.title == next && !it.date.isBefore(LocalDate.now()) }
            .sortedWith(compareBy<LifeStep> { it.done }.thenBy { it.date }).firstOrNull()
    }

    val selected: LifeEntry? get() = snapshot.entry(selectedId)
    val visibleEntries: List<LifeEntry> get() = snapshot.entries
        .filter { (kindFilter == null || it.kind == kindFilter) && (showArchived || !it.archived) }
        .sortedWith(compareBy<LifeEntry> { if (it.inUse) 0 else if (it.status == LifeStatus.DRAFT) 1 else 2 }.thenBy { it.title.lowercase() })
    val relatedEntries: List<LifeEntry> get() = snapshot.entries.filter { child ->
        val current = runCatching { LifeHierarchy.canonical(child, snapshot.entries) }.getOrDefault(child)
        !child.archived && when (selected?.kind) {
            LifeKind.AREA -> current.areaId == selectedId
            LifeKind.GOAL -> current.goalId == selectedId
            LifeKind.PROJECT -> current.projectId == selectedId
            else -> false
        }
    }
    val linkedTasks: List<VaultTask> get() = todayTasks.filter { task ->
        when (selected?.kind) {
            LifeKind.AREA -> task.areaId == selectedId
            LifeKind.GOAL -> task.goalId == selectedId
            LifeKind.PROJECT -> task.projectId == selectedId
            else -> false
        }
    }
}

class LifeViewModel(
    private val repository: LifeRepository,
    private val dayStore: VaultDayStore,
    context: Context,
) : ViewModel() {
    private val prefs = context.getSharedPreferences("life-draft", Context.MODE_PRIVATE)
    private val restoredDraft = prefs.getString("draft", null)?.let { LifeDraftCodec.decode(it, prefs.getString("path", "").orEmpty()) }
    private val restoredBase = prefs.getString("base", null)?.let { LifeNoteCodec.parse(prefs.getString("path", "").orEmpty(), it)?.copy(sourceRoot = prefs.getString("sourceRoot", "").orEmpty()) }
    private val _uiState = MutableStateFlow(LifeUiState(draft = restoredDraft, base = restoredBase,
        draftPurpose = prefs.getString("purpose", "edit").orEmpty().takeIf { it in DRAFT_PURPOSES } ?: "edit",
        error = if (restoredDraft == null && prefs.contains("draft")) "Локальный черновик не удалось открыть. Исходная копия сохранена на устройстве; не удаляй её." else null))
    val uiState: StateFlow<LifeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { repository.snapshot.collect { snapshot ->
            _uiState.value = _uiState.value.copy(snapshot = snapshot)
            val state = _uiState.value
            val draft = state.draft
            val saved = snapshot.entry(draft?.id)
            if (!state.busy && state.base == null && draft != null && saved != null &&
                LifeRules.sameDraftContent(draft, saved)) {
                withContext(Dispatchers.IO) { prefs.edit().clear().commit() }
                _uiState.value = _uiState.value.copy(draft = null, base = null, selectedId = saved.id)
            }
        } }
        viewModelScope.launch { dayStore.todayNote.collect { note ->
            val activity = _uiState.value.activity
            _uiState.value = _uiState.value.copy(todayTasks = note?.tasks.orEmpty(),
                activity = if (note != null && activity != null) activity.withDay(note) else activity)
        } }
    }

    private var activityJob: Job? = null
    /** Four weeks of linked daily tasks: the factual record progress is drawn from. */
    fun loadActivity() {
        if (activityJob?.isActive == true) return
        val root = dayStore.rootIdentity ?: return
        activityJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(activityLoading = true)
            val today = LocalDate.now()
            val from = today.minusDays(LifeActivity.WINDOW_DAYS - 1)
            val to = today.plusDays(7)
            runCatching { dayStore.notesBetween(from, to) }
                .onSuccess { read ->
                    if (dayStore.rootIdentity != root) return@onSuccess
                    _uiState.value = _uiState.value.copy(activity = LifeActivity(from, to,
                        read.notes.values.flatMap(LifeActivity::stepsOf), read.notes.size, read.failed.size))
                }
                .onFailure { if (it is CancellationException) throw it }
            _uiState.value = _uiState.value.copy(activityLoading = false)
        }
    }

    fun consumeCelebration() { _uiState.value = _uiState.value.copy(celebration = null) }

    private val quickWrites = Mutex()
    /**
     * Small edits of a saved entry (a milestone tick, a new next step) are queued, not dropped
     * like [action]: several quick taps in a row must all land. The screen shows the change at
     * once; a failed write puts the file's version back.
     */
    private fun quickEdit(entryId: String, transform: (LifeEntry) -> LifeEntry, after: suspend (LifeEntry) -> Unit = {}) {
        val shown = _uiState.value.snapshot.entry(entryId) ?: return
        val optimistic = transform(shown)
        _uiState.value = _uiState.value.copy(snapshot = _uiState.value.snapshot.copy(
            entries = _uiState.value.snapshot.entries.map { if (it.id == entryId) optimistic else it }))
        viewModelScope.launch {
            quickWrites.withLock {
                _uiState.value = _uiState.value.copy(syncing = true, error = null)
                try {
                    val base = repository.snapshot.value.entry(entryId) ?: error("Запись не найдена. Обнови список.")
                    val saved = repository.save(base, transform(base))
                    after(saved)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(snapshot = repository.snapshot.value,
                        error = failure.message ?: "Не удалось сохранить изменение")
                } finally {
                    _uiState.value = _uiState.value.copy(syncing = false)
                }
            }
        }
    }

    fun toggleMilestone(entry: LifeEntry, milestone: Milestone, done: Boolean) {
        quickEdit(entry.id, { current ->
            val text = current.text(LifeField.MILESTONES)
            // Obsidian may have moved lines; find the same milestone by its text if needed.
            val line = Milestones.parse(text).firstOrNull { it.line == milestone.line && it.title == milestone.title }?.line
                ?: Milestones.parse(text).firstOrNull { it.title == milestone.title }?.line ?: return@quickEdit current
            current.copy(fields = current.fields + (LifeField.MILESTONES to Milestones.toggle(text, line, done, LocalDate.now())))
        }) { saved ->
            val all = Milestones.parse(saved.text(LifeField.MILESTONES))
            if (done && all.isNotEmpty() && all.all { it.done }) {
                celebrate(Celebration("Все этапы пройдены", saved.title, big = true))
            } else if (done) celebrate(Celebration("Этап закрыт", milestone.title, big = false))
        }
    }

    /** A dated measurement of the goal's metric; reaching the target is celebrated. */
    fun recordMetric(entry: LifeEntry, value: Double) {
        quickEdit(entry.id, { it.copy(fields = it.fields + (LifeField.METRIC to Metrics.record(it.text(LifeField.METRIC), LocalDate.now(), value))) }) { saved ->
            val metric = Metrics.parse(saved.text(LifeField.METRIC))
            if (metric?.fraction == 1f) celebrate(Celebration("Показатель достигнут", saved.title, big = true))
            else celebrate(Celebration("Замер записан", metric?.let { "${Metrics.format(value)} из ${Metrics.format(it.target)} ${it.unit}".trim() }.orEmpty(), big = false))
        }
    }

    fun addMilestone(entry: LifeEntry, title: String) {
        if (!title.any(Char::isLetterOrDigit)) return
        quickEdit(entry.id, { it.copy(fields = it.fields + (LifeField.MILESTONES to Milestones.add(it.text(LifeField.MILESTONES), title))) })
    }

    fun removeMilestone(entry: LifeEntry, milestone: Milestone) {
        quickEdit(entry.id, { current ->
            val text = current.text(LifeField.MILESTONES)
            val line = Milestones.parse(text).firstOrNull { it.title == milestone.title }?.line ?: return@quickEdit current
            current.copy(fields = current.fields + (LifeField.MILESTONES to Milestones.remove(text, line)))
        })
    }

    /** Replace the next action; optionally put it straight into a day of the plan. */
    fun setNextStep(entry: LifeEntry, text: String, planOn: LocalDate? = null, minutes: Int = dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES) {
        val clean = text.replace('\n', ' ').trim()
        if (!meaningfulLifeText(clean)) return
        quickEdit(entry.id, { it.copy(fields = it.fields + (LifeField.NEXT_ACTION to clean)) }) { saved ->
            if (planOn != null) planStep(saved, clean, planOn, minutes)
        }
    }

    /** Put an existing next action into the plan without leaving the screen. */
    fun planNextStep(entry: LifeEntry, date: LocalDate, minutes: Int = dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES, startMinute: Int? = null) {
        val title = entry.text(LifeField.NEXT_ACTION).trim()
        if (!meaningfulLifeText(title)) return
        viewModelScope.launch {
            quickWrites.withLock {
                _uiState.value = _uiState.value.copy(syncing = true, error = null)
                try { planStep(entry, title, date, minutes, startMinute) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    _uiState.value = _uiState.value.copy(error = failure.message ?: "Не удалось добавить шаг в план")
                } finally { _uiState.value = _uiState.value.copy(syncing = false) }
            }
        }
    }

    private suspend fun planStep(source: LifeEntry, title: String, date: LocalDate, minutes: Int, startMinute: Int? = null) {
        val root = dayStore.rootIdentity
        check(root != null && (source.sourceRoot.isBlank() || source.sourceRoot == root)) { "Папка Obsidian изменилась. Открой запись заново." }
        require(title.count { it.isLetterOrDigit() } >= 3) { "Назови конкретное действие" }
        val parents = repository.taskParents(source)
        val ok = dayStore.editNoteOn(date, createIfMissing = true, expectedRoot = root) { note ->
            val duplicate = note.tasks.any { !it.done && it.title == title && it.projectId == parents.projectId && it.goalId == parents.goalId && it.areaId == parents.areaId }
            if (duplicate) note.lines.joinToString("\n") else DailyNoteParser.addTask(note, title, startMinuteOfDay = startMinute,
                durationMinutes = minutes, projectId = parents.projectId, goalId = parents.goalId, areaId = parents.areaId, noteLink = parents.noteLink)
        }
        check(ok) { "Не удалось добавить шаг в заметку дня" }
        dayStore.noteOn(date)?.let { note ->
            _uiState.value = _uiState.value.copy(activity = _uiState.value.activity?.withDay(note))
        }
        _uiState.value = _uiState.value.copy(message = "В плане на ${planDayLabel(date)}" +
            (startMinute?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: ""))
    }

    private fun celebrate(celebration: Celebration) { _uiState.value = _uiState.value.copy(celebration = celebration) }

    private var refreshJob: Job? = null
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { repository.refresh() }
        loadActivity()
    }
    fun selectKind(kind: LifeKind?) { _uiState.value = _uiState.value.copy(kindFilter = kind, selectedId = null, error = null) }
    fun open(id: String) { _uiState.value = _uiState.value.copy(selectedId = id, error = null) }
    fun closeDetail() { _uiState.value = _uiState.value.copy(selectedId = null, error = null) }
    fun showArchived(show: Boolean) { _uiState.value = _uiState.value.copy(showArchived = show) }
    fun clearNotice() { _uiState.value = _uiState.value.copy(message = null) }
    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null, error = null) }
    fun consumePlanNavigation() { _uiState.value = _uiState.value.copy(openPlanDate = null) }

    fun create(kind: LifeKind, parent: LifeEntry? = uiState.value.selected) {
        if (_uiState.value.draft != null) return // Never silently overwrite an unfinished editor.
        val draft = LifeEntry.draft(kind).copy(
            sourceRoot = uiState.value.snapshot.rootKey.orEmpty(),
            areaId = when { parent?.kind == LifeKind.AREA -> parent.id; parent != null -> parent.areaId; else -> null },
            goalId = when {
                kind in listOf(LifeKind.PROJECT, LifeKind.DECISION) && parent?.kind == LifeKind.GOAL -> parent.id
                kind in listOf(LifeKind.PROJECT, LifeKind.DECISION) -> parent?.goalId
                else -> null
            },
            projectId = if (kind == LifeKind.DECISION && parent?.kind == LifeKind.PROJECT) parent.id else null,
            status = if (kind == LifeKind.AREA) LifeStatus.ACTIVE else LifeStatus.DRAFT,
        )
        _uiState.value = _uiState.value.copy(draft = draft, base = null, error = null, draftPurpose = "edit")
        persistDraft()
    }

    fun edit(entry: LifeEntry? = uiState.value.selected) {
        if (entry == null) return
        if (uiState.value.draft != null) return
        _uiState.value = _uiState.value.copy(draft = entry, base = entry, error = null, draftPurpose = "edit")
        persistDraft()
    }

    fun editForDecisionReview() {
        if (uiState.value.draft != null) return
        val entry = uiState.value.selected?.takeIf { it.kind == LifeKind.DECISION } ?: return
        _uiState.value = _uiState.value.copy(base = entry,
            draft = entry.copy(fields = entry.fields - LifeField.OUTCOME - LifeField.LESSON), error = null, draftPurpose = "review")
        persistDraft()
    }

    fun editForLifeReview() {
        if (uiState.value.draft != null) return
        val entry = uiState.value.selected?.takeIf { it.kind in listOf(LifeKind.AREA, LifeKind.GOAL, LifeKind.PROJECT) } ?: return
        _uiState.value = _uiState.value.copy(base = entry,
            draft = entry.copy(fields = entry.fields - LifeField.REVIEW_NOTE,
                reviewDate = entry.reviewDate?.takeIf { it.isAfter(LocalDate.now()) }), error = null, draftPurpose = "life-review")
        persistDraft()
    }

    fun setDraftPurpose(purpose: String) {
        require(purpose in DRAFT_PURPOSES)
        if (uiState.value.draft == null || uiState.value.busy) return
        _uiState.value = _uiState.value.copy(draftPurpose = purpose)
        persistDraft()
    }

    fun updateDraft(transform: (LifeEntry) -> LifeEntry) {
        val current = uiState.value.draft ?: return
        if (uiState.value.busy) return
        _uiState.value = _uiState.value.copy(draft = transform(current), error = null)
        persistDraft()
    }

    fun updateField(field: LifeField, value: String) = updateDraft { it.copy(fields = it.fields + (field to value)) }

    fun discardDraft() {
        if (uiState.value.busy) return
        prefs.edit { clear() }
        _uiState.value = _uiState.value.copy(draft = null, base = null, error = null)
    }

    fun saveDraft(
        status: LifeStatus? = null, recordDecisionReview: Boolean = false, recordLifeReview: Boolean = false,
        planAfterSave: Pair<LocalDate, Int>? = null,
    ) = action {
        val state = uiState.value
        val draft = state.draft ?: return@action
        val proposal = draft.copy(status = status ?: draft.status)
        // Persist the submitted intent for interrupted-create recovery without changing
        // the editor's status. A failed activation must still leave a DRAFT to save.
        suspend fun persistRecovery(entry: LifeEntry, base: LifeEntry?, purpose: String): Boolean = withContext(Dispatchers.IO) {
            val encoded = LifeDraftCodec.encode(entry)
            val editor = prefs.edit().putString("draft", encoded).putString("purpose", purpose)
                .putString("path", base?.path.orEmpty()).putString("sourceRoot", entry.sourceRoot)
            if (base != null) editor.putString("base", base.raw) else editor.remove("base")
            editor.commit()
        }
        val saved = try {
            check(persistRecovery(proposal, state.base, state.draftPurpose)) {
                "Не удалось сохранить локальный черновик. Скопируй текст перед выходом."
            }
            repository.save(state.base, proposal, recordDecisionReview, recordLifeReview)
        } catch (cancelled: CancellationException) {
            // The disk write may already have completed; retain its recovery intent.
            throw cancelled
        } catch (failure: Exception) {
            // No user text is replaced. Clear the failed transition from preferences,
            // including its status, before allowing another action in this editor.
            val restored = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { persistDraft(commit = true) }.getOrDefault(false)
            }
            _uiState.value = _uiState.value.copy(draftSaved = restored)
            if (!restored) throw IllegalStateException(
                "${failure.message ?: "Не удалось сохранить запись"}. Локальную копию черновика обновить не удалось. Скопируй текст перед выходом.", failure)
            throw failure
        }
        withContext(kotlinx.coroutines.NonCancellable) {
            val cleared = withContext(Dispatchers.IO) { runCatching { prefs.edit().clear().commit() }.getOrDefault(false) }
            if (!cleared) {
                // A failed clear also empties SharedPreferences in memory. Reinstall
                // the actual saved version so restart can recognize it without a create.
                val recoverySaved = runCatching { persistRecovery(saved, null, "edit") }.getOrDefault(false)
                if (!recoverySaved) {
                    _uiState.value = _uiState.value.copy(draft = saved, base = saved, draftPurpose = "edit",
                        selectedId = saved.id, draftSaved = false,
                        error = "Заметка записана в Obsidian, но локальную копию обновить не удалось. Скопируй текст перед выходом. Повторное сохранение обновит эту же заметку.")
                    return@withContext
                }
            }
            _uiState.value = _uiState.value.copy(draft = null, base = null, selectedId = saved.id)
            if (saved.status == LifeStatus.DONE && state.base?.status != LifeStatus.DONE) celebrateFinish(saved)
            else if (saved.status == LifeStatus.ACTIVE && state.base?.status != LifeStatus.ACTIVE && saved.kind != LifeKind.AREA) {
                celebrate(Celebration(when (saved.kind) {
                    LifeKind.DECISION -> "Решение принято"
                    LifeKind.GOAL -> "Цель поставлена"
                    else -> "Проект запущен"
                }, saved.title, big = false))
            }
            planAfterSave?.let { (date, minutes) ->
                val title = saved.text(LifeField.NEXT_ACTION).trim()
                if (meaningfulLifeText(title)) runCatching { planStep(saved, title, date, minutes) }
                    .onFailure { if (it is CancellationException) throw it
                        _uiState.value = _uiState.value.copy(error = it.message ?: "Не удалось добавить шаг в план") }
            }
        }
    }

    private fun celebrateFinish(saved: LifeEntry) = celebrate(when (saved.kind) {
        LifeKind.GOAL -> Celebration("Цель достигнута", saved.title, big = true)
        LifeKind.PROJECT -> Celebration("Проект завершён", saved.title, big = true)
        LifeKind.DECISION -> Celebration("Решение проверено", saved.title, big = false)
        LifeKind.REVIEW -> Celebration("Неделя разобрана", saved.title, big = false)
        LifeKind.AREA -> Celebration("Готово", saved.title, big = false)
    })

    fun changeStatus(status: LifeStatus) = action {
        val entry = uiState.value.selected ?: return@action
        val saved = repository.save(entry, entry.copy(status = status))
        if (status == LifeStatus.DONE && entry.status != LifeStatus.DONE) celebrateFinish(saved)
    }

    fun postponeReview(date: LocalDate) = action {
        val entry = uiState.value.selected ?: return@action
        require(date.isAfter(LocalDate.now())) { "Выбери будущую дату напоминания" }
        repository.save(entry, entry.copy(reviewDate = date))
    }

    fun initializeAreas() = action { repository.initializeAreas() }

    /** One review per local Monday–Sunday period; reopening is not a duplicate operation. */
    /** [previousWeek]: a Monday fresh start reviews the week that just ended, with complete facts. */
    fun beginWeeklyReview(previousWeek: Boolean = false) = action {
        val today = LocalDate.now()
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(if (previousWeek) 1 else 0)
        val end = start.plusDays(6)
        val existing = uiState.value.snapshot.entries.filter {
            it.kind == LifeKind.REVIEW && it.periodStart == start && it.periodEnd == end && !it.archived
        }
        check(existing.size <= 1) { "Есть несколько обзоров этой недели. Выбери нужный в списке обзоров." }
        if (existing.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(selectedId = existing.single().id, kindFilter = LifeKind.REVIEW)
        } else {
            check(uiState.value.draft == null) { "Сначала сохрани открытый черновик" }
            val draft = LifeEntry.draft(LifeKind.REVIEW, "Неделя ${start.dayOfMonth}.${start.monthValue} — ${end.dayOfMonth}.${end.monthValue}")
                .copy(periodStart = start, periodEnd = end, reviewDate = null, sourceRoot = uiState.value.snapshot.rootKey.orEmpty())
            _uiState.value = _uiState.value.copy(draft = draft, base = null, error = null, draftPurpose = "edit")
            persistDraft()
        }
        loadWeekFacts(start, end)
    }

    private var weekFactsJob: Job? = null
    fun loadWeekFacts(start: LocalDate, end: LocalDate) {
        val root = dayStore.rootIdentity
        weekFactsJob?.cancel()
        weekFactsJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(weekFacts = WeekFacts(start, end, loading = true))
            runCatching {
                val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) && !it.isAfter(LocalDate.now()) }.toList()
                var failedDays = 0
                val notes = days.mapNotNull { date ->
                    try {
                        check(root != null && dayStore.rootIdentity == root) { "Папка Obsidian изменилась" }
                        dayStore.noteOnChecked(date).also {
                            check(dayStore.rootIdentity == root) { "Папка Obsidian изменилась" }
                        }
                    }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        failedDays++
                        null
                    }
                }
                check(dayStore.rootIdentity == root) { "Папка Obsidian изменилась" }
                WeekFacts(start, end, notes.size, days.size,
                    notes.sumOf { it.tasks.count { task -> task.isScheduled } },
                    notes.sumOf { it.tasks.count { task -> task.isScheduled && task.done } },
                    notes.count { !it.journal.isEmpty }, failedDays = failedDays)
            }.onSuccess { _uiState.value = _uiState.value.copy(weekFacts = it) }
                .onFailure {
                    if (it is CancellationException) throw it
                    _uiState.value = _uiState.value.copy(weekFacts = null, error = "Не удалось прочитать заметки недели")
                }
        }
    }

    /** Export into the existing planner, without a second task database. */
    fun addActionToDay(title: String, date: LocalDate, minutes: Int, source: LifeEntry? = uiState.value.selected, navigate: Boolean = true, startMinute: Int? = null) {
        val root = dayStore.rootIdentity
        action {
            check(root != null && root == dayStore.rootIdentity &&
                (source == null || source.sourceRoot.isBlank() || source.sourceRoot == root)) {
                "Папка Obsidian изменилась. Открой запись заново."
            }
            val cleanTitle = title.replace('\n', ' ').trim()
            require(cleanTitle.count { it.isLetterOrDigit() } >= 3) { "Назови конкретное действие" }
            require(minutes in 5..720) { "Длительность: от 5 минут до 12 часов" }
            val parents = source?.let { repository.taskParents(it) } ?: LifeTaskParents(null, null, null)
            val project = parents.projectId
            val goal = parents.goalId
            val area = parents.areaId
            check(root == dayStore.rootIdentity) { "Папка Obsidian изменилась. Открой запись заново." }
            val ok = dayStore.editNoteOn(date, createIfMissing = true, expectedRoot = root) { note ->
                val duplicate = note.tasks.any { !it.done && it.title == cleanTitle && it.projectId == project && it.goalId == goal && it.areaId == area }
                if (duplicate) note.lines.joinToString("\n") else DailyNoteParser.addTask(note, cleanTitle, startMinuteOfDay = startMinute,
                    durationMinutes = minutes, projectId = project, goalId = goal, areaId = area, noteLink = parents.noteLink)
            }
            check(ok) { "Не удалось добавить действие в заметку дня" }
            dayStore.noteOn(date)?.let { note -> _uiState.value = _uiState.value.copy(activity = _uiState.value.activity?.withDay(note)) }
            _uiState.value = if (navigate) _uiState.value.copy(openPlanDate = date)
                else _uiState.value.copy(message = "В плане на ${planDayLabel(date)}" +
                    (startMinute?.let { " · %02d:%02d".format(it / 60, it % 60) } ?: ""))
        }
    }

    private fun persistDraft(commit: Boolean = false): Boolean {
        val state = uiState.value
        val draft = state.draft ?: return true
        val encoded = runCatching { LifeDraftCodec.encode(draft) }.getOrElse {
            _uiState.value = _uiState.value.copy(draftSaved = false,
                error = "Слишком большой черновик. Текст остаётся на экране; скопируй его перед выходом.")
            return false
        }
        val editor = prefs.edit().putString("draft", encoded)
            .putString("purpose", state.draftPurpose)
            .putString("path", state.base?.path.orEmpty()).putString("sourceRoot", draft.sourceRoot)
        if (state.base != null) editor.putString("base", state.base.raw) else editor.remove("base")
        val saved = if (commit) editor.commit() else { editor.apply(); true }
        _uiState.value = _uiState.value.copy(draftSaved = saved)
        return saved
    }

    private fun action(block: suspend () -> Unit) {
        if (uiState.value.busy) return
        _uiState.value = _uiState.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            runCatching { block() }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.value = _uiState.value.copy(error = error.message ?: "Не удалось сохранить изменение")
            }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    private companion object { val DRAFT_PURPOSES = setOf("edit", "activate", "complete", "review", "life-review") }

    class Factory(private val repository: LifeRepository, private val dayStore: VaultDayStore, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LifeViewModel(repository, dayStore, context.applicationContext) as T
    }
}

internal fun planDayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "сегодня"
    today.plusDays(1) -> "завтра"
    else -> date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.forLanguageTag("ru")))
}

package dev.stanislav.focusdeck.ui.ritual

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.db.Meal
import dev.stanislav.focusdeck.data.repo.NutritionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Job
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultTask
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import dev.stanislav.focusdeck.ui.restrictions.GuardWakeClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RitualDraftConflict(val key: String, val field: String, val day: LocalDate, val localText: String, val remoteText: String)

data class RitualUiState(
    val loading: Boolean = true,
    val window: RitualWindow = ritualWindow(LocalDateTime.now()),
    val progress: RitualProgress? = null,
    val journal: String = "",
    val meals: List<Meal> = emptyList(),
    val planTasks: List<VaultTask> = emptyList(),
    val reviewedTasks: List<VaultTask> = emptyList(),
    val carriedTitles: Set<String> = emptySet(),
    val doneToday: Int = 0,
    val plannedToday: Int = 0,
    val unplannedDoneToday: Int = 0,
    val emergencyUsesToday: Int = 0,
    val emergencyAvailable: Boolean = true,
    val ifThen: String = "",
    val mandatory: Boolean = false,
    val preview: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val vaultConnected: Boolean = false,
    val draftSaving: Boolean = false,
    val draftConflict: RitualDraftConflict? = null,
    /** Everything ticked on the closing day, planned or not: the wins shown first in the evening. */
    val doneTasks: List<VaultTask> = emptyList(),
    /** Morning only: yesterday's conclusions (or evening entry) to start from. */
    val yesterday: String = "",
    /** Unfinished tasks consciously let go tonight. Local; the note keeps its history. */
    val letGo: Set<String> = emptySet(),
)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RitualViewModel(
    private val dayStore: VaultDayStore,
    private val nutrition: NutritionRepository,
    context: Context,
) : ViewModel() {
    private val app = context.applicationContext as dev.stanislav.focusdeck.FocusDeckApp
    private val clockNow: () -> LocalDateTime = { ritualNow(context.applicationContext) }
    private val guardWake = GuardWakeClient(context)
    private val prefs = context.getSharedPreferences("ritual", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()
    private val actionLock = Mutex()
    private val draftChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val editorBuffers = mutableMapOf<String, String>()
    private var draftGeneration = 0L
    private var checkpointJob: Job? = null
    private var refreshJob: Job? = null
    private var refreshRequested = false
    private var draftError: String? = null
    private var manualOpen = false
    private var observedActualWindow = ritualWindow(clockNow())
    private val _uiState = MutableStateFlow(RitualUiState())
    val uiState: StateFlow<RitualUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            draftChanges.debounce(750).collect { flushDraftQueue() }
        }
        // Resume durable outbox entries after restart or reconnecting their original vault.
        viewModelScope.launch {
            dayStore.rootChanges.collect {
                draftChanges.tryEmit(Unit)
                refresh()
            }
        }
        viewModelScope.launch {
            dayStore.changes.collect { day ->
                val window = currentWindow()
                if (day == window.journalDate || day == window.planDate) refresh()
            }
        }
        viewModelScope.launch {
            _uiState.map { it.window.journalDate }.distinctUntilChanged()
                .flatMapLatest { nutrition.observeMealsForDay(it.toEpochDay()) }
                .map(::foodFingerprint).distinctUntilChanged().collect { refresh() }
        }
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val bypassUntil = prefs.getLong("emergency_until", 0L)
                val actualWindow = ritualWindow(clockNow())
                if (actualWindow != observedActualWindow ||
                    currentWindow() != _uiState.value.window ||
                    (bypassUntil > 0 && System.currentTimeMillis() >= bypassUntil)
                ) {
                    refresh()
                }
            }
        }
    }

    private fun currentWindow(): RitualWindow {
        return shownRitualWindow(clockNow(), manualOpen)
    }

    private fun draftKey(window: RitualWindow, field: String): String =
        RitualDraft.key(window, field, dayStore.rootIdentity)

    private val _finished = kotlinx.coroutines.flow.MutableStateFlow<RitualPhase?>(null)
    /** One-shot: the phase whose ritual was just completed by the person, for a moment of celebration. */
    val finished: kotlinx.coroutines.flow.StateFlow<RitualPhase?> = _finished
    fun consumeFinished() { _finished.value = null }

    private fun letGoKey(window: RitualWindow): String = "letgo_${emergencyWindowKey(window)}"

    /** Closing a loop by decision is as valid as carrying it: remember the choice, touch no file. */
    fun letGo(task: VaultTask) {
        val state = _uiState.value
        if (state.busy) return
        val next = state.letGo + task.title
        prefs.edit { putStringSet(letGoKey(state.window), next) }
        _uiState.value = state.copy(letGo = next)
    }

    private fun emergencyWindowKey(window: RitualWindow): String =
        "${window.phase}_${window.journalDate}"

    private fun hasEmergencyBypass(window: RitualWindow): Boolean =
        prefs.getString("exited_window", null) == emergencyWindowKey(window) ||
            (prefs.getLong("emergency_until", 0L) > System.currentTimeMillis() &&
                prefs.getString("emergency_window", null) == emergencyWindowKey(window))

    private fun updateDraft(field: String, value: String) {
        val window = _uiState.value.window
        val key = draftKey(window, field)
        val base = if (field == "journal") _uiState.value.journal else _uiState.value.ifThen
        val editor = prefs.edit().putString(key, value)
        if (!prefs.contains(key)) editor.putString("base_$key", RitualDraft.normalize(field, base))
            .putString("root_$key", dayStore.rootIdentity)
        editor.apply()
        editorBuffers[key] = value
        draftGeneration++
        _uiState.value = if (field == "journal") _uiState.value.copy(journal = value, draftSaving = true)
            else _uiState.value.copy(ifThen = value, draftSaving = true)
        checkpointJob?.cancel()
        checkpointJob = viewModelScope.launch {
            delay(120)
            val generation = draftGeneration
            // An empty synchronous commit waits for pending apply writes; it never replays old text.
            val durable = withContext(Dispatchers.IO) { prefs.edit().commit() }
            if (generation == draftGeneration) _uiState.value = _uiState.value.copy(draftSaving = !durable,
                error = if (durable) _uiState.value.error else "Не удалось сохранить черновик на устройстве. Не закрывай редактор.")
        }
        draftChanges.tryEmit(Unit)
    }

    fun updateJournalDraft(value: String) = updateDraft("journal", value)
    fun updateIfThenDraft(value: String) = updateDraft("ifthen", value)

    private fun pendingDrafts(): List<RitualDraft> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { RitualDraft.parse(key, it, prefs.getString("base_$key", null), prefs.getString("root_$key", null)) }
    }.sortedBy { it.key }

    private fun hasPending(window: RitualWindow) = listOf("journal", "ifthen").any { prefs.contains(draftKey(window, it)) }

    private suspend fun savePending(draft: RitualDraft, explicitLegacySave: Boolean = false): Boolean {
        if (prefs.getString(draft.key, null) != draft.text) return false
        check(draft.sourceRoot != null && draft.sourceRoot == dayStore.rootIdentity) {
            if (draft.sourceRoot == null) "Черновик пока не привязан к папке. Сравни записи и выбери, что сохранить."
            else "Есть черновик из другой папки Obsidian. Верни исходную папку, чтобы сохранить его."
        }
        val saved = dayStore.editNoteOn(draft.day, createIfMissing = true, expectedRoot = draft.sourceRoot) { note ->
            val current = if (draft.field == "ifthen") RitualNotes.value(note, RitualNotes.IF_THEN).orEmpty()
                else if (draft.window.phase == RitualPhase.EVENING) note.journal.evening else note.journal.morning
            check(draft.canApplyTo(current, explicitLegacySave)) {
                "Запись изменена в Obsidian. Черновик сохранён отдельно; сравни тексты перед заменой."
            }
            val result = if (draft.field == "ifthen") RitualNotes.put(note, RitualNotes.IF_THEN, draft.text)
            else DailyNoteParser.writeJournal(note, if (draft.window.phase == RitualPhase.EVENING)
                note.journal.copy(evening = draft.normalized) else note.journal.copy(morning = draft.normalized))
            val parsed = DailyNoteParser.parse(note.date, note.path, result)
            check(RitualDraft.normalize(draft.field, currentDraftField(draft, parsed)) == draft.normalized) {
                "Разметка записи мешает сохранению. Черновик оставлен на устройстве; исправь структуру заметки в Obsidian."
            }
            result
        }
        if (saved) {
            // Never remove text typed while the file write was suspended.
            if (prefs.getString(draft.key, null) == draft.text) {
                prefs.edit { remove(draft.key); remove("base_${draft.key}"); remove("root_${draft.key}") }
            } else if (prefs.contains(draft.key)) {
                // A newer local edit now starts from the version we just wrote ourselves.
                prefs.edit { putString("base_${draft.key}", draft.normalized) }
            }
            withContext(Dispatchers.IO) { prefs.edit().commit() }
        }
        return saved
    }

    private suspend fun flushDraftQueue() = actionLock.withLock {
        val pending = pendingDrafts()
        if (pending.isEmpty()) {
            if (draftError != null) { draftError = null; refreshNow() }
            return@withLock
        }
        val failures = mutableListOf<String>()
        for (draft in pending) {
            try {
                if (!savePending(draft)) failures += "Черновик за ${draft.day} ожидает записи"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                failures += error.message ?: "Не удалось сохранить черновик за ${draft.day}"
            }
        }
        draftError = failures.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("\n")
        refreshNow()
    }

    private fun currentDraftField(draft: RitualDraft, note: DailyNote?): String =
        if (draft.field == "ifthen") RitualNotes.value(note, RitualNotes.IF_THEN).orEmpty()
        else if (draft.window.phase == RitualPhase.EVENING) note?.journal?.evening.orEmpty()
        else note?.journal?.morning.orEmpty()

    fun openDraftConflict() {
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(busy = true)
        viewModelScope.launch {
            actionLock.withLock {
                try {
                    var conflict: RitualDraftConflict? = null
                    for (draft in pendingDrafts()) {
                        if (draft.sourceRoot != null && draft.sourceRoot != dayStore.rootIdentity) continue
                        val remote = currentDraftField(draft, dayStore.noteOnChecked(draft.day))
                        if (draft.sourceRoot == null || !draft.canApplyTo(remote)) {
                            conflict = RitualDraftConflict(draft.key, draft.field, draft.day, draft.text, remote)
                            break
                        }
                    }
                    _uiState.value = _uiState.value.copy(draftConflict = conflict,
                        error = if (conflict == null) "Различий между версиями нет. Повтори сохранение или проверь доступ к папке." else _uiState.value.error)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Не удалось прочитать запись")
                } finally { _uiState.value = _uiState.value.copy(busy = false) }
            }
        }
    }

    fun dismissDraftConflict() { if (!_uiState.value.busy) _uiState.value = _uiState.value.copy(draftConflict = null) }

    fun resolveDraftConflict(keepLocal: Boolean) {
        val shown = _uiState.value.draftConflict ?: return
        if (_uiState.value.busy) return
        _uiState.value = _uiState.value.copy(busy = true)
        viewModelScope.launch {
            actionLock.withLock {
                try {
                    val draft = pendingDrafts().firstOrNull { it.key == shown.key }
                    check(draft != null && draft.text == shown.localText) { "Черновик изменился. Сравни записи заново." }
                    val remote = currentDraftField(draft, dayStore.noteOnChecked(draft.day))
                    check(remote == shown.remoteText) { "Запись в Obsidian снова изменилась. Сравни записи заново." }
                    if (keepLocal) {
                        check(savePending(draft.copy(base = shown.remoteText, sourceRoot = dayStore.rootIdentity))) { "Не удалось записать выбранную версию" }
                    } else {
                        // Preserve the replaced local text as one private recovery copy.
                        check(withContext(Dispatchers.IO) {
                            prefs.edit().putString("last_replaced_draft_key", draft.key)
                                .putString("last_replaced_draft_text", draft.text)
                                .remove(draft.key).remove("base_${draft.key}").remove("root_${draft.key}").commit()
                        }) { "Не удалось сохранить выбор версии" }
                        editorBuffers.remove(draft.key)
                    }
                    draftError = null
                    _uiState.value = _uiState.value.copy(draftConflict = null)
                    refreshNow()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(draftConflict = null, error = error.message ?: "Не удалось применить выбор")
                } finally { _uiState.value = _uiState.value.copy(busy = false) }
            }
        }
    }

    fun openEvening() {
        manualOpen = true
        refresh()
    }

    fun closePreview() {
        if (!manualOpen) return
        manualOpen = false
        refresh()
    }

    fun refresh() {
        val actual = ritualWindow(clockNow())
        if (actual != observedActualWindow) {
            if (actual.phase == RitualPhase.MORNING) manualOpen = false
            observedActualWindow = actual
        }
        refreshRequested = true
        if (refreshJob?.isActive == true) return
        _uiState.value = _uiState.value.copy(loading = _uiState.value.progress == null || currentWindow() != _uiState.value.window)
        refreshJob = viewModelScope.launch {
          do {
            refreshRequested = false
            runCatching { refreshNow() }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                val window = currentWindow()
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    busy = false,
                    window = window,
                    progress = null,
                    planTasks = emptyList(),
                    reviewedTasks = emptyList(),
                    vaultConnected = dayStore.isConnected,
                    mandatory = false,
                    preview = manualOpen,
                    error = "Не удалось прочитать заметки Obsidian. Проверь доступ к папке.",
                )
            }
          } while (refreshRequested)
        }
    }

    private suspend fun refreshNow() = refreshLock.withLock {
        while (!refreshOnce()) { /* Re-read if the ritual window changed during I/O. */ }
    }

    private suspend fun refreshOnce(): Boolean {
        val window = currentWindow()
        val journalResult = runCatching { dayStore.noteOnChecked(window.journalDate) }
        val planResult = if (window.planDate == window.journalDate) journalResult
            else runCatching { dayStore.noteOnChecked(window.planDate) }
        journalResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        planResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val journalNote = journalResult.getOrNull()
        val planNote = planResult.getOrNull()
        val meals = nutrition.observeMealsForDay(window.journalDate.toEpochDay()).first()
        val yesterday = if (window.phase == RitualPhase.MORNING) {
            runCatching { dayStore.noteOnChecked(window.journalDate.minusDays(1)) }
                .onFailure { if (it is CancellationException) throw it }.getOrNull()
                ?.journal?.let { it.conclusions.trim().ifEmpty { it.evening.trim() } }.orEmpty()
        } else ""
        if (window != currentWindow()) return false
        val storedProgress = ritualProgress(window, journalNote, planNote, meals)
        val progress = storedProgress.copy(completed = storedProgress.completed && !hasPending(window))
        val review = eveningReview(journalNote)
        val journalFromNote = if (window.phase == RitualPhase.EVENING) {
            journalNote?.journal?.evening.orEmpty()
        } else journalNote?.journal?.morning.orEmpty()
        if (prefs.contains("emergency_until") &&
            (prefs.getLong("emergency_until", 0L) <= System.currentTimeMillis() ||
                prefs.getString("emergency_window", null) != emergencyWindowKey(window))) {
            prefs.edit {
                remove("emergency_until")
                remove("emergency_window")
            }
        }
        _uiState.value = RitualUiState(
            loading = false,
            window = window,
            progress = progress,
            meals = meals,
            journal = prefs.getString(draftKey(window, "journal"), null)
                ?: editorBuffers[draftKey(window, "journal")]?.takeIf { it.trim() == journalFromNote.trim() } ?: journalFromNote,
            planTasks = planNote?.tasks.orEmpty().filter(::validTimedTask),
            reviewedTasks = if (window.phase == RitualPhase.EVENING) review.unfinished else emptyList(),
            carriedTitles = planNote?.tasks.orEmpty().map { it.title }.toSet(),
            doneToday = review.completed,
            plannedToday = review.planned,
            unplannedDoneToday = review.unplannedCompleted,
            emergencyUsesToday = prefs.getInt("emergency_${window.journalDate}", 0),
            emergencyAvailable = !prefs.getBoolean("emergency_used_${emergencyWindowKey(window)}", false),
            ifThen = prefs.getString(draftKey(window, "ifthen"), null)
                ?: editorBuffers[draftKey(window, "ifthen")]?.takeIf { RitualDraft.normalize("ifthen", it) == RitualNotes.value(planNote, RitualNotes.IF_THEN).orEmpty() }
                ?: RitualNotes.value(planNote, RitualNotes.IF_THEN).orEmpty(),
            mandatory = false,
            preview = manualOpen,
            busy = _uiState.value.busy,
            error = when {
                !dayStore.isConnected -> "Папка Obsidian не подключена. Рабочий блок можно начать и без неё."
                journalResult.isFailure || planResult.isFailure ->
                    "Не удалось прочитать заметки Obsidian. Проверь доступ к папке."
                else -> draftError
            },
            vaultConnected = dayStore.isConnected,
            draftSaving = _uiState.value.draftSaving,
            draftConflict = _uiState.value.draftConflict,
            doneTasks = if (window.phase == RitualPhase.EVENING) journalNote?.tasks.orEmpty().filter { it.done } else emptyList(),
            yesterday = yesterday,
            letGo = prefs.getStringSet(letGoKey(window), emptySet()).orEmpty(),
        )
        return true
    }

    private fun change(block: suspend (RitualWindow, String) -> Boolean) {
        if (_uiState.value.busy) return
        val requestedWindow = _uiState.value.window
        val requestedRoot = dayStore.rootIdentity
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            actionLock.withLock {
                try {
                    check(requestedWindow == currentWindow() && ritualWindow(clockNow()) == observedActualWindow) {
                        "Начался новый ритуал. Повтори действие для текущего дня."
                    }
                    check(requestedRoot != null && dayStore.rootIdentity == requestedRoot) {
                        "Папка Obsidian изменилась. Повтори действие в текущей папке."
                    }
                    val success = block(requestedWindow, requestedRoot)
                    refreshNow()
                    if (!success) _uiState.value = _uiState.value.copy(
                        error = "Не удалось завершить действие. Проверь дневник, план и доступ к Obsidian.")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Не удалось сохранить изменения")
                } finally {
                    _uiState.value = _uiState.value.copy(busy = false)
                    if (requestedWindow != currentWindow()) refresh()
                }
            }
        }
    }

    fun choosePriority(title: String) = change { window, root ->
        dayStore.editNoteOn(window.planDate, createIfMissing = true, expectedRoot = root) { note ->
            val withPriority = RitualNotes.put(note, RitualNotes.PRIORITY, title)
            RitualNotes.put(
                DailyNoteParser.parse(window.planDate, note.path, withPriority),
                RitualNotes.REST_DAY,
                "нет",
            )
        }
    }

    fun confirmFood() = change { window, root ->
        if (window.phase != RitualPhase.EVENING) return@change false
        val meals = nutrition.observeMealsForDay(window.journalDate.toEpochDay()).first()
        if (meals.isEmpty() || meals.any { !meaningfulTitle(it.name) || it.calories < 0 }) return@change false
        dayStore.editNoteOn(window.journalDate, createIfMissing = true, expectedRoot = root) { note ->
            val listed = RitualNotes.put(note, RitualNotes.FOOD_LIST,
                meals.joinToString("; ") { "${it.name} — ${it.calories} ккал" })
            RitualNotes.put(DailyNoteParser.parse(note.date, note.path, listed),
                RitualNotes.FOOD_CHECKED, foodFingerprint(meals))
        }
    }

    /** Keep today's history intact; add a fresh, unscheduled copy to tomorrow. */
    fun carryToPlan(task: VaultTask) = change { window, root ->
        dayStore.copyUnfinishedTaskToDay(window.journalDate, window.planDate, task, expectedRoot = root)
    }

    fun finish(journalText: String, ifThenText: String) = change { window, root ->
        if (!journalReadiness(journalText).isReady) return@change false
        check(withContext(Dispatchers.IO) { prefs.edit().commit() }) { "Черновик ещё не сохранён на устройстве" }
        for (draft in pendingDrafts().filter { it.window == window && it.sourceRoot == root }) {
            if (!savePending(draft, explicitLegacySave = true)) return@change false
        }
        if (hasPending(window)) return@change false
        val journalNote: DailyNote? = dayStore.noteOn(window.journalDate)
        val planNote = if (window.planDate == window.journalDate) journalNote
            else dayStore.noteOn(window.planDate)
        val meals = nutrition.observeMealsForDay(window.journalDate.toEpochDay()).first()
        check(dayStore.rootIdentity == root) { "Папка Obsidian изменилась. Подтверждение отменено." }
        val progress = ritualProgress(window, journalNote, planNote, meals)
        if (!progress.canComplete) return@change false
        val marker = if (window.phase == RitualPhase.MORNING) RitualNotes.MORNING_DONE
            else RitualNotes.EVENING_DONE
        val written = dayStore.editNoteOn(window.journalDate, expectedRoot = root) { note ->
            RitualNotes.put(note, marker, "да")
        }
        if (written) {
            if (hasPending(window)) return@change false
            manualOpen = false
            _finished.value = window.phase
        }
        written
    }

    /** Leave this window without claiming the ritual was completed or deleting its drafts. */
    fun exitCurrentRitual() {
        val state = _uiState.value
        if (state.busy || !state.mandatory || state.window != ritualWindow(clockNow())) return
        _uiState.value = state.copy(busy = true, error = null)
        viewModelScope.launch {
            actionLock.withLock {
                try {
                    val stored = withContext(Dispatchers.IO) {
                        prefs.edit().putString("exited_window", emergencyWindowKey(state.window))
                            .putInt("ritual_exits", prefs.getInt("ritual_exits", 0) + 1).commit()
                    }
                    check(stored) { "Не удалось сохранить решение. Попробуй ещё раз." }
                    app.recordAudit("ritual_optout")
                    manualOpen = false
                    refreshNow()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Не удалось выйти из ритуала")
                } finally {
                    _uiState.value = _uiState.value.copy(busy = false)
                }
            }
        }
    }

    fun emergencyAccess() {
        if (_uiState.value.busy || !_uiState.value.mandatory ||
            prefs.getBoolean("emergency_used_${emergencyWindowKey(_uiState.value.window)}", false) ||
            hasEmergencyBypass(_uiState.value.window)
        ) return
        if (_uiState.value.window != ritualWindow(clockNow())) {
            refresh()
            return
        }
        val window = _uiState.value.window
        _uiState.value = _uiState.value.copy(busy = true, error = null)
        viewModelScope.launch {
            actionLock.withLock {
                try {
                    val deadline = System.currentTimeMillis() + 15 * 60_000L
                    if (guardWake.managed()) {
                        val receipt = guardWake.schedule(deadline, GuardWakeClient.Reason.EMERGENCY)
                        check(receipt.canRelyOnManagedReturn) {
                            "Автоматический возврат не подтверждён. Ограничение осталось включено; для полного выхода доступно восстановление Guard с компьютера."
                        }
                    }
                    check(window == ritualWindow(clockNow())) { "Начался новый ритуал. Открой срочный доступ заново." }
                    val key = "emergency_${window.journalDate}"
                    val stored = withContext(Dispatchers.IO) {
                        prefs.edit().putBoolean("emergency_used_${emergencyWindowKey(window)}", true)
                            .putLong("emergency_until", deadline)
                            .putString("emergency_window", emergencyWindowKey(window))
                            .putInt(key, prefs.getInt(key, 0) + 1).commit()
                    }
                    check(stored) { "Не удалось сохранить время возврата. Ограничение осталось включено." }
                    manualOpen = false
                    refreshNow()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    _uiState.value = _uiState.value.copy(error = error.message ?: "Не удалось открыть срочный доступ")
                } finally {
                    _uiState.value = _uiState.value.copy(busy = false)
                }
            }
        }
    }

    class Factory(
        private val dayStore: VaultDayStore,
        private val nutrition: NutritionRepository,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            RitualViewModel(dayStore, nutrition, context.applicationContext) as T
    }
}

package dev.stanislav.focusdeck.ui.nutrition

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.db.DailyCalories
import dev.stanislav.focusdeck.data.db.Meal
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.data.db.WeightEntry
import dev.stanislav.focusdeck.data.repo.NutritionRepository
import dev.stanislav.focusdeck.ui.ritual.meaningfulTitle
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** День в полоске истории. */
data class DaySummary(val date: LocalDate, val calories: Int, val target: Int) {
    val withinTarget: Boolean get() = calories in 1..target
    val label: String get() = "%02d.%02d".format(date.dayOfMonth, date.monthValue)
}

/** A separate draft for each day and each edited meal; null means a new meal. */
data class MealDraft(
    val day: Long,
    val mealId: Long? = null,
    val name: String = "",
    val calories: String = "",
    val type: MealType = MealType.BREAKFAST,
    val revision: Long = 0,
    val creationId: Long? = null,
    val creationEpochMillis: Long? = null,
    val legacyUncertain: Boolean = false,
) {
    val key: String get() = "draft_${day}_${mealId ?: "new"}"
    val valid: Boolean get() = meaningfulTitle(name) && calories.toIntOrNull()?.let { it >= 0 } == true
    val requiresRecovery: Boolean get() = creationId != null || legacyUncertain
    fun editable(busy: Boolean, needsCleanup: Boolean): Boolean = !busy && !needsCleanup && !requiresRecovery
    fun reservedMeal(): Meal {
        check(mealId == null && creationId != null && creationId < -1L && !legacyUncertain)
        return Meal(id = creationId, dayEpochDay = day, type = type, name = name.trim(),
            calories = calories.toInt(), epochMillis = checkNotNull(creationEpochMillis))
    }
}

internal fun restoredMealId(stored: Long): Long? = stored.takeIf { it != -1L }
internal fun legacyMealRecovery(mealId: Long?, inFlight: Boolean, creationId: Long): Boolean =
    mealId == null && inFlight && creationId >= -1L

data class WeightDraft(val day: Long, val text: String = "", val revision: Long = 0,
    val busy: Boolean = false, val error: String? = null,
    val creationId: Long? = null, val creationEpochMillis: Long? = null,
    val draftSaved: Boolean = false) {
    val kg: Float? get() = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    fun beginSave(): WeightDraft? = if (busy || kg == null) null else copy(busy = true, error = null)
    fun complete(saved: WeightDraft, success: Boolean): WeightDraft {
        if (day != saved.day || revision != saved.revision) return this
        return if (success) copy(text = "", revision = revision + 1, busy = false, error = null,
            creationId = null, creationEpochMillis = null, draftSaved = false)
        else copy(busy = false, error = "Не удалось подтвердить вес. Повтори с теми же данными; второй записи не будет.")
    }
    fun reservedEntry(): WeightEntry {
        val id = checkNotNull(creationId)
        check(id < -1L)
        return WeightEntry(id, day, checkNotNull(kg), checkNotNull(creationEpochMillis))
    }
}

private data class WeightDraftWrite(val draft: WeightDraft, val remove: Boolean = false,
    val result: CompletableDeferred<Boolean> = CompletableDeferred())

data class NutritionUiState(
    val date: LocalDate = LocalDate.now(),
    val meals: List<Meal> = emptyList(),
    val recentWeights: List<WeightEntry> = emptyList(),
    val calorieTarget: Int = 2200,
    val history: List<DaySummary> = emptyList(),
    val draft: MealDraft = MealDraft(date.toEpochDay()),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val draftSaving: Boolean = false,
    val draftPersisted: Boolean = false,
    val needsCleanup: Boolean = false,
    val error: String? = null,
) {
    val editingMealId: Long? get() = draft.mealId
    val editingMeal: Meal? get() = meals.firstOrNull { it.id == draft.mealId }
    val totalCalories: Int get() = meals.sumOf { it.calories }
    val calorieProgress: Float get() = (totalCalories.toFloat() / calorieTarget.coerceAtLeast(1)).coerceIn(0f, 1f)
    val remainingCalories: Int get() = calorieTarget - totalCalories
    val weightDeltaKg: Float? get() = if (recentWeights.size > 1) recentWeights.first().kg - recentWeights.last().kg else null
}

private const val HISTORY_DAYS = 7
private data class DraftWrite(
    val draft: MealDraft,
    val select: Boolean = false,
    val remove: Boolean = false,
    val inFlight: Boolean? = null,
    val archive: Boolean = false,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)
private data class NutritionData(val day: LocalDate, val meals: List<Meal>, val weights: List<WeightEntry>, val target: Int, val totals: List<DailyCalories>)

@OptIn(ExperimentalCoroutinesApi::class)
class NutritionViewModel(private val repository: NutritionRepository, context: Context) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences("nutrition_drafts", Context.MODE_PRIVATE)
    private val initialDate = runCatching { LocalDate.ofEpochDay(prefs.getLong("selected_day", LocalDate.now().toEpochDay())) }
        .getOrDefault(LocalDate.now())
    private val selectedDate = MutableStateFlow(initialDate)
    private val reload = MutableStateFlow(0L)
    private val cache = mutableMapOf<String, MealDraft>()
    private val durableVersions = mutableMapOf<String, Long>()
    private val activeEdits = mutableMapOf<Long, Long?>()
    private var revision = 0L
    private val writes = Channel<DraftWrite>(Channel.UNLIMITED)
    // A failed preferences cleanup must not cause a second Room insert on Retry.
    private val appliedOperations = mutableSetOf<Pair<String, Long>>()
    private val retiredDrafts = mutableSetOf<Pair<String, Long>>()
    private val weightDrafts = mutableMapOf<Long, WeightDraft>()
    private val weightWrites = Channel<WeightDraftWrite>(Channel.UNLIMITED)
    private val _weightDraft = MutableStateFlow(weightDraftFor(initialDate.toEpochDay()))
    val weightDraft: StateFlow<WeightDraft> = _weightDraft.asStateFlow()
    private val initialDraft = draftFor(initialDate.toEpochDay(), activeId(initialDate.toEpochDay()))
    private val _uiState = MutableStateFlow(NutritionUiState(date = initialDate, draft = initialDraft, draftPersisted = durableVersions[initialDraft.key] == initialDraft.revision, error = interruptedMessage(initialDraft)))
    val uiState: StateFlow<NutritionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            for (write in weightWrites) {
                if (!write.remove && weightDrafts[write.draft.day]?.revision != write.draft.revision) {
                    write.result.complete(false)
                    continue
                }
                val committed = withContext(Dispatchers.IO) {
                    try {
                        val prefix = "weight_${write.draft.day}"
                        val editor = prefs.edit()
                        if (write.remove) listOf("text", "creation_id", "creation_time").forEach { editor.remove("${prefix}_$it") }
                        else {
                            editor.putString("${prefix}_text", write.draft.text)
                            write.draft.creationId?.let { editor.putLong("${prefix}_creation_id", it) }
                            write.draft.creationEpochMillis?.let { editor.putLong("${prefix}_creation_time", it) }
                        }
                        val saved = editor.commit()
                        if (!saved && write.remove) {
                            // Failed commit has already changed SharedPreferences' memory map.
                            // Restore the operation there too, so a recreated VM cannot forget it.
                            val restore = prefs.edit().putString("${prefix}_text", write.draft.text)
                            write.draft.creationId?.let { restore.putLong("${prefix}_creation_id", it) }
                            write.draft.creationEpochMillis?.let { restore.putLong("${prefix}_creation_time", it) }
                            restore.commit()
                        }
                        saved
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        false
                    }
                }
                if (!write.remove) weightDrafts[write.draft.day]?.let { current ->
                    if (current.revision == write.draft.revision) putWeight(current.copy(draftSaved = committed,
                        error = if (committed) current.error else "Не удалось сохранить черновик веса на устройстве. Ввод оставлен."))
                }
                write.result.complete(committed)
            }
        }
        viewModelScope.launch {
            for (write in writes) {
                if (!write.remove && ((write.draft.key to write.draft.revision) in retiredDrafts ||
                        cache[write.draft.key]?.revision != write.draft.revision)) {
                    write.result.complete(false)
                    val current = _uiState.value
                    if (current.draft.key == write.draft.key && current.draft.revision == write.draft.revision) {
                        _uiState.value = current.copy(draftSaving = false)
                    }
                    continue
                }
                val saved = withContext(Dispatchers.IO) { runCatching { persist(write) }.getOrDefault(false) }
                if (saved && !write.remove) durableVersions[write.draft.key] = write.draft.revision
                write.result.complete(saved)
                val current = _uiState.value
                if (!write.remove && current.draft.key == write.draft.key && current.draft.revision == write.draft.revision) {
                    _uiState.value = current.copy(draftSaving = false, draftPersisted = saved,
                        error = if (saved) current.error else "Не удалось сохранить черновик на устройстве. Повтори сохранение.")
                }
            }
        }
        viewModelScope.launch {
            combine(selectedDate, reload) { date, _ -> date }.flatMapLatest { date ->
                combine(repository.observeMealsForDay(date.toEpochDay()), repository.observeRecentWeights(),
                    repository.calorieTarget, repository.observeDailyTotals(date.toEpochDay() - HISTORY_DAYS + 1, date.toEpochDay()),
                ) { meals, weights, target, totals -> NutritionData(date, meals, weights, target, totals) }
                    .catch { failure ->
                        if (failure is CancellationException) throw failure
                        if (_uiState.value.date == date) _uiState.value = _uiState.value.copy(loading = false,
                            error = "Не удалось прочитать питание. Ввод оставлен; попробуй открыть экран снова.")
                    }
            }.collect { data ->
                if (_uiState.value.date == data.day) _uiState.value = _uiState.value.copy(
                    loading = false, meals = data.meals, recentWeights = data.weights, calorieTarget = data.target,
                    history = buildHistory(data.totals, data.target, data.day.toEpochDay()),
                )
            }
        }
    }

    private fun activeId(day: Long): Long? = if (activeEdits.containsKey(day)) activeEdits[day]
        else restoredMealId(prefs.getLong("active_$day", -1L)).also { activeEdits[day] = it }

    private fun draftFor(day: Long, mealId: Long?, meal: Meal? = null): MealDraft {
        val base = MealDraft(day, mealId)
        return cache.getOrPut(base.key) {
            if (prefs.contains("${base.key}_name")) base.copy(
                name = prefs.getString("${base.key}_name", "").orEmpty(),
                calories = prefs.getString("${base.key}_calories", "").orEmpty(),
                type = runCatching { MealType.valueOf(prefs.getString("${base.key}_type", null).orEmpty()) }.getOrDefault(MealType.BREAKFAST),
                revision = ++revision,
                creationId = prefs.getLong("${base.key}_creation_id", 0L).takeIf { it < -1L },
                creationEpochMillis = if (prefs.contains("${base.key}_creation_time")) prefs.getLong("${base.key}_creation_time", 0L) else null,
                legacyUncertain = legacyMealRecovery(mealId, prefs.getBoolean("${base.key}_inflight", false),
                    prefs.getLong("${base.key}_creation_id", 0L)),
            ).also { durableVersions[it.key] = it.revision } else if (meal != null) base.copy(name = meal.name, calories = meal.calories.toString(), type = meal.type, revision = ++revision)
            else base.copy(revision = ++revision)
        }
    }

    private fun interruptedMessage(draft: MealDraft): String? = if (!draft.requiresRecovery && prefs.getBoolean("${draft.key}_inflight", false))
        "Предыдущее сохранение было прервано. Проверь список перед повтором; черновик оставлен." else null

    private fun persist(write: DraftWrite): Boolean {
        val draft = write.draft
        val editor = prefs.edit()
        if (write.select) editor.putLong("selected_day", draft.day).putLong("active_${draft.day}", draft.mealId ?: -1L)
        if (write.remove) {
            if (write.archive) {
                val backup = "recovered_${draft.key}_${System.currentTimeMillis()}"
                editor.putString("${backup}_name", draft.name).putString("${backup}_calories", draft.calories)
                    .putString("${backup}_type", draft.type.name)
            }
            listOf("name", "calories", "type", "inflight", "creation_id", "creation_time").forEach { editor.remove("${draft.key}_$it") }
            // Don't overwrite a different active editor selected while Room was busy.
            if (prefs.getLong("active_${draft.day}", -1L) == (draft.mealId ?: -1L)) editor.putLong("active_${draft.day}", -1L)
        } else {
            editor.putString("${draft.key}_name", draft.name).putString("${draft.key}_calories", draft.calories)
                .putString("${draft.key}_type", draft.type.name)
            draft.creationId?.let { editor.putLong("${draft.key}_creation_id", it) }
            draft.creationEpochMillis?.let { editor.putLong("${draft.key}_creation_time", it) }
            // Selection preserves the marker at write time, even if another operation was queued first.
            write.inFlight?.let { editor.putBoolean("${draft.key}_inflight", it) }
        }
        return editor.commit()
    }

    private fun enqueue(write: DraftWrite): CompletableDeferred<Boolean> {
        if (!writes.trySend(write).isSuccess) write.result.complete(false)
        return write.result
    }

    fun selectDate(date: LocalDate) {
        if (_uiState.value.date == date) {
            // Retry a failed read when reopening, while keeping the current editor intact.
            reload.value += 1
            return
        }
        val draft = draftFor(date.toEpochDay(), activeId(date.toEpochDay()))
        _uiState.value = NutritionUiState(date = date, draft = draft, busy = _uiState.value.busy,
            draftSaving = true, needsCleanup = (draft.key to draft.revision) in appliedOperations, error = interruptedMessage(draft))
        selectedDate.value = date
        _weightDraft.value = weightDraftFor(date.toEpochDay())
        enqueue(DraftWrite(draft, select = true))
    }

    private fun showDraft(draft: MealDraft) {
        activeEdits[draft.day] = draft.mealId
        _uiState.value = _uiState.value.copy(draft = draft, draftSaving = true, draftPersisted = false,
            needsCleanup = (draft.key to draft.revision) in appliedOperations, error = interruptedMessage(draft))
        enqueue(DraftWrite(draft, select = true))
    }

    fun updateName(value: String) = updateDraft { copy(name = value) }
    fun updateCalories(value: String) = updateDraft { copy(calories = value.filter(Char::isDigit)) }
    fun updateMealType(value: MealType) = updateDraft { copy(type = value) }

    private fun updateDraft(change: MealDraft.() -> MealDraft) {
        if (!_uiState.value.draft.editable(_uiState.value.busy, _uiState.value.needsCleanup)) return
        val next = _uiState.value.draft.change().copy(revision = ++revision)
        cache[next.key] = next
        _uiState.value = _uiState.value.copy(draft = next, draftSaving = true, draftPersisted = false, error = null)
        enqueue(DraftWrite(next, select = true, inFlight = false))
    }

    fun startEditing(meal: Meal) {
        if (_uiState.value.busy || meal.dayEpochDay != selectedDate.value.toEpochDay()) return
        // A pending creation must be acknowledged before its row can be changed.
        val pending = draftFor(meal.dayEpochDay, null)
        if (pending.creationId == meal.id) {
            showDraft(pending)
            return
        }
        showDraft(draftFor(meal.dayEpochDay, meal.id, meal))
    }

    /** Leave the edited draft intact; returning to this meal restores it. */
    fun cancelEditing() {
        if (_uiState.value.busy) return
        showDraft(draftFor(selectedDate.value.toEpochDay(), null))
    }

    fun saveMeal() = performMealAction(delete = false)
    fun deleteEditing() = performMealAction(delete = true)

    fun acknowledgeLegacyDraft() = performMealAction(delete = false, legacyCleanup = true)

    private fun performMealAction(delete: Boolean, legacyCleanup: Boolean = false) {
        val state = _uiState.value
        if (state.busy || state.loading || (delete && state.draft.mealId == null)) return
        if (state.draft.legacyUncertain != legacyCleanup) return
        if (!delete && !legacyCleanup && !state.draft.valid && (state.draft.key to state.draft.revision) !in appliedOperations) return
        val draft = if (!delete && !legacyCleanup && state.draft.mealId == null && state.draft.creationId == null) {
            state.draft.copy(creationId = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(2L),
                creationEpochMillis = System.currentTimeMillis(), revision = ++revision).also { cache[it.key] = it }
        } else state.draft
        val token = draft.key to draft.revision
        _uiState.value = state.copy(draft = draft, busy = true, error = null)
        viewModelScope.launch {
            try {
                if (!legacyCleanup && token !in appliedOperations) {
                    check(enqueue(DraftWrite(draft, inFlight = true)).await())
                    if (draft.mealId == null) {
                        repository.addMealOnce(draft.reservedMeal())
                    } else {
                        val current = repository.observeMealsForDay(draft.day).first().firstOrNull { it.id == draft.mealId }
                        if (delete) {
                            // A crash may have happened after deletion but before draft cleanup.
                            if (current != null) repository.deleteMeal(current)
                        } else {
                            checkNotNull(current) { "Meal unavailable" }
                            repository.updateMeal(current.copy(type = draft.type, name = draft.name.trim(), calories = draft.calories.toInt()))
                        }
                    }
                    appliedOperations += token
                }
                // Retire before enqueuing cleanup: a queued navigation snapshot must never resurrect this operation.
                retiredDrafts += token
                check(enqueue(DraftWrite(draft, remove = true, archive = legacyCleanup)).await())
                appliedOperations -= token
                if (cache[draft.key]?.revision == draft.revision) {
                    cache.remove(draft.key)
                    durableVersions.remove(draft.key)
                }
                if (activeEdits[draft.day] == draft.mealId) activeEdits[draft.day] = null
                if (_uiState.value.draft.key == draft.key && _uiState.value.draft.revision == draft.revision) {
                    val next = draftFor(draft.day, null)
                    _uiState.value = _uiState.value.copy(draft = next, needsCleanup = false, draftSaving = false,
                        draftPersisted = durableVersions[next.key] == next.revision)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (_uiState.value.draft.key == draft.key) _uiState.value = _uiState.value.copy(
                    needsCleanup = token in appliedOperations,
                    error = if (token in appliedOperations) "Запись обновлена, но черновик не очищен. Заверши сохранение повторно."
                        else if (draft.requiresRecovery) "Не удалось подтвердить сохранение. Черновик оставлен; повтор использует ту же запись и не заменяет другую."
                        else "Не удалось сохранить изменения. Введённые данные оставлены — попробуй ещё раз.",
                )
            } finally {
                _uiState.value = _uiState.value.copy(busy = false)
            }
        }
    }

    private fun buildHistory(totals: List<DailyCalories>, target: Int, today: Long): List<DaySummary> {
        val byDay = totals.associateBy { it.dayEpochDay }
        return (0 until HISTORY_DAYS).map { offset ->
            val day = today - (HISTORY_DAYS - 1 - offset)
            DaySummary(LocalDate.ofEpochDay(day), byDay[day]?.calories ?: 0, target)
        }
    }

    fun setTarget(value: Int) { repository.setCalorieTarget(value) }
    fun updateWeight(day: Long, text: String) {
        if (day != selectedDate.value.toEpochDay() || _weightDraft.value.busy || _weightDraft.value.creationId != null) return
        val next = _weightDraft.value.copy(text = text.filter { it.isDigit() || it == '.' || it == ',' },
            revision = _weightDraft.value.revision + 1, error = null, draftSaved = false)
        putWeight(next)
        persistWeight(next)
    }

    private fun weightDraftFor(day: Long): WeightDraft = weightDrafts.getOrPut(day) {
        val prefix = "weight_$day"
        val id = prefs.getLong("${prefix}_creation_id", 0L).takeIf { it < -1L }
        WeightDraft(day, text = prefs.getString("${prefix}_text", "").orEmpty(), creationId = id,
            creationEpochMillis = if (prefs.contains("${prefix}_creation_time")) prefs.getLong("${prefix}_creation_time", 0L) else null,
            error = if (id != null) "Сохранение веса не завершено. Повтори с теми же данными; второй записи не будет." else null,
            draftSaved = prefs.contains("${prefix}_text"))
    }

    private fun persistWeight(draft: WeightDraft, remove: Boolean = false): CompletableDeferred<Boolean> {
        val write = WeightDraftWrite(draft, remove)
        if (!weightWrites.trySend(write).isSuccess) write.result.complete(false)
        return write.result
    }

    private fun putWeight(draft: WeightDraft) {
        weightDrafts[draft.day] = draft
        if (selectedDate.value.toEpochDay() == draft.day) _weightDraft.value = draft
    }

    fun saveWeight(day: Long) {
        if (selectedDate.value.toEpochDay() != day || _weightDraft.value.day != day) return
        val started = _weightDraft.value.beginSave() ?: return
        val captured = if (started.creationId == null) started.copy(
            creationId = -(UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(2L),
            creationEpochMillis = System.currentTimeMillis(), revision = started.revision + 1, draftSaved = false) else started
        putWeight(captured)
        viewModelScope.launch {
            try {
                check(persistWeight(captured).await()) { "Не удалось сохранить операцию веса." }
                repository.addWeightOnce(captured.reservedEntry())
                check(persistWeight(captured, remove = true).await()) { "Не удалось очистить черновик веса." }
                putWeight((weightDrafts[captured.day] ?: captured).complete(captured, success = true))
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                putWeight((weightDrafts[captured.day] ?: captured).complete(captured, success = false))
            }
        }
    }

    class Factory(private val repository: NutritionRepository, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NutritionViewModel(repository, context.applicationContext) as T
    }
}

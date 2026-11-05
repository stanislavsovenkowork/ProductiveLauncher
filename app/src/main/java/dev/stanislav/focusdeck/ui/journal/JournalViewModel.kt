package dev.stanislav.focusdeck.ui.journal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultJournal
import dev.stanislav.focusdeck.data.vault.VaultState
import java.time.LocalDate
import java.time.LocalTime
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Поле дневника в заметке. Набор фиксирован — он задан шаблоном vault'а, не приложением. */
enum class JournalField(val label: String, val prompt: String) {
    MORNING("Утро", "С чего начался день?"),
    DAY("День", "Что происходило?"),
    EVENING("Вечер", "Чем закончился день?"),
    CONCLUSIONS("Выводы", "Что стоит запомнить?"),
    ;

    fun read(journal: VaultJournal): String = when (this) {
        MORNING -> journal.morning
        DAY -> journal.day
        EVENING -> journal.evening
        CONCLUSIONS -> journal.conclusions
    }

    fun replace(journal: VaultJournal, text: String): VaultJournal = when (this) {
        MORNING -> journal.copy(morning = text)
        DAY -> journal.copy(day = text)
        EVENING -> journal.copy(evening = text)
        CONCLUSIONS -> journal.copy(conclusions = text)
    }

    companion object {
        /** Какое поле открыть по умолчанию — то, которое сейчас и заполняют. */
        fun forTime(time: LocalTime): JournalField = when {
            time.hour < 12 -> MORNING
            time.hour < 18 -> DAY
            else -> EVENING
        }
    }
}

data class JournalDraftTarget(val date: LocalDate, val root: String, val field: JournalField) {
    val key: String get() {
        val rootHash = MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "journal_${rootHash}_${date}_${this.field.name}"
    }
}

data class JournalTextDraft(val target: JournalDraftTarget, val text: String, val base: String?, val revision: Long) {
    val normalized: String get() = text.trim()

    fun mergeInto(fresh: VaultJournal): VaultJournal {
        val current = target.field.read(fresh)
        if (current != normalized && (base == null || current != base)) throw JournalFieldConflictException()
        return target.field.replace(fresh, normalized)
    }

    /** An older acknowledgement advances the baseline, never the newer editor text. */
    fun afterAcknowledgement(saved: JournalTextDraft): JournalTextDraft =
        if (target == saved.target) copy(base = saved.normalized) else this
}

class JournalFieldConflictException : IllegalStateException(
    "Это поле изменено в Obsidian. Черновик оставлен; сравни тексты перед заменой.",
)

internal fun journalForComparison(note: DailyNote): VaultJournal {
    // Validate without writing: malformed markup must not masquerade as an empty
    // remote version and authorize discarding a local draft.
    DailyNoteParser.writeJournal(note, note.journal)
    return note.journal
}

/** The explicit choice applies only to the exact local and remote versions shown. */
data class JournalComparison(val local: JournalTextDraft, val remote: String) {
    fun resolve(current: JournalTextDraft?, root: String?, fresh: VaultJournal, keepLocal: Boolean): VaultJournal {
        check(current == local) { "Черновик изменился. Обнови сравнение перед выбором." }
        check(root == local.target.root) { "Выбрана другая папка Obsidian. Открой дневник заново." }
        check(local.target.field.read(fresh) == remote) { "Текст в Obsidian снова изменился. Обнови сравнение перед выбором." }
        return local.target.field.replace(fresh, if (keepLocal) local.normalized else remote)
    }
}

data class JournalComparisonState(
    val local: JournalTextDraft,
    val remote: String? = null,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

data class JournalEditorState(
    val draft: JournalTextDraft? = null,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val dirty: Boolean = false,
    val draftSaving: Boolean = false,
    val draftSaved: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val conflicted: Boolean = false,
)

private data class JournalDraftWrite(
    val draft: JournalTextDraft,
    val remove: Boolean = false,
    val result: CompletableDeferred<Boolean> = CompletableDeferred(),
)

class JournalViewModel(private val dayStore: VaultDayStore, context: Context) : ViewModel() {
    private val prefs = context.applicationContext.getSharedPreferences("journal_editor_drafts", Context.MODE_PRIVATE)
    private val recoveryPrefs = context.applicationContext.getSharedPreferences("journal_editor_recovery", Context.MODE_PRIVATE)
    private val entries = mutableMapOf<String, JournalEditorState>()
    private val delays = mutableMapOf<String, Job>()
    private val writes = Channel<JournalDraftWrite>(Channel.UNLIMITED)
    private val saveLock = Mutex()
    private var revision = 0L
    private var activeKey: String? = null
    private val _editor = MutableStateFlow(JournalEditorState())
    val editor: StateFlow<JournalEditorState> = _editor.asStateFlow()
    private val _comparison = MutableStateFlow<JournalComparisonState?>(null)
    val comparison: StateFlow<JournalComparisonState?> = _comparison.asStateFlow()
    private var comparisonRequest = 0L

    init {
        // Every edit is queued immediately; only the vault write is debounced.
        // commit runs off main, in order, and the UI only claims durability after its acknowledgement.
        viewModelScope.launch {
            for (write in writes) {
                val committed = withContext(Dispatchers.IO) {
                    try {
                        val draft = write.draft
                        val key = draft.target.key
                        val edit = prefs.edit()
                        if (write.remove) listOf("text", "base", "root").forEach { edit.remove("${key}_$it") }
                        else edit.putString("${key}_text", draft.text).putString("${key}_base", draft.base)
                            .putString("${key}_root", draft.target.root)
                        edit.commit()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        false
                    }
                }
                val current = entries[write.draft.target.key]
                if (!write.remove && current?.draft?.revision == write.draft.revision) {
                    put(current.copy(draftSaving = false, draftSaved = committed,
                        error = if (committed) current.error else "Не удалось сохранить черновик на устройстве. Ввод оставлен на экране."))
                }
                write.result.complete(committed)
            }
        }
    }

    private fun put(state: JournalEditorState) {
        val key = state.draft?.target?.key ?: return
        entries[key] = state
        if (activeKey == key) _editor.value = state
    }

    private fun persist(draft: JournalTextDraft, remove: Boolean = false): CompletableDeferred<Boolean> {
        val write = JournalDraftWrite(draft, remove)
        if (!writes.trySend(write).isSuccess) write.result.complete(false)
        return write.result
    }

    val journal: StateFlow<VaultJournal> = dayStore.note
        .map { it?.journal ?: VaultJournal() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultJournal())

    val vaultState: StateFlow<VaultState> = dayStore.state

    val selectedDate: StateFlow<LocalDate> = dayStore.selectedDate

    val daysWithNotes: StateFlow<Set<LocalDate>> = dayStore.datesWithNotes

    fun selectDate(date: LocalDate) {
        flushEditor()
        viewModelScope.launch {
            try { dayStore.selectDate(date) }
            catch (failure: Exception) { report(failure) }
        }
    }

    fun loadNoteDates() {
        viewModelScope.launch {
            try { dayStore.loadNoteDates() }
            catch (failure: Exception) { report(failure) }
        }
    }

    fun textOf(field: JournalField): String = field.read(journal.value)

    fun openEditor(field: JournalField) {
        dismissComparison()
        flushEditor()
        val root = dayStore.rootIdentity
        if (root == null) {
            activeKey = null
            _editor.value = JournalEditorState(error = "Подключи папку Obsidian, чтобы открыть дневник.")
            return
        }
        open(JournalDraftTarget(dayStore.selectedDate.value, root, field))
    }

    fun switchField(field: JournalField) {
        val target = _editor.value.draft?.target ?: return
        if (target.field == field) return
        flushEditor()
        open(target.copy(field = field))
    }

    private fun open(target: JournalDraftTarget) {
        val key = target.key
        activeKey = key
        val cached = entries[key]
        if (cached != null && (cached.dirty || cached.loading || cached.saving || cached.draftSaving)) {
            _editor.value = cached
            if (cached.dirty && !cached.saving && !cached.loading) requestSave(key)
            return
        }
        try {
            if (prefs.contains("${key}_text")) {
                check(prefs.getString("${key}_root", null) == target.root) { "Не удалось проверить папку черновика." }
                val draft = JournalTextDraft(target, prefs.getString("${key}_text", "").orEmpty(),
                    prefs.getString("${key}_base", null), ++revision)
                put(JournalEditorState(draft, ready = true, dirty = true, draftSaved = true))
                // Recovery is visible first; retry uses the captured baseline and root.
                requestSave(key)
                return
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            put(JournalEditorState(JournalTextDraft(target, "", null, ++revision), error = "Не удалось прочитать локальный черновик. Повтори открытие."))
            return
        }
        val initial = cached ?: JournalEditorState(JournalTextDraft(target, "", null, ++revision))
        put(initial.copy(loading = true, ready = false, error = null))
        viewModelScope.launch {
            try {
                check(dayStore.rootIdentity == target.root) { "Выбрана другая папка Obsidian. Открой дневник заново." }
                val note = dayStore.noteOnChecked(target.date) ?: error("Заметка за этот день отсутствует.")
                check(dayStore.rootIdentity == target.root) { "Папка Obsidian изменилась. Открой дневник заново." }
                val fresh = target.field.read(note.journal)
                val text = cached?.draft?.text?.takeIf { it.trim() == fresh } ?: fresh
                put(JournalEditorState(JournalTextDraft(target, text, fresh, ++revision), ready = true, saved = true))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                put(initial.copy(loading = false, ready = false, error = failure.message ?: "Не удалось прочитать запись."))
            }
        }
    }

    /** Raw editor text is persisted immediately and never replaced by an autosave response. */
    fun save(target: JournalDraftTarget, text: String) {
        val state = _editor.value
        val previous = state.draft ?: return
        if (!state.ready || previous.target != target || previous.text == text || _comparison.value?.local?.target == target) return
        val draft = previous.copy(text = text, revision = ++revision)
        put(state.copy(draft = draft, dirty = true, draftSaving = true, draftSaved = false, saved = false, error = null))
        persist(draft)
        val key = draft.target.key
        delays.remove(key)?.cancel()
        delays[key] = viewModelScope.launch {
            delay(800)
            requestSave(key)
        }
    }

    fun flushEditor() {
        val key = activeKey ?: return
        delays.remove(key)?.cancel()
        requestSave(key)
    }

    fun retryEditor() {
        val state = _editor.value
        val target = state.draft?.target ?: return
        if (!state.ready) open(target) else flushEditor()
    }

    fun openComparison() {
        if (_comparison.value?.busy == true) return
        val captured = _comparison.value?.local ?: _editor.value.draft ?: return
        val key = captured.target.key
        delays.remove(key)?.cancel()
        val request = ++comparisonRequest
        _comparison.value = JournalComparisonState(captured)
        viewModelScope.launch {
            saveLock.withLock {
                try {
                    val local = entries[key]?.draft ?: error("Черновик недоступен. Открой запись заново.")
                    check(dayStore.rootIdentity == local.target.root) { "Выбрана другая папка Obsidian. Открой дневник заново." }
                    val note = dayStore.noteOnChecked(local.target.date) ?: error("Заметка за этот день отсутствует.")
                    check(dayStore.rootIdentity == local.target.root) { "Папка Obsidian изменилась. Открой дневник заново." }
                    if (request == comparisonRequest) _comparison.value = JournalComparisonState(local,
                        remote = local.target.field.read(journalForComparison(note)), loading = false)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (request == comparisonRequest) _comparison.value = JournalComparisonState(captured,
                        loading = false, error = failure.message ?: "Не удалось прочитать вторую версию.")
                }
            }
        }
    }

    fun dismissComparison() {
        if (_comparison.value?.busy == true) return
        comparisonRequest++
        _comparison.value = null
    }

    fun resolveComparison(keepLocal: Boolean) {
        val shown = _comparison.value ?: return
        val remote = shown.remote ?: return
        if (shown.loading || shown.busy || shown.error != null) return
        val compared = JournalComparison(shown.local, remote)
        val target = shown.local.target
        val key = target.key
        _comparison.value = shown.copy(busy = true)
        delays.remove(key)?.cancel()
        viewModelScope.launch {
            saveLock.withLock {
                var adopted = false
                try {
                    check(dayStore.rootIdentity == target.root) { "Выбрана другая папка Obsidian. Открой дневник заново." }
                    val note = dayStore.noteOnChecked(target.date) ?: error("Заметка за этот день отсутствует.")
                    compared.resolve(entries[key]?.draft, dayStore.rootIdentity, journalForComparison(note), keepLocal)
                    // Both versions are durably backed up BEFORE either can be replaced.
                    // A unique record keeps earlier recovery copies intact as well.
                    check(withContext(Dispatchers.IO) {
                        val record = JSONObject().put("root", target.root).put("date", target.date.toString())
                            .put("field", target.field.name).put("local", shown.local.text).put("remote", remote)
                            .put("base", shown.local.base ?: JSONObject.NULL).put("keepLocal", keepLocal)
                            .put("createdAt", System.currentTimeMillis())
                        recoveryPrefs.edit().putString(UUID.randomUUID().toString(), record.toString()).commit()
                    }) { "Не удалось сохранить резервные версии. Оба текста оставлены без изменений." }
                    val written = dayStore.editNoteOn(target.date, expectedRoot = target.root) { fresh ->
                        val selected = compared.resolve(entries[key]?.draft, dayStore.rootIdentity, journalForComparison(fresh), keepLocal)
                        if (keepLocal) DailyNoteParser.writeJournal(fresh, selected) else fresh.lines.joinToString("\n")
                    }
                    check(written) { "Не удалось применить выбранную версию. Черновик оставлен." }
                    check(dayStore.rootIdentity == target.root) { "Папка Obsidian изменилась. Черновик оставлен; открой дневник заново." }
                    val current = checkNotNull(entries[key])
                    check(current.draft == shown.local) { "Черновик изменился. Обнови сравнение перед выбором." }
                    val text = if (keepLocal) shown.local.text else remote
                    val accepted = shown.local.copy(text = text, base = text.trim(), revision = ++revision)
                    put(current.copy(draft = accepted, dirty = true, saved = true, saving = false,
                        draftSaving = true, draftSaved = false, error = null, conflicted = false))
                    adopted = true
                    // Persist the explicit choice before cleanup: a restart must not replay
                    // the discarded local version after "take Obsidian".
                    check(persist(accepted).await()) { "Выбранная версия принята, но локальный черновик не обновлён. Повтори сохранение." }
                    val removed = persist(accepted, remove = true).await()
                    entries[key]?.let { latest ->
                        if (latest.draft?.revision == accepted.revision) put(latest.copy(dirty = !removed,
                            draftSaving = false, draftSaved = !removed,
                            error = if (removed) null else "Версия принята, но черновик не очищен. Повтори сохранение."))
                    }
                    _comparison.value = null
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    val message = failure.message ?: "Не удалось применить выбор. Черновик оставлен."
                    if (adopted) {
                        _comparison.value = null
                        entries[key]?.let { put(it.copy(draftSaving = false, error = message)) }
                    } else _comparison.value = shown.copy(busy = false, error = message)
                }
            }
        }
    }

    private fun requestSave(key: String) {
        viewModelScope.launch {
            saveLock.withLock {
                val state = entries[key] ?: return@withLock
                val draft = state.draft ?: return@withLock
                if (!state.ready || !state.dirty || _comparison.value?.local?.target?.key == key) return@withLock
                put(state.copy(saving = true, error = null))
                try {
                    check(persist(draft).await()) { "Не удалось сохранить локальный черновик. Запись в заметку отложена." }
                    val written = dayStore.editNoteOn(draft.target.date, expectedRoot = draft.target.root) { fresh ->
                        DailyNoteParser.writeJournal(fresh, draft.mergeInto(fresh.journal))
                    }
                    check(written) { "Не удалось записать заметку. Черновик сохранён на устройстве." }
                    val latest = entries[key] ?: return@withLock
                    val latestDraft = latest.draft ?: return@withLock
                    val rebased = latestDraft.afterAcknowledgement(draft)
                    if (latestDraft.revision != draft.revision) {
                        put(latest.copy(draft = rebased, saving = false, draftSaving = true, draftSaved = false, conflicted = false))
                        persist(rebased)
                    } else {
                        put(latest.copy(draft = rebased, dirty = false, saving = false, saved = true, draftSaving = true, conflicted = false))
                        val removed = persist(draft, remove = true).await()
                        val current = entries[key]
                        if (current?.draft?.revision == draft.revision) {
                            put(current.copy(draftSaving = false, draftSaved = !removed, dirty = !removed,
                                error = if (removed) null else "Запись сохранена, но локальный черновик не очищен. Повтори сохранение."))
                        }
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    entries[key]?.let { put(it.copy(saving = false, conflicted = failure is JournalFieldConflictException,
                        error = failure.message ?: "Не удалось сохранить запись. Ввод оставлен.")) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            try { dayStore.refresh() }
            catch (failure: Exception) { report(failure) }
        }
    }

    private fun report(failure: Exception) {
        if (failure is CancellationException) throw failure
        val state = _editor.value.copy(error = failure.message ?: "Не удалось прочитать дневник.")
        if (state.draft == null) _editor.value = state else put(state)
    }

    class Factory(private val dayStore: VaultDayStore, private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = JournalViewModel(dayStore, context.applicationContext) as T
    }
}

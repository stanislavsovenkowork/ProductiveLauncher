package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** В каком состоянии сейчас заметка дня — от этого зависит, что показывать вместо данных. */
enum class VaultState {
    /** Папка vault не выбрана или право доступа отозвано системой. */
    NOT_CONNECTED,

    /** Папка есть, заметки за выбранный день нет — её можно создать одной кнопкой. */
    NOTE_MISSING,

    /** Заметка прочитана, данные актуальны. */
    READY,

    /** Файл есть, но прочитать не удалось. */
    ERROR,
}

/**
 * Единственная точка доступа к заметке дня.
 *
 * Почему хранилище одно на всё приложение, а не по репозиторию на экран: заметка —
 * это один файл, и два независимых читателя неизбежно разошлись бы в том, какая
 * версия текста считается текущей. Здесь версия одна, и все экраны смотрят на неё.
 *
 * Почему каждая правка идёт через [edit] с перечитыванием: между чтением и записью
 * файл мог измениться — Obsidian на телефоне, скрипт на Mac, Syncthing принёс чужую
 * версию. Запись поверх устаревшего текста затёрла бы чужие правки молча. Поэтому
 * правка всегда применяется к только что прочитанному содержимому, под общим мьютексом.
 */
class VaultDayStore(private val repository: VaultRepository) {

    val isConnected: Boolean get() = repository.isConnected
    val changes = repository.changes
    val rootIdentity: String? get() = repository.vaultUri.value?.toString()
    val rootChanges = repository.vaultUri

    private val writeLock = Mutex()

    private val _note = MutableStateFlow<DailyNote?>(null)
    val note: StateFlow<DailyNote?> = _note.asStateFlow()

    private val _state = MutableStateFlow(VaultState.NOT_CONNECTED)
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** День, который сейчас открыт в планировании и дневнике. */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /**
     * Отдельный слепок сегодняшнего дня — для главного экрана.
     *
     * Главный экран всегда про сегодня, и если бы он читал ту же заметку, что
     * планирование, то уход на завтра в планировании подменил бы ему данные:
     * экранное время за сегодня рядом с чужими задачами.
     */
    private val _todayNote = MutableStateFlow<DailyNote?>(null)
    val todayNote: StateFlow<DailyNote?> = _todayNote.asStateFlow()

    private val _todayState = MutableStateFlow(VaultState.NOT_CONNECTED)
    val todayState: StateFlow<VaultState> = _todayState.asStateFlow()

    /**
     * Дни, для которых заметка уже заведена — для точек под числами в полосе.
     *
     * Берётся из имён файлов одним обходом папки. Раньше здесь было число задач,
     * но оно стоило чтения файла на каждый видимый день, и при прокрутке полосы
     * это превращалось в десятки чтений в секунду.
     */
    private val _datesWithNotes = MutableStateFlow<Set<LocalDate>>(emptySet())
    val datesWithNotes: StateFlow<Set<LocalDate>> = _datesWithNotes.asStateFlow()

    /** Сегодня пересчитывается при каждом обращении: приложение живёт через полночь. */
    private val today: LocalDate get() = LocalDate.now()

    private val date: LocalDate get() = _selectedDate.value

    suspend fun selectDate(value: LocalDate) {
        writeLock.withLock {
            if (value == _selectedDate.value) return@withLock
            _selectedDate.value = value
            reload()
        }
    }

    /**
     * Возвращает выбор на сегодня, если он успел устареть.
     *
     * День выбирается на время, а не навсегда: лаунчер живёт в памяти неделями,
     * и без сброса «вчера, которое я смотрел перед сном» встречало бы утром как
     * текущий день. Возвращает true, если день действительно сменился.
     */
    suspend fun resetToToday(): Boolean {
        if (_selectedDate.value == today) return false
        selectDate(today)
        return true
    }

    suspend fun refresh() {
        writeLock.withLock { reload() }
    }

    /** Перечитывает сегодняшний день для главного экрана, не трогая выбранный. */
    suspend fun refreshToday() {
        if (date == today) {
            refresh()
            return
        }
        writeLock.withLock { reloadToday() }
    }

    /** Создаёт заметку за выбранный день по шаблону vault'а. */
    suspend fun createNote(): Boolean = writeLock.withLock {
        val created = repository.createDailyNote(date)
        if (created) _datesWithNotes.value = _datesWithNotes.value + date
        reload()
        created
    }

    /** Обновляет список заведённых дней. Один обход папки, а не чтение файлов. */
    suspend fun loadNoteDates() {
        _datesWithNotes.value = repository.datesWithNotes()
    }

    suspend fun toggleTask(task: VaultTask, done: Boolean): Boolean = edit { fresh ->
        DailyNoteParser.resolveTask(fresh, task)?.let {
            DailyNoteParser.toggleTask(fresh, it.lineIndex, done)
        }
    }

    suspend fun addTask(title: String, durationMinutes: Int?, startMinuteOfDay: Int? = null,
        projectId: String? = null, goalId: String? = null, areaId: String? = null,
        stableId: String = java.util.UUID.randomUUID().toString(), expectedRoot: String? = null,
        targetDay: LocalDate = date, noteLink: String? = null): Boolean {
        if (title.isBlank()) return false
        return edit(expectedRoot, targetDay) { fresh ->
            DailyNoteParser.addTask(fresh, title.trim(), startMinuteOfDay, durationMinutes, projectId, goalId, areaId, stableId, noteLink)
        }
    }

    suspend fun scheduleTask(task: VaultTask, startMinuteOfDay: Int, durationMinutes: Int?): Boolean =
        edit { fresh ->
            DailyNoteParser.resolveTask(fresh, task)?.let {
                DailyNoteParser.scheduleTask(fresh, it.lineIndex, startMinuteOfDay, durationMinutes)
            }
        }

    suspend fun renameTask(task: VaultTask, title: String): Boolean {
        if (title.isBlank()) return false
        return edit { fresh ->
            DailyNoteParser.resolveTask(fresh, task)?.let {
                DailyNoteParser.renameTask(fresh, it.lineIndex, title)
            }
        }
    }

    suspend fun linkTask(task: VaultTask, projectId: String?, goalId: String?, areaId: String?, expectedRoot: String? = null, targetDay: LocalDate = date, noteLink: String? = null): Boolean {
        val stableId = task.stableId ?: java.util.UUID.randomUUID().toString()
        return edit(expectedRoot, targetDay) { fresh ->
            DailyNoteParser.resolveTask(fresh, task)?.let {
                DailyNoteParser.linkTask(fresh, it.lineIndex, projectId, goalId, areaId, stableId, noteLink)
            }
        }
    }

    suspend fun setDuration(task: VaultTask, durationMinutes: Int): Boolean = edit { fresh ->
        DailyNoteParser.resolveTask(fresh, task)?.let {
            DailyNoteParser.setDuration(fresh, it.lineIndex, durationMinutes)
        }
    }

    suspend fun deleteTask(task: VaultTask): Boolean = edit { fresh ->
        DailyNoteParser.resolveTask(fresh, task)?.let {
            DailyNoteParser.deleteTask(fresh, it.lineIndex)
        }
    }

    suspend fun unscheduleTask(task: VaultTask): Boolean = edit { fresh ->
        DailyNoteParser.resolveTask(fresh, task)?.let {
            DailyNoteParser.unscheduleTask(fresh, it.lineIndex)
        }
    }

    /** Дневник за произвольный день — разовое чтение, минуя состояние сегодняшней заметки. */
    suspend fun journalOn(date: LocalDate): VaultJournal? =
        writeLock.withLock { repository.readDailyNote(date)?.journal }

    suspend fun saveJournal(journal: VaultJournal): Boolean = edit { fresh ->
        DailyNoteParser.writeJournal(fresh, journal)
    }

    /** Разовое чтение заметки за произвольный день, минуя состояние экрана. */
    suspend fun noteOn(date: LocalDate): DailyNote? =
        writeLock.withLock { repository.readDailyNote(date) }

    /** Several days at once for progress history; one folder scan instead of one per day. */
    suspend fun notesBetween(from: LocalDate, to: LocalDate): DailyNotesRead =
        writeLock.withLock { repository.readDailyNotes(from, to) }

    /** Reviews must distinguish a missing daily note from an existing unreadable one. */
    suspend fun noteOnChecked(date: LocalDate): DailyNote? = writeLock.withLock {
        val note = repository.readDailyNote(date)
        if (note == null && repository.dailyNoteExists(date)) {
            throw java.io.IOException("Не удалось прочитать заметку за $date")
        }
        note
    }

    /** Date-specific edits for the morning/evening ritual. Never redirect a write
     * because the user switched the planner to another day during disk I/O. */
    suspend fun editNoteOn(
        day: LocalDate,
        createIfMissing: Boolean = false,
        expectedRoot: String? = null,
        transform: (DailyNote) -> String,
    ): Boolean = writeLock.withLock {
        check(expectedRoot == null || rootIdentity == expectedRoot) { "Выбрана другая папка Obsidian" }
        var fresh = repository.readDailyNote(day)
        if (fresh == null && createIfMissing) {
            if (!repository.createDailyNote(day, expectedRoot)) return@withLock false
            fresh = repository.readDailyNote(day)
        }
        val note = fresh ?: return@withLock false
        check(expectedRoot == null || rootIdentity == expectedRoot) { "Папка Obsidian изменилась во время чтения" }
        val updated = transform(note)
        if (updated == note.lines.joinToString("\n")) return@withLock true
        if (!repository.writeDailyNote(day, updated, note.lines.joinToString("\n"), expectedRoot)) return@withLock false
        publishForDay(day, DailyNoteParser.parse(day, note.path, updated), VaultState.READY)
        _datesWithNotes.value = _datesWithNotes.value + day
        true
    }

    /** Move the intention forward without erasing the truthful unfinished entry
     * from today's review. Repeated taps do not create duplicate tomorrow tasks. */
    suspend fun copyUnfinishedTaskToDay(
        from: LocalDate,
        to: LocalDate,
        task: VaultTask,
        expectedRoot: String? = null,
    ): Boolean {
        val operationRoot = expectedRoot ?: rootIdentity ?: return false
        fun checkRoot() = check(rootIdentity == operationRoot) { "Папка Obsidian изменилась. Перенос отменён." }
        checkRoot()
        return writeLock.withLock {
            checkRoot()
            val source = repository.readDailyNote(from)
            checkRoot()
            val resolved = source?.let { DailyNoteParser.resolveTask(it, task) }
                ?.takeIf { !it.done } ?: return@withLock false
            checkRoot()
            var target = repository.readDailyNote(to)
            checkRoot()
            if (target == null) {
                checkRoot()
                val created = repository.createDailyNote(to, expectedRoot = operationRoot)
                checkRoot()
                if (!created) return@withLock false
                target = repository.readDailyNote(to)
                checkRoot()
            }
            val destination = target ?: return@withLock false
            if (destination.tasks.any { it.title == resolved.title && it.projectId == resolved.projectId && it.goalId == resolved.goalId }) return@withLock true
            val updated = DailyNoteParser.addTask(
                destination,
                resolved.title,
                durationMinutes = resolved.durationMinutes,
                projectId = resolved.projectId, goalId = resolved.goalId, areaId = resolved.areaId, noteLink = resolved.noteLink,
            )
            checkRoot()
            val written = repository.writeDailyNote(to, updated, destination.lines.joinToString("\n"), expectedRoot = operationRoot)
            checkRoot()
            if (!written) return@withLock false
            publishForDay(to, DailyNoteParser.parse(to, destination.path, updated), VaultState.READY)
            _datesWithNotes.value = _datesWithNotes.value + to
            true
        }
    }

    /**
     * Пишет итог в заметку за произвольный день, не трогая открытый.
     *
     * Отдельно от [edit] именно поэтому: итог за вчера дописывается сегодня,
     * и подменять им заметку, которая сейчас на экране, нельзя.
     *
     * Возвращает false и когда писать некуда (в заметке нет блока итогов),
     * и когда те же строки уже стоят на месте — в обоих случаях делать нечего.
     */
    suspend fun writeSummaryOn(date: LocalDate, summary: DailySummary, expectedRoot: String? = null): Boolean {
        val operationRoot = expectedRoot ?: rootIdentity ?: return false
        fun checkRoot() = check(rootIdentity == operationRoot) { "Папка Obsidian изменилась. Запись итога отменена." }
        checkRoot()
        return writeLock.withLock {
            checkRoot()
            val fresh = repository.readDailyNote(date)
            checkRoot()
            val note = fresh ?: return@withLock false
            val updated = DailyNoteParser.writeSummary(note, summary)
            if (updated == note.lines.joinToString("\n")) return@withLock false

            checkRoot()
            val written = repository.writeDailyNote(date, updated, note.lines.joinToString("\n"), expectedRoot = operationRoot)
            checkRoot()
            // Если правили тот день, что открыт на экране, — показать правку.
            if (written) publishForDay(date, DailyNoteParser.parse(date, note.path, updated), VaultState.READY)
            written
        }
    }

    /**
     * Читает файл заново, применяет правку к свежему тексту и записывает.
     * `null` из [transform] означает «задачу в файле не нашли» — тогда ничего не пишем,
     * чтобы не превратить промах в порчу заметки.
     */
    private suspend fun edit(expectedRoot: String? = null, targetDay: LocalDate = date, transform: (DailyNote) -> String?): Boolean {
        val day = targetDay
        val operationRoot = expectedRoot ?: rootIdentity ?: return false
        check(rootIdentity == operationRoot) { "Папка Obsidian изменилась. Открой задачу заново." }
        // Сразу показываем результат на той версии, что уже в памяти, и только потом
        // идём на диск. Чтение, запись и перечитывание через SAF занимают заметное
        // время, и всё это время галочка стояла неотмеченной — со стороны это
        // выглядело как подвисание. Если правка не пройдёт, reload вернёт правду.
        applyOptimistically(day, transform)

        return writeLock.withLock {
            check(rootIdentity == operationRoot) { "Папка Obsidian изменилась. Правка отменена." }
            val fresh = repository.readDailyNote(day)
            check(rootIdentity == operationRoot) { "Папка Obsidian изменилась. Правка отменена." }
            if (fresh == null) {
                if (day == date) reload()
                return@withLock false
            }
            val updated = transform(fresh)
            if (updated == null || updated == fresh.lines.joinToString("\n")) {
                publishForDay(day, fresh, VaultState.READY)
                return@withLock updated != null
            }
            val written = repository.writeDailyNote(day, updated, fresh.lines.joinToString("\n"), operationRoot)
            if (written) {
                // Записали ровно этот текст — перечитывать файл незачем.
                publishForDay(day, DailyNoteParser.parse(day, fresh.path, updated), VaultState.READY)
                _datesWithNotes.value = _datesWithNotes.value + day
            } else {
                if (day == date) reload()
            }
            written
        }
    }

    /** Показывает результат правки, не дожидаясь диска. Чистая функция, вызвать дважды безопасно. */
    private fun applyOptimistically(day: LocalDate, transform: (DailyNote) -> String?) {
        if (day != date) return
        val current = _note.value?.takeIf { it.date == day } ?: return
        val preview = transform(current) ?: return
        publishForDay(day, DailyNoteParser.parse(day, current.path, preview), _state.value)
    }

    private fun publishForDay(day: LocalDate, note: DailyNote?, state: VaultState) {
        if (day == date) {
            _note.value = note
            _state.value = state
        }
        if (day == today) {
            _todayNote.value = note
            _todayState.value = state
        }
    }

    /**
     * Кладёт заметку в выбранный день и, если это сегодня, в слепок для главного экрана.
     */
    private fun publish(note: DailyNote?, state: VaultState) {
        publishForDay(date, note, state)
    }

    /** Перечитывает заметку и пересчитывает состояние. Вызывать только под [writeLock]. */
    private suspend fun reload() {
        if (!repository.isConnected) {
            publish(null, VaultState.NOT_CONNECTED)
            return
        }
        val day = date
        val fresh = repository.readDailyNote(day)
        publish(
            fresh,
            when {
                fresh != null -> VaultState.READY
                repository.dailyNoteExists(day) -> VaultState.ERROR
                else -> VaultState.NOTE_MISSING
            },
        )
        if (day != today) reloadToday()
    }

    /** Читает сегодняшний день отдельно: он нужен главному экрану всегда. */
    private suspend fun reloadToday() {
        if (!repository.isConnected) {
            _todayNote.value = null
            _todayState.value = VaultState.NOT_CONNECTED
            return
        }
        val fresh = repository.readDailyNote(today)
        _todayNote.value = fresh
        _todayState.value = when {
            fresh != null -> VaultState.READY
            repository.dailyNoteExists(today) -> VaultState.ERROR
            else -> VaultState.NOTE_MISSING
        }
    }
}

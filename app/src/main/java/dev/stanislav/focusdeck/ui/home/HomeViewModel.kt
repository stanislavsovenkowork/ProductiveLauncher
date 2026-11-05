package dev.stanislav.focusdeck.ui.home

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.db.Meal
import dev.stanislav.focusdeck.data.db.MealType
import dev.stanislav.focusdeck.data.repo.FocusBlockRepository
import dev.stanislav.focusdeck.data.repo.FocusClockAnchor
import dev.stanislav.focusdeck.FocusDeckApp
import dev.stanislav.focusdeck.ui.apps.safetyExempt
import dev.stanislav.focusdeck.data.repo.focusMillis
import dev.stanislav.focusdeck.data.repo.afterReturn
import java.util.UUID
import dev.stanislav.focusdeck.data.repo.NutritionRepository
import dev.stanislav.focusdeck.data.repo.UsageStatsRepository
import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.ui.ritual.RitualNotes
import dev.stanislav.focusdeck.ui.ritual.validTimedTask
import dev.stanislav.focusdeck.data.vault.DailySummary
import dev.stanislav.focusdeck.data.vault.VaultDayStore
import dev.stanislav.focusdeck.data.vault.VaultState
import dev.stanislav.focusdeck.data.vault.VaultTask
import dev.stanislav.focusdeck.ui.components.SegmentKind
import dev.stanislav.focusdeck.ui.theme.FocusDeckColors
import dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES
import dev.stanislav.focusdeck.ui.planning.ScheduledItem
import dev.stanislav.focusdeck.ui.planning.dayFraction
import dev.stanislav.focusdeck.ui.planning.laneToneShift
import dev.stanislav.focusdeck.ui.planning.layoutLanes
import dev.stanislav.focusdeck.ui.theme.taskFill
import dev.stanislav.focusdeck.ui.components.TapeSegment
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TimeOfDay { MORNING, DAY, EVENING }

internal const val SESSION_MINUTES = 25
internal fun sessionTargetMinutes(block: FocusBlock): Int =
    if (block.category == null) block.plannedMinutes else block.plannedMinutes.coerceIn(1, SESSION_MINUTES)

/** Resolve only an explicitly persisted binding. Duplicate IDs/legacy tuples are never guessed. */
internal fun resolveFocusTask(block: FocusBlock, note: DailyNote?, currentRoot: String?): VaultTask? {
    val boundRoot = block.taskVaultRoot?.takeIf { it.isNotBlank() } ?: return null
    if (boundRoot != currentRoot || note == null || note.date.toEpochDay() != block.dayEpochDay) return null
    val stableId = block.taskStableId
    if (stableId != null) {
        if (stableId.isBlank()) return null
        return note.tasks.filter { it.stableId == stableId }.singleOrNull()
    }
    return note.tasks.filter {
        it.title == block.title && it.startMinuteOfDay == block.taskStartMinuteOfDay &&
            (it.durationMinutes ?: DEFAULT_TASK_MINUTES) == block.plannedMinutes
    }.singleOrNull()
}

/** A persisted checkbox is the prerequisite, including an already-done task on a safe retry. */
internal suspend fun finishFocusBlockAfterTaskWrite(
    writeTask: suspend () -> Boolean,
    finishBlock: suspend () -> Unit,
) {
    check(writeTask()) { "Не удалось сохранить отметку задачи" }
    finishBlock()
}

/** Контекстная подсказка вверху главного экрана. Показывается только когда уместна. */
sealed interface HomeCue {
    data object JournalYesterdayEmpty : HomeCue
    data class MealMissing(val type: MealType, val label: String, val expectedTime: String) : HomeCue
}

/**
 * Что предложить большой кнопкой «Начать».
 *
 * Порядок жёсткий и без вариантов: то, что идёт по плану прямо сейчас, потом
 * то, что уже должно было пройти и не сделано, и только потом отдых. Выбор
 * «из чего начать» — работа, которую человек уже проделал, когда расставлял
 * день на таймлайне; главный экран не должен предлагать её заново списком.
 */
sealed interface NextUp {
    /** Время задачи идёт прямо сейчас. */
    data class Now(val task: VaultTask) : NextUp

    /** Время прошло, а галочки нет — самая свежая из просроченных. */
    data class Overdue(val task: VaultTask) : NextUp

    /**
     * На сейчас ничего не запланировано. [nextStartMinute] — время следующей
     * задачи, если она сегодня есть: без него отдых выглядел бы как пустой день,
     * хотя до ближайшей задачи может остаться десять минут.
     */
    data class Rest(val nextStartMinute: Int? = null) : NextUp
}

/**
 * Задача под большую кнопку.
 *
 * Задачи без времени сюда не попадают намеренно: у них нет причины начаться
 * именно сейчас, и подставлять их значило бы выбирать за человека — а он уже
 * выбрал, когда расставлял день.
 */
internal fun resolveNextUp(tasks: List<VaultTask>, minuteOfDay: Int): NextUp {
    val pending = tasks.filter { !it.done && validTimedTask(it) }

    pending.firstOrNull { task ->
        val start = task.startMinuteOfDay!!
        minuteOfDay >= start &&
            minuteOfDay < start + (task.durationMinutes ?: DEFAULT_TASK_MINUTES)
    }?.let { return NextUp.Now(it) }

    // Самая свежая из просроченных, а не самая старая: хвост из брошенных задач
    // иначе держал бы кнопку на том, что человек пропустил ещё утром.
    pending
        .filter { it.startMinuteOfDay!! < minuteOfDay }
        .maxByOrNull { it.startMinuteOfDay!! }
        ?.let { return NextUp.Overdue(it) }

    return NextUp.Rest(
        nextStartMinute = pending
            .filter { it.startMinuteOfDay!! > minuteOfDay }
            .minOfOrNull { it.startMinuteOfDay!! },
    )
}

/** A missed start should be visible while its time slot is still running,
 * not only after the entire slot has elapsed. */
internal fun resolveRecoveryCue(
    note: DailyNote?,
    nextUp: NextUp,
    minuteOfDay: Int,
    focusActive: Boolean,
): String? {
    if (focusActive) return null
    val task = when (nextUp) {
        is NextUp.Now -> nextUp.task
        is NextUp.Overdue -> nextUp.task
        is NextUp.Rest -> return null
    }
    if (minuteOfDay - task.startMinuteOfDay!! < 15) return null
    return RitualNotes.value(note, RitualNotes.IF_THEN)
        ?: "Не получилось начать вовремя? Переставь блок на реальное время."
}

/** Ширина окна крупного плана: час до точки отсчёта и час после. */
const val UPCOMING_HOURS = 2

private const val MINUTES_IN_DAY = 24 * 60

/** Короче этого промежуток не подписываем: подпись не влезает, а шума добавляет. */
private const val MIN_GAP_MINUTES = 15

/**
 * Ближайшие часы крупным планом.
 *
 * Лента суток отвечала на вопрос «как выглядит день целиком», но в ширину
 * экрана сутки укладываются так, что час — это сорок пикселей, а задача на
 * пятьдесят минут — тридцать. Разглядеть там нечего, и лента читалась как
 * украшение. Вопрос, который на главном экране действительно есть, — «что
 * сейчас и что сразу за ним», и на него отвечает окно в пару часов, где
 * блокам хватает места на подписи.
 */
data class UpcomingWindow(
    val startMinuteOfDay: Int = 0,
    val endMinuteOfDay: Int = UPCOMING_HOURS * 60,
    /** Где внутри окна находится «сейчас», 0..1. null — сейчас за его краем. */
    val nowFraction: Float? = null,
    /** Окно стоит на «сейчас» и едет вместе с ним, а не отведено пальцем. */
    val followsNow: Boolean = true,
    val blocks: List<UpcomingBlock> = emptyList(),
    /** Промежутки, в которых ничего не стоит, — их длина и есть ответ «сколько свободно». */
    val gaps: List<UpcomingGap> = emptyList(),
) {
    val spanMinutes: Int get() = (endMinuteOfDay - startMinuteOfDay).coerceAtLeast(1)

    /** Место окна на ленте суток — по нему рисуется рамка выбранного участка. */
    val tapeRange: ClosedFloatingPointRange<Float>
        get() = (startMinuteOfDay / 1440f)..(endMinuteOfDay / 1440f)

    /** Часовые отметки внутри окна — доли по ширине. */
    val hourMarks: List<Float>
        get() {
            val firstHour = (startMinuteOfDay / 60) + 1
            val lastHour = endMinuteOfDay / 60
            return (firstHour..lastHour).map { hour ->
                (hour * 60 - startMinuteOfDay).toFloat() / spanMinutes
            }
        }
}

/** Свободный промежуток внутри окна. */
data class UpcomingGap(
    val startFraction: Float,
    val widthFraction: Float,
    val minutes: Int,
)

/** Задача внутри окна ближайших часов. Доли — от левого края окна. */
data class UpcomingBlock(
    val startFraction: Float,
    val widthFraction: Float,
    val title: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val fill: Color,
    /** Началась раньше окна — левый край обрезан, и скруглять его не нужно. */
    val clippedStart: Boolean,
)

data class HomeUiState(
    val tasks: List<VaultTask> = emptyList(),
    val vaultState: VaultState = VaultState.NOT_CONNECTED,
    val activeBlock: FocusBlock? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val screenTimeMinutesToday: Long = 0,
    val focusMinutesToday: Int = 0,
    val caloriesToday: Int = 0,
    val latestWeightKg: Float? = null,
    val tapeSegments: List<TapeSegment> = emptyList(),
    val timeOfDay: TimeOfDay = TimeOfDay.DAY,
    val cue: HomeCue? = null,
    val screenTimeLimitMinutes: Long = 240,
    val interruptedBlocksToday: Int = 0,
    val nextUp: NextUp = NextUp.Rest(),
    /** Задача, под которую идёт текущий блок, — если он был начат с задачи. */
    val activeTask: VaultTask? = null,
    val blockActionError: String? = null,
    val blockActionBusy: Boolean = false,
    val summaryStatus: String? = null,
    val summaryBusy: Boolean = false,
    val upcoming: UpcomingWindow = UpcomingWindow(),
    val recoveryCue: String? = null,
    val tomorrowPrepared: Boolean = false,
    /** «Главное» chosen in the ritual, when it is still one of today's tasks. */
    val priority: String? = null,
) {
    val elapsedSecondsInBlock: Long
        get() = activeBlock?.let { it.focusMillis(nowMillis) / 1000 } ?: 0

    val remainingSecondsInBlock: Long
        get() = activeBlock?.let { (sessionTargetMinutes(it) * 60L - elapsedSecondsInBlock).coerceAtLeast(0) } ?: 0

    val blockProgress: Float
        get() = activeBlock?.let {
            (elapsedSecondsInBlock.toFloat() / (sessionTargetMinutes(it) * 60f)).coerceIn(0f, 1f)
        } ?: 0f
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val palette: FocusDeckColors,
    private val dayStore: VaultDayStore,
    private val focusBlockRepository: FocusBlockRepository,
    private val usageStatsRepository: UsageStatsRepository,
    private val nutritionRepository: NutritionRepository,
    context: Context,
) : ViewModel() {

    private val today: Long get() = LocalDate.now().toEpochDay()
    private val todayEpoch = MutableStateFlow(today)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val applicationContext = context.applicationContext
    private val app = applicationContext as FocusDeckApp
    private val processId = app.processId
    private fun clockAnchor() = FocusClockAnchor(System.currentTimeMillis(), SystemClock.uptimeMillis(),
        Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT, -1), processId)

    private val now = MutableStateFlow(System.currentTimeMillis())
    private val screenTimeMinutes = MutableStateFlow(0L)
    private val yesterdayJournalMissing = MutableStateFlow(false)
    private val blockActionError = MutableStateFlow<String?>(null)
    private val blockActionBusy = MutableStateFlow(false)
    private val summaryStatus = MutableStateFlow<String?>(null)
    private val summaryBusy = MutableStateFlow(false)
    private val summaryPrefs by lazy { context.applicationContext.getSharedPreferences("home-summary-outbox", Context.MODE_PRIVATE) }
    private val summaryQueue by lazy {
        SummaryRetryQueue(summaryPrefs.getStringSet("pending", emptySet()).orEmpty()) { entries ->
            summaryPrefs.edit().putStringSet("pending", entries).commit()
        }
    }
    private data class SummaryTrigger(val date: LocalDate, val root: String?, val changed: Boolean)
    // One IO consumer owns all preference reads, commits, queue snapshots and acknowledgements.
    private val summaryWake = Channel<SummaryTrigger>(Channel.UNLIMITED)
    private var summaryObservation: Job? = null

    /** Куда пальцем отведён крупный план. null — держится за «сейчас». */
    private val scrubMinute = MutableStateFlow<Int?>(null)

    val uiState: StateFlow<HomeUiState> = combine(
        // Именно слепок сегодняшнего дня: в планировании может быть открыт другой,
        // а главный экран всегда про сегодня.
        dayStore.todayNote,
        dayStore.todayState,
        todayEpoch.flatMapLatest { focusBlockRepository.observeForDay(it) },
        todayEpoch.flatMapLatest { nutritionRepository.observeMealsForDay(it) },
        nutritionRepository.observeRecentWeights(1),
        now,
        screenTimeMinutes,
        yesterdayJournalMissing,
        scrubMinute,
        focusBlockRepository.observeActive(),
        dayStore.rootChanges,
        blockActionError,
        blockActionBusy,
        summaryStatus,
        summaryBusy,
    ) { values ->
        val note = values[0] as DailyNote?
        val vaultState = values[1] as VaultState

        @Suppress("UNCHECKED_CAST")
        val blocks = values[2] as List<FocusBlock>

        @Suppress("UNCHECKED_CAST")
        val meals = values[3] as List<Meal>

        @Suppress("UNCHECKED_CAST")
        val weights = values[4] as List<dev.stanislav.focusdeck.data.db.WeightEntry>

        val nowMillis = values[5] as Long
        val screenTime = values[6] as Long

        val journalMissing = values[7] as Boolean
        val scrub = values[8] as Int?

        // An unfinished timer remains visible across midnight; its binding keeps the original day.
        val active = values[9] as FocusBlock?
        val currentRoot = values[10]?.toString()
        val timeOfDay = timeOfDayFor(LocalTime.now())
        val minute = minuteOfDay(nowMillis)
        val nextUp = resolveNextUp(note?.tasks.orEmpty(), minute)
        val recoveryCue = resolveRecoveryCue(note, nextUp, minute, active != null)

        HomeUiState(
            tasks = note?.tasks.orEmpty(),
            vaultState = vaultState,
            activeBlock = active,
            nowMillis = nowMillis,
            screenTimeMinutesToday = screenTime,
            focusMinutesToday = blocks.sumOf { block ->
                (block.focusMillis(nowMillis) / 60_000).toInt()
            },
            caloriesToday = meals.sumOf { it.calories },
            latestWeightKg = weights.firstOrNull()?.kg,
            tapeSegments = buildTapeSegments(palette, note?.tasks.orEmpty(), blocks, nowMillis),
            interruptedBlocksToday = blocks.count { it.interrupted },
            nextUp = nextUp,
            activeTask = active?.let { resolveFocusTask(it, note, currentRoot) },
            blockActionError = values[11] as String?,
            blockActionBusy = values[12] as Boolean,
            summaryStatus = values[13] as String?,
            summaryBusy = values[14] as Boolean,
            upcoming = buildUpcoming(palette, note?.tasks.orEmpty(), minuteOfDay(nowMillis), scrub),
            recoveryCue = recoveryCue,
            tomorrowPrepared = RitualNotes.value(note, RitualNotes.EVENING_DONE) == "да",
            priority = RitualNotes.value(note, RitualNotes.PRIORITY)
                ?.takeIf { title -> note?.tasks.orEmpty().any { it.title == title } },
            timeOfDay = timeOfDay,
            cue = resolveCue(timeOfDay, journalMissing, meals, LocalTime.now()),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (trigger in summaryWake) processSummaryTrigger(trigger)
        }
        observeYesterdaySummary()
        viewModelScope.launch {
            // Защита живёт отдельно от работы: её обратный отсчёт и выход тоже
            // должны обновляться каждую секунду после остановки таймера.
            combine(focusBlockRepository.observeActive(), app.protectionStore.window) { active, _ -> active }
                .collectLatest { active ->
                while (true) {
                    rollToCurrentDay()
                    now.value = System.currentTimeMillis()
                    delay(if (active != null || app.protectionStore.current() != null) 1_000L else 30_000L)
                }
            }
        }
        viewModelScope.launch {
            while (true) {
                screenTimeMinutes.value = usageStatsRepository.screenTimeTodayMillis() / 60_000
                delay(60_000)
            }
        }
        refreshNote()
        viewModelScope.launch {
            // Дневник за вчера лежит в отдельном файле — читаем разово, вне общего потока.
            yesterdayJournalMissing.value =
                dayStore.journalOn(LocalDate.now().minusDays(1))?.isEmpty ?: false
        }
    }

    /** Ведёт крупный план по ленте суток. null возвращает окно к «сейчас». */
    fun scrubTo(minuteOfDay: Int?) {
        scrubMinute.value = minuteOfDay?.coerceIn(0, MINUTES_IN_DAY)
    }

    /** Перечитать заметку — при возврате в приложение файл мог обновиться синхронизацией. */
    fun refreshNote() {
        val summaryDate = LocalDate.now().minusDays(1)
        val summaryRoot = dayStore.rootIdentity
        viewModelScope.launch {
            try { dayStore.refreshToday() }
            finally { enqueueSummary(summaryDate, summaryRoot) }
        }
        observeYesterdaySummary()
    }

    fun refreshDate() {
        viewModelScope.launch { rollToCurrentDay() }
    }

    private suspend fun rollToCurrentDay() {
        val day = today
        if (todayEpoch.value == day) return
        val summaryRoot = dayStore.rootIdentity
        todayEpoch.value = day
        dayStore.refreshToday()
        enqueueSummary(LocalDate.ofEpochDay(day).minusDays(1), summaryRoot)
        yesterdayJournalMissing.value =
            dayStore.journalOn(LocalDate.now().minusDays(1))?.isEmpty ?: false
    }


    private fun minuteOfDay(nowMillis: Long): Int =
        java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
            .let { it.hour * 60 + it.minute }

    private fun timeOfDayFor(time: LocalTime): TimeOfDay = when {
        time.hour < 12 -> TimeOfDay.MORNING
        time.hour < 21 -> TimeOfDay.DAY
        else -> TimeOfDay.EVENING
    }

    private fun resolveCue(
        timeOfDay: TimeOfDay,
        journalMissing: Boolean,
        meals: List<Meal>,
        time: LocalTime,
    ): HomeCue? = when {
        timeOfDay == TimeOfDay.MORNING && journalMissing -> HomeCue.JournalYesterdayEmpty
        time.hour >= 14 && meals.none { it.type == MealType.LUNCH } ->
            HomeCue.MealMissing(MealType.LUNCH, "Обед не записан", "14:00")
        time.hour >= 20 && meals.none { it.type == MealType.DINNER } ->
            HomeCue.MealMissing(MealType.DINNER, "Ужин не записан", "20:00")
        time.hour >= 10 && meals.none { it.type == MealType.BREAKFAST } ->
            HomeCue.MealMissing(MealType.BREAKFAST, "Завтрак не записан", "10:00")
        else -> null
    }

    /**
     * Собирает окно ближайших часов.
     *
     * Цвет берётся тот же, каким задача залита на таймлайне, — включая сдвиг
     * по дорожке. Раньше полоса красилась акцентным цветом, тем самым, что на
     * таймлайне идёт на тонкие ручки и контуры: в виде заливки во всю высоту
     * он превращался в неон, и соседние задачи сливались в одно пятно.
     */
    private fun buildUpcoming(
        palette: FocusDeckColors,
        tasks: List<VaultTask>,
        nowMinute: Int,
        scrubMinute: Int?,
    ): UpcomingWindow {
        // Окно стоит вокруг точки отсчёта, а не начинается с неё: час позади
        // нужен не меньше, чем час впереди — по нему видно, где ты сейчас
        // внутри текущего дела и сколько его осталось.
        val half = UPCOMING_HOURS * 60 / 2
        val center = (scrubMinute ?: nowMinute).coerceIn(0, MINUTES_IN_DAY)
        // У краёв суток окно упирается, но не сжимается: иначе масштаб крупного
        // плана менялся бы на ходу, и одна и та же задача была бы то шире, то уже.
        var start = center - half
        var end = center + half
        if (start < 0) {
            end -= start
            start = 0
        }
        if (end > MINUTES_IN_DAY) {
            start -= end - MINUTES_IN_DAY
            end = MINUTES_IN_DAY
        }
        start = start.coerceAtLeast(0)
        val span = (end - start).coerceAtLeast(1)

        val scheduled = tasks
            .filter { it.isScheduled && !it.done }
            .map { task ->
                ScheduledItem.TaskBlock(
                    task = task,
                    startMinuteOfDay = task.startMinuteOfDay!!,
                    durationMinutes = task.durationMinutes ?: DEFAULT_TASK_MINUTES,
                )
            }

        val blocks = layoutLanes(scheduled).mapNotNull { placed ->
            val from = placed.item.startMinuteOfDay
            val to = from + placed.item.durationMinutes
            if (to <= start || from >= end) return@mapNotNull null

            val visibleFrom = maxOf(from, start)
            val visibleTo = minOf(to, end)
            UpcomingBlock(
                startFraction = (visibleFrom - start).toFloat() / span,
                widthFraction = (visibleTo - visibleFrom).toFloat() / span,
                title = placed.item.title,
                startMinuteOfDay = from,
                endMinuteOfDay = to,
                fill = palette.taskFill(
                    dayFraction(from) + laneToneShift(placed.lane),
                    emphasised = false,
                ),
                clippedStart = from < start,
            )
        }

        return UpcomingWindow(
            startMinuteOfDay = start,
            endMinuteOfDay = end,
            nowFraction = if (nowMinute in start..end) {
                (nowMinute - start).toFloat() / span
            } else {
                null
            },
            followsNow = scrubMinute == null,
            blocks = blocks,
            gaps = buildGaps(scheduled, start, end, span),
        )
    }

    /**
     * Дырки между задачами внутри окна.
     *
     * Считаются по исходным задачам, а не по дорожкам: две задачи на разных
     * дорожках занимают одно и то же время, и по дорожкам промежуток между
     * ними посчитался бы дважды. Слишком короткие пропускаем — подпись
     * «7 мин свободно» всё равно некуда вписать.
     */
    private fun buildGaps(
        scheduled: List<ScheduledItem>,
        start: Int,
        end: Int,
        span: Int,
    ): List<UpcomingGap> {
        val busy = scheduled
            .map { maxOf(it.startMinuteOfDay, start) to minOf(it.endMinuteOfDay, end) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
            .fold(mutableListOf<Pair<Int, Int>>()) { acc, range ->
                val last = acc.lastOrNull()
                if (last != null && range.first <= last.second) {
                    acc[acc.lastIndex] = last.first to maxOf(last.second, range.second)
                } else {
                    acc += range
                }
                acc
            }

        val gaps = mutableListOf<UpcomingGap>()
        var cursor = start
        (busy + (end to end)).forEach { (from, to) ->
            if (from - cursor >= MIN_GAP_MINUTES) {
                gaps += UpcomingGap(
                    startFraction = (cursor - start).toFloat() / span,
                    widthFraction = (from - cursor).toFloat() / span,
                    minutes = from - cursor,
                )
            }
            cursor = maxOf(cursor, to)
        }
        return gaps
    }

    /** Раскладывает реальные блоки и встречи на ленту суток (0..1 по ширине). */
    private fun buildTapeSegments(
        palette: FocusDeckColors,
        tasks: List<VaultTask>,
        blocks: List<FocusBlock>,
        nowMillis: Long,
    ): List<TapeSegment> {
        val dayStart = LocalDate.ofEpochDay(today).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayLength = 24 * 60 * 60 * 1000f
        val minutesInDay = 24 * 60f

        // План из заметки — то же, что видно во вкладке «Планирование».
        // Дорожки считаем и здесь: без них оттенок разошёлся бы с таймлайном,
        // ради совпадения с которым лента и красится.
        val plannedBlocks = tasks
            .filter { it.startMinuteOfDay != null && !it.done }
            .map { task ->
                ScheduledItem.TaskBlock(
                    task = task,
                    startMinuteOfDay = task.startMinuteOfDay!!,
                    durationMinutes = task.durationMinutes ?: DEFAULT_TASK_MINUTES,
                )
            }
        val plannedSegments = layoutLanes(plannedBlocks).map { placed ->
            val start = placed.item.startMinuteOfDay
            TapeSegment(
                startFraction = (start / minutesInDay).coerceIn(0f, 1f),
                widthFraction = (placed.item.durationMinutes / minutesInDay).coerceIn(0f, 1f),
                kind = SegmentKind.PLANNED,
                // Та же заливка, что у блока на таймлайне: акцентный цвет на полоске
                // читался неоном, потому что он задуман для контуров, а не для плашек.
                tint = palette.taskFill(
                    dayFraction(start) + laneToneShift(placed.lane),
                    emphasised = true,
                ),
            )
        }

        val blockSegments = blocks.flatMap { block ->
            val end = (block.endEpochMillis ?: nowMillis).coerceAtLeast(block.startEpochMillis)
            val breakStart = block.firstBreakStartedAt?.coerceIn(block.startEpochMillis, end)
            val breakEnd = block.firstBreakEndedAt?.coerceIn(breakStart ?: block.startEpochMillis, end)
            val intervals = if (breakStart == null) listOf(block.startEpochMillis to end)
                else listOfNotNull(block.startEpochMillis to breakStart, breakEnd?.let { it to end })
            intervals.map { (start, finish) ->
                TapeSegment(
                    startFraction = ((start - dayStart) / dayLength).coerceIn(0f, 1f),
                    widthFraction = ((finish - start) / dayLength).coerceIn(0f, 1f),
                    kind = if (block.interrupted) SegmentKind.INTERRUPTED else SegmentKind.FOCUS,
                )
            }
        }
        return (plannedSegments + blockSegments).filter { it.widthFraction > 0f }
    }

    fun toggleTask(task: VaultTask) {
        viewModelScope.launch { dayStore.toggleTask(task, !task.done) }
    }

    private suspend fun startWithProtection(block: FocusBlock, protect: Boolean) {
        var createdId: String? = null
        try {
            if (protect && app.protectionStore.current() == null) {
                val selected = applicationContext.getSharedPreferences("launcher-restrictions", Context.MODE_PRIVATE)
                    .getStringSet(block.category, emptySet()).orEmpty().filterNot { safetyExempt(applicationContext, it) }.toSet()
                check(selected.isNotEmpty()) { "Приложения для защиты не выбраны" }
                check(app.protectionStore.begin(checkNotNull(block.category), selected, block.sessionId, block.taskStableId)) { "Не удалось сохранить окно защиты" }
                createdId = app.protectionStore.current()?.id
                check(createdId != null)
            }
            focusBlockRepository.start(block)
            createdId?.let { app.recordAudit("protection_started", block, windowId = it) }
        } catch (failure: Exception) {
            val verification = withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { focusBlockRepository.observeActive().first() }
            }
            if (verification.isSuccess && verification.getOrNull()?.sessionId != block.sessionId)
                createdId?.let { app.protectionStore.rollbackStart(it) }
            throw failure
        }
    }

    fun startBlock(title: String, plannedMinutes: Int, category: String, protect: Boolean = false) {
        require(category in setOf("work", "study"))
        val day = LocalDate.now().toEpochDay()
        launchBlockAction("Не удалось начать блок. Попробуй ещё раз.") {
            if (focusBlockRepository.observeActive().first() != null) return@launchBlockAction
            val anchor = clockAnchor()
            startWithProtection(
                FocusBlock(
                    title = title,
                    plannedMinutes = plannedMinutes,
                    startEpochMillis = anchor.wallMillis,
                    startUptimeMillis = anchor.uptimeMillis, startBootCount = anchor.bootCount, startProcessId = anchor.processId,
                    dayEpochDay = day,
                    category = category,
                    sessionId = UUID.randomUUID().toString(),
                ), protect,
            )
        }
    }

    fun startTaskBlock(task: VaultTask, category: String, protect: Boolean = false) {
        require(category in setOf("work", "study"))
        val root = dayStore.rootIdentity
        // Use the displayed note's day, not a new "today" after a midnight transition.
        val day = dayStore.todayNote.value?.takeIf { note -> note.tasks.any { it === task } }?.date
        val anchor = clockAnchor()
        launchBlockAction("Не удалось начать задачу. Проверь выбранную папку, обнови план и повтори.") {
            check(root != null && day != null && dayStore.rootIdentity == root)
            if (focusBlockRepository.observeActive().first() != null) return@launchBlockAction
            val requested = FocusBlock(
                title = task.title,
                plannedMinutes = task.durationMinutes ?: DEFAULT_TASK_MINUTES,
                startEpochMillis = anchor.wallMillis,
                startUptimeMillis = anchor.uptimeMillis, startBootCount = anchor.bootCount, startProcessId = anchor.processId,
                dayEpochDay = day.toEpochDay(),
                taskStableId = task.stableId,
                taskVaultRoot = root,
                taskStartMinuteOfDay = task.startMinuteOfDay,
                category = category,
                sessionId = UUID.randomUUID().toString(),
            )
            val fresh = dayStore.noteOn(day)
            check(dayStore.rootIdentity == root)
            val matched = checkNotNull(resolveFocusTask(requested, fresh, root))
            check(!matched.done)
            check(dayStore.rootIdentity == root)
            val actual = clockAnchor()
            startWithProtection(requested.copy(
                startEpochMillis = actual.wallMillis, startUptimeMillis = actual.uptimeMillis,
                startBootCount = actual.bootCount, startProcessId = actual.processId,
                title = matched.title,
                plannedMinutes = matched.durationMinutes ?: DEFAULT_TASK_MINUTES,
                taskStableId = matched.stableId,
                taskStartMinuteOfDay = matched.startMinuteOfDay,
            ), protect)
        }
    }

    /** Serialize taps before the first suspension, including completion/interrupt/finish races. */
    private fun launchBlockAction(message: String, action: suspend () -> Unit) {
        if (blockActionBusy.value) return
        blockActionBusy.value = true
        blockActionError.value = null
        viewModelScope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                blockActionError.value = message
            } finally {
                blockActionBusy.value = false
            }
        }
    }

    /** Observe only yesterday. Previously failed dates are retried from the outbox, never discovered by scanning. */
    private fun observeYesterdaySummary() {
        if (summaryObservation?.isActive == true) return
        summaryObservation = viewModelScope.launch {
            try {
                combine(todayEpoch, dayStore.rootChanges) { epoch, root ->
                    LocalDate.ofEpochDay(epoch).minusDays(1) to root?.toString()
                }.flatMapLatest { (date, root) ->
                    if (root == null) kotlinx.coroutines.flow.flowOf(Triple(date, root, false))
                    else {
                        var first = true
                        val day = date.toEpochDay()
                        combine(focusBlockRepository.observeForDay(day),
                            nutritionRepository.observeDailyTotals(day, day), nutritionRepository.calorieTarget
                        ) { blocks, totals, target -> Triple(blocks, totals, target) }
                            .distinctUntilChanged()
                            .map {
                                val changed = !first
                                first = false
                                Triple(date, root, changed)
                            }
                    }
                }.collect { (date, root, changed) ->
                    enqueueSummary(date, root, changed)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                summaryStatus.value = "Не удалось обновить итоги. Нажми «Повторить»."
            }
        }
    }

    /** Capture date/root before any coroutine or I/O. Room invalidations and manual refresh share this queue. */
    fun writeYesterdaySummary() {
        enqueueSummary(LocalDate.now().minusDays(1), dayStore.rootIdentity)
    }

    private fun enqueueSummary(date: LocalDate, root: String?, changed: Boolean = false) {
        summaryWake.trySend(SummaryTrigger(date, root, changed))
    }

    private suspend fun processSummaryTrigger(trigger: SummaryTrigger) {
        summaryBusy.value = true
        try {
            val queue = summaryQueue
            val root = trigger.root
            if (root != null && root == dayStore.rootIdentity && trigger.date < LocalDate.now()) {
                try {
                    val existing = queue.forRoot(root, LocalDate.now()).any { it.date == trigger.date }
                    if (existing) {
                        queue.enqueue(trigger.date, root, trigger.changed)
                    } else {
                        // Missing notes are normal. Check before creating durable work; never create a note.
                        val prepared = readSummary(trigger.date, root)
                        if (prepared != null && summaryNeedsWrite(prepared.first, prepared.second)) {
                            queue.enqueue(trigger.date, root, trigger.changed)
                        }
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    // A read failure is not a missing note: retain this date across midnight/restart.
                    // Cancellation and root drift must not create work for a different vault.
                    if (dayStore.rootIdentity == root) {
                        queue.enqueue(trigger.date, root, trigger.changed)
                    }
                }
            }
            flushSummaryQueue()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            summaryStatus.value = "Не удалось сохранить очередь итогов. Нажми «Повторить»."
        } finally {
            summaryBusy.value = false
        }
    }

    fun retrySummaryWrites() {
        observeYesterdaySummary()
        writeYesterdaySummary()
    }

    private suspend fun flushSummaryQueue() {
        val root = dayStore.rootIdentity
        val today = LocalDate.now()
        try {
            val queue = summaryQueue
            if (root != null) {
                for (request in queue.forRoot(root, today)) {
                    if (dayStore.rootIdentity != root) break
                    try {
                        val written = writeQueuedSummary(request)
                        queue.acknowledge(request, written)
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        // Keep exactly this root/date pending, including acknowledgement-storage failures.
                    }
                }
            }
            summaryStatus.value = when {
                queue.pendingCount == 0 -> null
                root == null || queue.forRoot(root, today).isEmpty() ->
                    "Есть несохранённые итоги для другой папки или даты. Вернись к исходной папке и повтори."
                else -> "Итоги ещё не подтверждены. Проверь существующую заметку и нажми «Повторить»."
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            summaryStatus.value = "Не удалось обновить итоги. Нажми «Повторить»."
        }
    }

    private suspend fun writeQueuedSummary(request: SummaryRetryQueue.Request): Boolean {
        val root = request.root
        val date = request.date
        val prepared = readSummary(date, root) ?: return false
        check(dayStore.rootIdentity == root)
        val written = dayStore.writeSummaryOn(date, prepared.second, expectedRoot = root)
        check(dayStore.rootIdentity == root)
        return written // Only a successful write can acknowledge an existing durable request.
    }

    private suspend fun readSummary(date: LocalDate, root: String): Pair<DailyNote, DailySummary>? {
        fun checkRoot() = check(dayStore.rootIdentity == root) { "Папка итогов изменилась" }
        checkRoot()
        val note = dayStore.noteOnChecked(date)
        checkRoot()
        if (note == null) return null // Never create a daily note for a background summary.
        val day = date.toEpochDay()
        val blocks = focusBlockRepository.observeForDay(day).first()
        checkRoot()
        val calories = nutritionRepository.observeDailyTotals(day, day).first().sumOf { it.calories }
        checkRoot()
        val summary = DailySummary(
            screenMinutes = usageStatsRepository.screenTimeMillisOn(date) / 60_000,
            focusMinutes = blocks.sumOf { block ->
                (block.focusMillis(block.endEpochMillis ?: block.startEpochMillis) / 60_000).toInt()
            },
            tasksDone = note.tasks.count { it.done }, tasksTotal = note.tasks.size,
            interruptedBlocks = blocks.count { it.interrupted }, calories = calories,
            calorieTarget = nutritionRepository.calorieTarget.value,
        )
        checkRoot()
        return note to summary
    }

    /**
     * Сначала сохраняет отметку задачи в исходном дне и папке, затем закрывает блок.
     *
     * Отдельным действием, а не вместо «закончить»: блок можно закрыть и на
     * середине задачи, и тогда галочка была бы враньём. Раньше её в любом
     * случае приходилось ставить вручную в списке ниже.
     */
    fun completeActiveBlock() {
        val block = uiState.value.activeBlock ?: return
        val root = block.taskVaultRoot
        val day = LocalDate.ofEpochDay(block.dayEpochDay)
        launchBlockAction("Не удалось завершить задачу. Проверь исходную папку и повтори; таймер остаётся активным.") {
            check(root != null && dayStore.rootIdentity == root)
            check(focusBlockRepository.observeActive().first()?.id == block.id)
            finishFocusBlockAfterTaskWrite(
                writeTask = {
                    dayStore.editNoteOn(day, expectedRoot = root) { fresh ->
                        val task = checkNotNull(resolveFocusTask(block, fresh, dayStore.rootIdentity))
                        if (task.done) fresh.lines.joinToString("\n")
                        else DailyNoteParser.toggleTask(fresh, task.lineIndex, true)
                    }
                },
                finishBlock = {
                    check(dayStore.rootIdentity == root)
                    val current = focusBlockRepository.observeActive().first()
                    check(current?.id == block.id)
                    focusBlockRepository.finish(current, interrupted = false, anchor = clockAnchor())
                },
            )
        }
    }

    fun pauseActiveBlock(openSelectedApps: Boolean = false) {
        val block = uiState.value.activeBlock ?: return
        launchBlockAction("Не удалось начать перерыв. Попробуй ещё раз.") {
            val current = focusBlockRepository.observeActive().first()
            check(current?.id == block.id && current.breakStartedAt == null && current.breakCount == 0)
            val anchor = clockAnchor()
            focusBlockRepository.update(current.copy(breakStartedAt = anchor.wallMillis, firstBreakStartedAt = anchor.wallMillis,
                breakStartedElapsedMillis = SystemClock.elapsedRealtime(), breakBootCount = anchor.bootCount,
                pauseUptimeMillis = anchor.uptimeMillis, pauseProcessId = anchor.processId, breakCount = 1))
            val window = app.protectionStore.current()
            if (window != null && (!window.reliefUsed || (!openSelectedApps && window.policyVersion == 2))) {
                val saved = if (openSelectedApps) app.protectionStore.beginRelief() else app.protectionStore.beginRest()
                if (!saved) blockActionError.value = "Таймер на паузе, но изменение окна защиты не сохранилось. Проверь его статус."
                else app.recordAudit(if (openSelectedApps) "bounded_relief_started" else "rest_without_feeds",
                    if (window.policyVersion == 2) null else current, windowId = window.id, resolveActive = false)
            }
        }
    }

    fun resumeActiveBlock() {
        val block = uiState.value.activeBlock ?: return
        launchBlockAction("Не удалось вернуться к блоку. Попробуй ещё раз.") {
            val current = focusBlockRepository.observeActive().first()
            check(current?.id == block.id && current.breakStartedAt != null)
            val window = app.protectionStore.current()
            if (window != null) {
                if (!app.protectionStore.endRelief()) error("Не удалось вернуть ограничения; таймер остаётся на паузе")
                if (window.resting) app.recordAudit("rest_returned", null, windowId = window.id, resolveActive = false)
            }
            val anchor = clockAnchor()
            focusBlockRepository.update(current.afterReturn(anchor.wallMillis).copy(resumeUptimeMillis = anchor.uptimeMillis,
                resumeBootCount = anchor.bootCount, resumeProcessId = anchor.processId))
        }
    }

    fun finishActiveBlock(interrupted: Boolean = false) {
        val block = uiState.value.activeBlock ?: return
        launchBlockAction("Не удалось остановить таймер. Попробуй ещё раз.") {
            val current = focusBlockRepository.observeActive().first()
            check(current?.id == block.id)
            focusBlockRepository.finish(current, interrupted, clockAnchor())
        }
    }

    class Factory(
        private val palette: FocusDeckColors,
        private val dayStore: VaultDayStore,
        private val focusBlockRepository: FocusBlockRepository,
        private val usageStatsRepository: UsageStatsRepository,
        private val nutritionRepository: NutritionRepository,
        private val context: Context,
        ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(
            palette,
            dayStore,
            focusBlockRepository,
            usageStatsRepository,
            nutritionRepository,
            context.applicationContext,
        ) as T
    }
}

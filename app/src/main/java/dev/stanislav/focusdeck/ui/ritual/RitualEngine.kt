package dev.stanislav.focusdeck.ui.ritual

import dev.stanislav.focusdeck.data.db.Meal
import java.security.MessageDigest
import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.VaultTask
import dev.stanislav.focusdeck.ui.planning.DEFAULT_TASK_MINUTES
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

enum class RitualPhase { MORNING, EVENING }

const val EVENING_START_HOUR = 23
const val MORNING_START_HOUR = 6

fun shouldOfferEveningPreparation(
    now: LocalTime,
    tomorrowPrepared: Boolean,
    focusActive: Boolean,
): Boolean = now.hour == EVENING_START_HOUR - 1 && now.minute >= 30 &&
    !tomorrowPrepared && !focusActive

data class RitualWindow(
    val phase: RitualPhase,
    val journalDate: LocalDate,
    val planDate: LocalDate,
)

/** After midnight, the unfinished evening ritual still belongs to yesterday. */
fun ritualWindow(now: LocalDateTime): RitualWindow = when {
    now.hour >= EVENING_START_HOUR -> RitualWindow(RitualPhase.EVENING, now.toLocalDate(), now.toLocalDate().plusDays(1))
    now.hour < MORNING_START_HOUR -> RitualWindow(RitualPhase.EVENING, now.toLocalDate().minusDays(1), now.toLocalDate())
    else -> RitualWindow(RitualPhase.MORNING, now.toLocalDate(), now.toLocalDate())
}

/** Manual opening targets tomorrow only during the day. After 23:00 and until
 * 06:00 it opens the actual evening ritual, including the same day across midnight. */
fun shownRitualWindow(now: LocalDateTime, manuallyOpened: Boolean): RitualWindow {
    val actual = ritualWindow(now)
    return if (manuallyOpened && actual.phase == RitualPhase.MORNING) {
        RitualWindow(RitualPhase.EVENING, now.toLocalDate(), now.toLocalDate().plusDays(1))
    } else actual
}

/** Manual preparation can be opened early without turning into a forced gate. */
fun shouldRequireRitual(
    shown: RitualWindow,
    actual: RitualWindow,
    activationDay: LocalDate,
    completed: Boolean,
    emergencyBypass: Boolean,
    skipMorningOn: LocalDate? = null,
): Boolean = shown == actual && shown.journalDate >= activationDay &&
    !(shown.phase == RitualPhase.MORNING && shown.journalDate == skipMorningOn) &&
    !completed && !emergencyBypass

/** Let a focus session started before 23:00 finish, but never hide the ritual
 * indefinitely behind an abandoned active timer. Grace ends at the earlier of
 * the planned block end or 23:15. */
fun eveningFocusGrace(
    window: RitualWindow,
    blockStartMillis: Long,
    plannedMinutes: Int,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    return eveningFocusGraceDeadline(window, blockStartMillis, plannedMinutes, nowMillis, zone) != null
}

fun eveningFocusGraceDeadline(
    window: RitualWindow,
    blockStartMillis: Long,
    plannedMinutes: Int,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (window.phase != RitualPhase.EVENING || plannedMinutes <= 0) return null
    val gateStart = window.journalDate.atTime(EVENING_START_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
    val plannedEnd = blockStartMillis + plannedMinutes * 60_000L
    val deadline = minOf(plannedEnd, gateStart + 15 * 60_000L)
    return deadline.takeIf { blockStartMillis < gateStart && nowMillis >= gateStart && nowMillis < it }
}

// Fixed, visible thresholds do not change with font scale or keyboard height.
const val JOURNAL_MIN_WORDS = 100
const val JOURNAL_MIN_CHARACTERS = 600
const val PLAN_START_MINUTE = 8 * 60
const val PLAN_END_MINUTE = 23 * 60
const val PLAN_REQUIRED_MINUTES = (PLAN_END_MINUTE - PLAN_START_MINUTE) * 60 / 100

private val wordPattern = Regex("[\\p{L}]{2,}")

data class JournalReadiness(val words: Int, val characters: Int, val distinctWords: Int) {
    val isReady get() = words >= JOURNAL_MIN_WORDS && characters >= JOURNAL_MIN_CHARACTERS && distinctWords >= 20
    val fraction get() = minOf(words.toFloat() / JOURNAL_MIN_WORDS,
        characters.toFloat() / JOURNAL_MIN_CHARACTERS, distinctWords / 20f).coerceIn(0f, 1f)
}

fun journalReadiness(text: String): JournalReadiness {
    val words = wordPattern.findAll(text).map { it.value.lowercase(java.util.Locale.ROOT) }.toList()
    return JournalReadiness(words.size, text.count { it.isLetterOrDigit() }, words.distinct().size)
}

fun meaningfulTitle(text: String): Boolean =
    text.count { it.isLetter() } >= 3 && text.filter { it.isLetter() }.lowercase().toSet().size >= 2

data class PlanReadiness(
    val scheduledCount: Int,
    // Union of valid named intervals, clipped to the displayed waking window.
    val plannedMinutes: Int,
    val hasOverlap: Boolean,
    val hasInvalidTime: Boolean,
    val priority: String?,
    val restDay: Boolean,
) {
    val coveragePercent get() = plannedMinutes * 100 / (PLAN_END_MINUTE - PLAN_START_MINUTE)
    val remainingMinutes get() = (PLAN_REQUIRED_MINUTES - plannedMinutes).coerceAtLeast(0)
    val isReady: Boolean get() = plannedMinutes >= PLAN_REQUIRED_MINUTES && priority != null && !hasInvalidTime
}

fun validTimedTask(task: VaultTask): Boolean {
    val start = task.startMinuteOfDay ?: return false
    val duration = task.durationMinutes ?: DEFAULT_TASK_MINUTES
    return start in 0..1439 && duration in 1..(1440 - start)
}

fun planReadiness(note: DailyNote?): PlanReadiness {
    if (note == null) return PlanReadiness(0, 0, false, false, null, false)
    val scheduled = note.tasks.filter { it.startMinuteOfDay != null }.sortedBy { it.startMinuteOfDay }
    val valid = scheduled.filter { validTimedTask(it) && meaningfulTitle(it.title) }
    val priority = RitualNotes.value(note, RitualNotes.PRIORITY)
        ?.takeIf { title -> valid.any { it.title == title } }
    var end = PLAN_START_MINUTE
    var minutes = 0
    var overlap = false
    for (task in valid) {
        val start = maxOf(PLAN_START_MINUTE, task.startMinuteOfDay!!)
        val stop = minOf(PLAN_END_MINUTE, task.startMinuteOfDay + (task.durationMinutes ?: DEFAULT_TASK_MINUTES))
        if (stop <= start) continue
        if (start < end) overlap = true
        minutes += (stop - maxOf(start, end)).coerceAtLeast(0)
        end = maxOf(end, stop)
    }
    return PlanReadiness(valid.size, minutes, overlap,
        scheduled.any { !validTimedTask(it) || !meaningfulTitle(it.title) }, priority,
        RitualNotes.value(note, RitualNotes.REST_DAY) == "да")
}

/** Confirmation is bound to exact meal contents; editing food invalidates it. */
fun foodFingerprint(meals: List<Meal>): String {
    val canonical = meals.sortedBy { it.id }.joinToString("\n") {
        "${it.id}|${it.dayEpochDay}|${it.type}|${it.name.length}:${it.name}|${it.calories}"
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun foodReady(note: DailyNote?, meals: List<Meal>): Boolean = meals.isNotEmpty() &&
    meals.all { it.dayEpochDay == note?.date?.toEpochDay() && meaningfulTitle(it.name) && it.calories >= 0 } &&
    RitualNotes.value(note, RitualNotes.FOOD_CHECKED) == foodFingerprint(meals)

data class RitualProgress(
    val window: RitualWindow,
    val journalReady: Boolean,
    val plan: PlanReadiness,
    val completed: Boolean,
    val nutritionReady: Boolean = false,
) {
    val canComplete: Boolean get() = journalReady && plan.isReady &&
        (window.phase == RitualPhase.MORNING || nutritionReady)
}

fun ritualProgress(window: RitualWindow, journalNote: DailyNote?, planNote: DailyNote?,
    meals: List<Meal> = emptyList()): RitualProgress {
    val journalReady = journalReadiness(when (window.phase) {
        RitualPhase.MORNING -> journalNote?.journal?.morning.orEmpty()
        RitualPhase.EVENING -> journalNote?.journal?.evening.orEmpty()
    }).isReady
    val marker = if (window.phase == RitualPhase.MORNING) RitualNotes.MORNING_DONE else RitualNotes.EVENING_DONE
    val plan = planReadiness(planNote)
    val nutrition = window.phase == RitualPhase.MORNING || foodReady(journalNote, meals)
    return RitualProgress(window, journalReady, plan,
        RitualNotes.value(journalNote, marker) == "да" && journalReady && plan.isReady && nutrition,
        nutrition)
}

/** Review commitments, not the entire unscheduled backlog. */
data class EveningReview(
    val planned: Int,
    val completed: Int,
    val unplannedCompleted: Int,
    val unfinished: List<VaultTask>,
)

fun eveningReview(note: DailyNote?): EveningReview {
    val scheduled = note?.tasks.orEmpty().filter { it.isScheduled }
    return EveningReview(
        planned = scheduled.size,
        completed = scheduled.count { it.done },
        unplannedCompleted = note?.tasks.orEmpty().count { it.done && !it.isScheduled },
        unfinished = scheduled.filter { !it.done },
    )
}

/** Visible Obsidian section, kept outside the existing task, journal and plugin blocks. */
object RitualNotes {
    const val PRIORITY = "Главное"
    const val IF_THEN = "Если помешает"
    const val REST_DAY = "День отдыха"
    const val MORNING_DONE = "Утро подтверждено"
    const val EVENING_DONE = "Вечер завершён"
    const val FOOD_CHECKED = "Питание проверено"
    const val FOOD_LIST = "Еда за день"
    private const val HEADING = "## FocusDeck"

    fun value(note: DailyNote?, key: String): String? {
        if (note == null) return null
        val start = note.lines.indexOfFirst { it.trim() == HEADING }
        if (start < 0) return null
        val end = ((start + 1) until note.lines.size)
            .firstOrNull { note.lines[it].trimStart().startsWith("#") } ?: note.lines.size
        return note.lines.subList(start + 1, end)
            .firstOrNull { it.trimStart().startsWith("- $key:") }
            ?.substringAfter("- $key:")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun put(note: DailyNote, key: String, value: String): String {
        require(key in setOf(PRIORITY, IF_THEN, REST_DAY, MORNING_DONE, EVENING_DONE, FOOD_CHECKED, FOOD_LIST))
        val lines = note.lines.toMutableList()
        val cleaned = value.replace('\n', ' ').replace('\r', ' ').trim()
        val start = lines.indexOfFirst { it.trim() == HEADING }
        if (start < 0) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
            lines.add(HEADING)
            lines.add("- $key: $cleaned")
            lines.add("")
        } else {
            val end = ((start + 1) until lines.size)
                .firstOrNull { lines[it].trimStart().startsWith("#") } ?: lines.size
            val existing = ((start + 1) until end)
                .firstOrNull { lines[it].trimStart().startsWith("- $key:") }
            if (existing != null) lines[existing] = "- $key: $cleaned"
            else lines.add(end, "- $key: $cleaned")
        }
        return lines.joinToString("\n")
    }
}

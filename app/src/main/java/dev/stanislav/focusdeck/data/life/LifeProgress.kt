package dev.stanislav.focusdeck.data.life

import dev.stanislav.focusdeck.data.vault.DailyNote
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** One `- [ ] …` line of the «Этапы» section. [line] indexes the field text, not the file. */
data class Milestone(val line: Int, val title: String, val done: Boolean, val doneOn: LocalDate? = null)

/**
 * Milestones are plain Markdown checkboxes, so Obsidian can tick them too.
 * Only checkbox lines are touched; any other text in the section stays as written.
 */
object Milestones {
    private val checkbox = Regex("""^(\s*[-*+]\s+\[)([ xX])(]\s+)(.*)$""")
    /** The Obsidian Tasks plugin convention, so the done date is readable there too. */
    private val doneDate = Regex("""\s*✅\s*(\d{4}-\d{2}-\d{2})\s*$""")

    fun parse(text: String): List<Milestone> = text.lines().mapIndexedNotNull { index, line ->
        checkbox.matchEntire(line)?.let { match ->
            val body = match.groupValues[4]
            val date = doneDate.find(body)?.groupValues?.get(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            body.replace(doneDate, "").trim().takeIf { it.any(Char::isLetterOrDigit) }
                ?.let { Milestone(index, it, match.groupValues[2] != " ", date) }
        }
    }

    /** Ticking stamps the day it happened; unticking removes the stamp. The pace forecast reads these dates. */
    fun toggle(text: String, line: Int, done: Boolean, today: LocalDate? = null): String {
        val lines = text.lines().toMutableList()
        val match = lines.getOrNull(line)?.let(checkbox::matchEntire) ?: return text
        val body = match.groupValues[4].replace(doneDate, "")
        lines[line] = match.groupValues[1] + (if (done) "x" else " ") + match.groupValues[3] + body +
            (if (done && today != null) " ✅ $today" else "")
        return lines.joinToString("\n")
    }

    fun add(text: String, title: String): String {
        val clean = title.replace('\n', ' ').trim()
        require(clean.any(Char::isLetterOrDigit)) { "Назови этап" }
        val item = "- [ ] $clean"
        return if (text.isBlank()) item else text.trimEnd() + "\n" + item
    }

    fun remove(text: String, line: Int): String {
        val lines = text.lines().toMutableList()
        if (lines.getOrNull(line)?.let(checkbox::matches) != true) return text
        lines.removeAt(line)
        return lines.joinToString("\n").trim()
    }

    /** Wizard input: one milestone per line, bullets optional. */
    fun fromLines(input: String): String = input.lines()
        .map { it.trim().removePrefix("-").removePrefix("*").trim().removePrefix("[ ]").removePrefix("[x]").trim() }
        .filter { it.any(Char::isLetterOrDigit) }
        .joinToString("\n") { "- [ ] $it" }
}

/** Measured progress. Never invented: an entry without milestones or projects has none. */
data class LifeProgress(val done: Int, val total: Int, val fraction: Float, val unit: ProgressUnit) {
    enum class ProgressUnit { MILESTONES, PROJECTS, MIXED, FINISHED, METRIC }

    /**
     * Small-area framing (Koo & Fishbach 2012): early on, what is already done motivates more;
     * past the middle, what remains does. Returns null for finished or metric progress.
     */
    fun framing(): String? {
        if (unit == ProgressUnit.FINISHED || unit == ProgressUnit.METRIC || unit == ProgressUnit.MIXED || total <= 0) return null
        val noun = when (unit) {
            ProgressUnit.PROJECTS -> "проект" to listOf("проект", "проекта", "проектов")
            else -> "этап" to listOf("этап", "этапа", "этапов")
        }.second
        return if (done * 2 < total) "пройдено $done из $total"
        else if (done == total) "всё пройдено"
        else "осталось ${total - done} ${plural(total - done, noun)}"
    }
}

internal fun plural(count: Int, forms: List<String>): String = when {
    count % 10 == 1 && count % 100 != 11 -> forms[0]
    count % 10 in 2..4 && count % 100 !in 12..14 -> forms[1]
    else -> forms[2]
}

/**
 * One number that shows movement (Harkin et al. 2016: recording progress helps most when it is
 * written down). Stored as readable lines in the note:
 * `Цель: 21 км`, optional `Старт: 5`, then dated `- 2026-09-29: 12`.
 */
data class Metric(val target: Double, val unit: String, val start: Double?, val points: List<Pair<LocalDate, Double>>) {
    val current: Double? get() = points.lastOrNull()?.second
    /** Where the scale begins: the stated start, or the first value when the goal is to go down. */
    val base: Double get() = start ?: points.firstOrNull()?.second?.takeIf { it > target } ?: 0.0
    val fraction: Float? get() {
        val value = current ?: return 0f
        if (target == base) return null
        return ((value - base) / (target - base)).toFloat().coerceIn(0f, 1f)
    }
}

object Metrics {
    private val targetLine = Regex("""^\s*Цель:\s*(-?[\d\s]+(?:[.,]\d+)?)\s*(.*)$""")
    private val startLine = Regex("""^\s*Старт:\s*(-?[\d\s]+(?:[.,]\d+)?)""")
    private val pointLine = Regex("""^\s*[-*]\s*(\d{4}-\d{2}-\d{2})\s*:\s*(-?[\d\s]+(?:[.,]\d+)?)""")

    fun number(text: String): Double? = text.replace(" ", "").replace(',', '.').toDoubleOrNull()

    fun parse(text: String): Metric? {
        var target: Double? = null
        var unit = ""
        var start: Double? = null
        val points = mutableListOf<Pair<LocalDate, Double>>()
        text.lines().forEach { line ->
            targetLine.matchEntire(line)?.let { target = number(it.groupValues[1]); unit = it.groupValues[2].trim() }
            startLine.find(line)?.let { start = number(it.groupValues[1]) }
            pointLine.find(line)?.let { match ->
                val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
                val value = number(match.groupValues[2])
                if (date != null && value != null) points += date to value
            }
        }
        return target?.let { Metric(it, unit, start, points.sortedBy { p -> p.first }) }
    }

    fun create(target: Double, unit: String, start: Double?): String = buildString {
        append("Цель: ${format(target)}${if (unit.isNotBlank()) " ${unit.trim()}" else ""}")
        start?.let { append("\nСтарт: ${format(it)}") }
    }

    /** One value per day: a second entry on the same date replaces the first. */
    fun record(text: String, date: LocalDate, value: Double): String {
        val kept = text.lines().filterNot { line -> pointLine.find(line)?.groupValues?.get(1) == date.toString() }
        return (kept.joinToString("\n").trimEnd() + "\n- $date: ${format(value)}").trim()
    }

    fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, value)
}

/**
 * The outside view against the planning fallacy (Buehler, Griffin & Ross 1994): the finish date
 * implied by the pace actually achieved, not by hope. Shown only with enough dated evidence.
 */
data class PaceForecast(val perWeek: Double, val eta: LocalDate, val due: LocalDate?) {
    val late: Boolean get() = due != null && eta.isAfter(due)
}

object LifePace {
    fun milestones(entry: LifeEntry, today: LocalDate = LocalDate.now()): PaceForecast? {
        val all = Milestones.parse(entry.text(LifeField.MILESTONES))
        val dated = all.mapNotNull { if (it.done) it.doneOn else null }
        val remaining = all.count { !it.done }
        if (dated.size < 2 || remaining == 0 || entry.status != LifeStatus.ACTIVE) return null
        val from = minOf(entry.created.takeIf { it.year > 1970 } ?: dated.min(), dated.min())
        val weeks = maxOf(1.0, java.time.temporal.ChronoUnit.DAYS.between(from, today) / 7.0)
        val perWeek = dated.size / weeks
        return PaceForecast(perWeek, today.plusDays(kotlin.math.ceil(remaining / perWeek * 7).toLong()), entry.dueDate)
    }

    fun metric(entry: LifeEntry, today: LocalDate = LocalDate.now()): PaceForecast? {
        val metric = Metrics.parse(entry.text(LifeField.METRIC)) ?: return null
        val first = metric.points.firstOrNull() ?: return null
        val last = metric.points.last()
        val days = java.time.temporal.ChronoUnit.DAYS.between(first.first, last.first)
        if (metric.points.size < 2 || days < 7 || entry.status != LifeStatus.ACTIVE) return null
        val direction = if (metric.target >= metric.base) 1 else -1
        val perDay = (last.second - first.second) / days * direction
        val left = (metric.target - last.second) * direction
        if (perDay <= 0 || left <= 0) return null
        return PaceForecast(perDay * 7, last.first.plusDays(kotlin.math.ceil(left / perDay).toLong()).coerceAtLeast(today), entry.dueDate)
    }
}

/**
 * Why a goal is held (Sheldon & Elliot 1999): goals pursued from interest or personal importance
 * are sustained better than those pursued from guilt or outside pressure. Stored as a readable line.
 */
enum class GoalMotive(val label: String, val autonomous: Boolean) {
    INTEREST("интересно", true), VALUE("важно мне", true), GUILT("чувствую, что должен", false), PRESSURE("ждут другие", false);

    companion object {
        private val line = Regex("""(?m)^Мотив:\s*(.+)$""")
        fun of(why: String): GoalMotive? = line.find(why)?.groupValues?.get(1)?.trim()?.let { value -> entries.firstOrNull { it.label == value } }
        fun with(why: String, motive: GoalMotive?): String {
            val body = why.replace(line, "").trim()
            return listOfNotNull(motive?.let { "Мотив: ${it.label}" }, body.takeIf { it.isNotEmpty() }).joinToString("\n")
        }
        fun strip(why: String): String = why.replace(line, "").trim()
    }
}

/**
 * Decision calibration (Mellers et al. 2014; Tetlock): a probability written before the outcome,
 * then a plain verdict afterwards. Over several decisions this shows whether confidence is earned.
 */
object Calibration {
    private val confidenceLine = Regex("""(?m)^Уверенность:\s*(\d{1,3})\s*%\s*$""")
    private val verdictLine = Regex("""(?m)^Ожидание сбылось:\s*(да|частично|нет)\s*$""")

    enum class Verdict(val label: String, val score: Double) { YES("да", 1.0), PARTLY("частично", 0.5), NO("нет", 0.0) }

    fun confidence(prediction: String): Int? = confidenceLine.find(prediction)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..100 }
    fun verdict(outcome: String): Verdict? = verdictLine.find(outcome)?.groupValues?.get(1)?.let { v -> Verdict.entries.first { it.label == v } }

    fun withConfidence(prediction: String, percent: Int?): String =
        listOfNotNull(prediction.replace(confidenceLine, "").trim().takeIf { it.isNotEmpty() }, percent?.let { "Уверенность: $it%" }).joinToString("\n")
    fun withVerdict(outcome: String, verdict: Verdict?): String =
        listOfNotNull(outcome.replace(verdictLine, "").trim().takeIf { it.isNotEmpty() }, verdict?.let { "Ожидание сбылось: ${it.label}" }).joinToString("\n")
    fun stripConfidence(prediction: String): String = prediction.replace(confidenceLine, "").trim()
    fun stripVerdict(outcome: String): String = outcome.replace(verdictLine, "").trim()

    /** Checked decisions with a stated confidence. Brier score: 0 is perfect, 0.25 is a coin flip. */
    data class Summary(val count: Int, val meanConfidence: Double, val hitRate: Double, val brier: Double)

    fun summary(decisions: List<LifeEntry>): Summary? {
        val pairs = decisions.filter { it.kind == LifeKind.DECISION }.mapNotNull { entry ->
            val p = confidence(entry.text(LifeField.PREDICTION)) ?: return@mapNotNull null
            val v = verdict(entry.text(LifeField.OUTCOME)) ?: return@mapNotNull null
            p / 100.0 to v.score
        }
        if (pairs.isEmpty()) return null
        return Summary(pairs.size, pairs.map { it.first }.average(), pairs.map { it.second }.average(),
            pairs.map { (p, o) -> (p - o) * (p - o) }.average())
    }
}

object LifeProgressRules {
    fun of(entry: LifeEntry, entries: List<LifeEntry>): LifeProgress? {
        if (entry.status == LifeStatus.DONE && entry.kind in listOf(LifeKind.PROJECT, LifeKind.GOAL)) {
            return LifeProgress(1, 1, 1f, LifeProgress.ProgressUnit.FINISHED)
        }
        val own = Milestones.parse(entry.text(LifeField.MILESTONES))
        return when (entry.kind) {
            LifeKind.PROJECT -> own.takeIf { it.isNotEmpty() }?.let { list ->
                val done = list.count { it.done }
                LifeProgress(done, list.size, done.toFloat() / list.size, LifeProgress.ProgressUnit.MILESTONES)
            }
            LifeKind.GOAL -> {
                val projects = entries.filter { it.kind == LifeKind.PROJECT && it.goalId == entry.id && !it.archived }
                val metric = Metrics.parse(entry.text(LifeField.METRIC))
                val parts = buildList {
                    metric?.fraction?.let { add(it) }
                    if (own.isNotEmpty()) add(own.count { it.done }.toFloat() / own.size)
                    projects.forEach { project -> of(project, entries)?.let { add(it.fraction) } }
                }
                if (parts.isEmpty()) return null
                val doneProjects = projects.count { it.status == LifeStatus.DONE }
                if (metric?.fraction != null && own.isEmpty() && projects.none { of(it, entries) != null }) {
                    return LifeProgress(0, 0, parts.single(), LifeProgress.ProgressUnit.METRIC)
                }
                val hasMetric = metric?.fraction != null
                when {
                    own.isEmpty() && !hasMetric -> LifeProgress(doneProjects, projects.size, parts.average().toFloat(), LifeProgress.ProgressUnit.PROJECTS)
                    projects.isEmpty() && !hasMetric -> LifeProgress(own.count { it.done }, own.size, parts.single(), LifeProgress.ProgressUnit.MILESTONES)
                    else -> LifeProgress(own.count { it.done } + doneProjects, own.size + projects.size,
                        parts.average().toFloat(), LifeProgress.ProgressUnit.MIXED)
                }
            }
            else -> null
        }
    }
}

/** A daily-note task linked to the life system. The checkbox in the daily note stays authoritative. */
data class LifeStep(
    val date: LocalDate,
    val title: String,
    val done: Boolean,
    val startMinute: Int?,
    val projectId: String?,
    val goalId: String?,
    val areaId: String?,
)

/** What actually happened in the daily notes of the window. Missing days are not failures. */
data class LifeActivity(
    val from: LocalDate,
    val to: LocalDate,
    val steps: List<LifeStep> = emptyList(),
    val readDays: Int = 0,
    val failedDays: Int = 0,
) {
    fun stepsFor(entry: LifeEntry): List<LifeStep> = steps.filter { step ->
        when (entry.kind) {
            LifeKind.PROJECT -> step.projectId == entry.id
            LifeKind.GOAL -> step.goalId == entry.id
            LifeKind.AREA -> step.areaId == entry.id
            LifeKind.DECISION -> entry.projectId != null && step.projectId == entry.projectId
            LifeKind.REVIEW -> false
        }
    }

    val linkedSteps: List<LifeStep> get() = steps.filter { it.projectId != null || it.goalId != null || it.areaId != null }

    /** Replace one day's steps, e.g. after a checkbox in today's note changed. */
    fun withDay(note: DailyNote): LifeActivity {
        if (note.date.isBefore(from) || note.date.isAfter(to)) return this
        return copy(steps = steps.filterNot { it.date == note.date } + stepsOf(note))
    }

    companion object {
        const val WINDOW_DAYS = 28L

        fun stepsOf(note: DailyNote): List<LifeStep> = note.tasks
            .filter { it.projectId != null || it.goalId != null || it.areaId != null }
            .map { LifeStep(note.date, it.title, it.done, it.startMinuteOfDay, it.projectId, it.goalId, it.areaId) }
    }
}

object LifeMomentum {
    fun doneByDay(steps: List<LifeStep>): Map<LocalDate, Int> =
        steps.filter { it.done }.groupingBy { it.date }.eachCount()

    /** Weeks (Mon–Sun) among the last [weeks] with at least one completed step. Forgiving by design. */
    fun activeWeeks(steps: List<LifeStep>, today: LocalDate, weeks: Int = 4): Int {
        val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0 until weeks).count { back ->
            val start = thisWeek.minusWeeks(back.toLong())
            steps.any { it.done && !it.date.isBefore(start) && it.date.isBefore(start.plusWeeks(1)) }
        }
    }

    fun doneBetween(steps: List<LifeStep>, from: LocalDate, to: LocalDate): Int =
        steps.count { it.done && !it.date.isBefore(from) && !it.date.isAfter(to) }

    fun lastDone(steps: List<LifeStep>): LocalDate? = steps.filter { it.done }.maxOfOrNull { it.date }
}

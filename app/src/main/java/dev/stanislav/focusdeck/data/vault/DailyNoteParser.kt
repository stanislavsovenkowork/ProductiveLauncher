package dev.stanislav.focusdeck.data.vault

import java.time.LocalDate

/**
 * Разбор и правка daily note в формате vault'а.
 *
 * Формат взят из реальных заметок, а не придуман:
 * ```
 * ---
 * day: 2026-08-25
 * tags:
 *   - daily
 *   - planning
 * ---
 *
 * ## Задачи
 *
 * - [ ] Сделать письмо безопасникам
 *
 * # Day planner
 *
 * ## Итоги дня
 *
 * > [!summary] Краткая сводка дня
 * > - Экранное время: **<1 мин**.
 *
 * <details class="daily-journal">
 * <summary>Дневник</summary>
 *
 * - Утро:
 * - День:
 * - Вечер:
 * - Выводы:
 *
 * </details>
 * ```
 *
 * Принцип правок: меняем только нужные строки в исходном тексте и сохраняем
 * остальные, включая блоки плагинов. SAF заменяет содержимое файла целиком;
 * одновременные правки на другом устройстве всё ещё могут вызвать конфликт.
 */
object DailyNoteParser {

    private val TASK_REGEX = Regex("""^\s*-\s\[( |x|X)]\s*(.*)$""")
    private val TIME_REGEX = Regex("""[⏳🕐]\s*(\d{1,2}):(\d{2})""")
    private val INLINE_TIME_REGEX = Regex("""\[time::\s*(\d{1,2}):(\d{2})]""")
    private val DURATION_REGEX = Regex("""\[dur::\s*(\d+)]""")
    private val PROJECT_REGEX = Regex("""\[project::\s*([A-Za-z0-9_-]+)]""")
    private val GOAL_REGEX = Regex("""\[goal::\s*([A-Za-z0-9_-]+)]""")
    private val AREA_REGEX = Regex("""\[area::\s*([A-Za-z0-9_-]+)]""")
    private val NOTE_LINK_REGEX = Regex("""\[focusdeck-note::\s*(\[\[[^\r\n\[\]]+\]\])\s*]""")
    private val ID_REGEX = Regex("""\^task-([A-Za-z0-9_-]+)\s*$""")
    private const val TASKS_HEADING = "## Задачи"
    private const val JOURNAL_OPEN = "<details class=\"daily-journal\">"
    private const val JOURNAL_CLOSE = "</details>"
    private const val SUMMARY_MARKER = "> [!summary]"

    /** Имя файла заметки за дату. Формат из vault'а: `00 - 2026-08-25.md`. */
    fun fileNameFor(date: LocalDate): String = "00 - %04d-%02d-%02d.md".format(
        date.year,
        date.monthValue,
        date.dayOfMonth,
    )

    fun parse(date: LocalDate, path: String, content: String): DailyNote {
        val lines = content.lines()
        return DailyNote(
            date = date,
            path = path,
            tasks = parseTasks(lines),
            journal = parseJournal(lines),
            lines = lines,
        )
    }

    /**
     * Чекбоксы берём только из секции `## Задачи` — в заметке могут быть
     * и другие списки, которые задачами дня не являются.
     */
    private fun parseTasks(lines: List<String>): List<VaultTask> {
        val start = lines.indexOfFirst { it.trim() == TASKS_HEADING }
        if (start == -1) return emptyList()

        val tasks = mutableListOf<VaultTask>()
        for (i in (start + 1) until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            // Следующий заголовок закрывает секцию.
            if (trimmed.startsWith("#")) break

            val match = TASK_REGEX.find(line) ?: continue
            val done = match.groupValues[1].lowercase() == "x"
            val source = match.groupValues[2]
            val raw = source.replace(NOTE_LINK_REGEX, "")
            val title = raw
                .replace(TIME_REGEX, "")
                .replace(INLINE_TIME_REGEX, "")
                .replace(DURATION_REGEX, "")
                .replace(PROJECT_REGEX, "").replace(GOAL_REGEX, "").replace(AREA_REGEX, "").replace(ID_REGEX, "")
                .trim()
            // Пустой чекбокс `- [ ]` — это заготовка, а не задача.
            if (title.isEmpty()) continue

            val startMinute = (TIME_REGEX.find(raw) ?: INLINE_TIME_REGEX.find(raw))?.let {
                it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
            }
            tasks += VaultTask(
                lineIndex = i,
                title = title,
                done = done,
                startMinuteOfDay = startMinute,
                durationMinutes = DURATION_REGEX.find(raw)?.groupValues?.get(1)?.toIntOrNull(),
                stableId = ID_REGEX.find(raw)?.groupValues?.get(1),
                projectId = PROJECT_REGEX.find(raw)?.groupValues?.get(1),
                goalId = GOAL_REGEX.find(raw)?.groupValues?.get(1),
                areaId = AREA_REGEX.find(raw)?.groupValues?.get(1),
                noteLink = NOTE_LINK_REGEX.find(source)?.groupValues?.get(1),
            )
        }
        return tasks
    }

    private fun parseJournal(lines: List<String>): VaultJournal {
        val open = lines.indexOfFirst { it.contains(JOURNAL_OPEN) }
        if (open == -1) return VaultJournal()
        val close = lines.drop(open).indexOfFirst { it.trim() == JOURNAL_CLOSE }
        if (close == -1) return VaultJournal()

        val body = lines.subList(open, open + close)
        fun field(label: String): String {
            val index = body.indexOfFirst { it.trim().startsWith("- $label:") }
            if (index < 0) return ""
            val parts = mutableListOf(body[index].substringAfter("$label:").trim())
            var next = index + 1
            while (next < body.size && body[next].startsWith("  ")) {
                parts += body[next].removePrefix("  ")
                next++
            }
            return parts.joinToString("\n").trim()
        }

        return VaultJournal(
            morning = field("Утро"),
            day = field("День"),
            evening = field("Вечер"),
            conclusions = field("Выводы"),
        )
    }

    /** Переключает галочку на конкретной строке. Остальной файл не трогаем. */
    fun toggleTask(note: DailyNote, lineIndex: Int, done: Boolean): String {
        val lines = note.lines.toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return note.lines.joinToString("\n")
        lines[lineIndex] = if (done) {
            line.replaceFirst(Regex("""-\s\[\s]"""), "- [x]")
        } else {
            line.replaceFirst(Regex("""-\s\[[xX]]"""), "- [ ]")
        }
        return lines.joinToString("\n")
    }

    /** Добавляет задачу в конец секции `## Задачи`. */
    fun addTask(
        note: DailyNote,
        title: String,
        startMinuteOfDay: Int? = null,
        durationMinutes: Int? = null,
        projectId: String? = null,
        goalId: String? = null,
        areaId: String? = null,
        stableId: String = java.util.UUID.randomUUID().toString(),
        noteLink: String? = null,
    ): String {
        require(noteLink == null || NOTE_LINK_REGEX.matches("[focusdeck-note:: $noteLink]")) { "Ссылка на заметку имеет неподдерживаемую разметку" }
        require(listOfNotNull(projectId, goalId, areaId, stableId).all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        val lines = note.lines.toMutableList()
        val headingIndex = lines.indexOfFirst { it.trim() == TASKS_HEADING }
        if (headingIndex == -1) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
            lines.add(TASKS_HEADING)
            lines.add("")
            return addTask(parse(note.date, note.path, lines.joinToString("\n")),
                title, startMinuteOfDay, durationMinutes, projectId, goalId, areaId, stableId, noteLink)
        }

        // Секция кончается следующим заголовком любого уровня.
        val sectionEnd = ((headingIndex + 1) until lines.size)
            .firstOrNull { lines[it].trimStart().startsWith("#") } ?: lines.size
        val lastTask = ((headingIndex + 1) until sectionEnd)
            .lastOrNull { TASK_REGEX.matches(lines[it]) }
        val insertAt = when {
            lastTask != null -> lastTask + 1
            // Пустая строка после заголовка — часть формата заметки, писать поверх неё нельзя.
            lines.getOrNull(headingIndex + 1)?.isBlank() == true -> headingIndex + 2
            else -> headingIndex + 1
        }.coerceAtMost(lines.size)

        val suffix = buildString {
            startMinuteOfDay?.let { append(" ⏳ %02d:%02d".format(it / 60, it % 60)) }
            durationMinutes?.let { append(" [dur:: $it]") }
            projectId?.let { append(" [project:: $it]") }
            goalId?.let { append(" [goal:: $it]") }
            areaId?.let { append(" [area:: $it]") }
            noteLink?.let { append(" [focusdeck-note:: $it]") }
            append(" ^task-$stableId")
        }
        lines.add(insertAt, "- [ ] ${title.replace('\n', ' ').replace('\r', ' ').trim()}$suffix")

        // Заголовок вплотную к списку — валидный markdown, но заметка от этого
        // выглядит слипшейся, а в пустой день так съедалась единственная пустая строка.
        if (lines.getOrNull(insertAt + 1)?.trimStart()?.startsWith("#") == true) {
            lines.add(insertAt + 1, "")
        }
        return lines.joinToString("\n")
    }

    /**
     * Находит строку задачи заново перед правкой.
     *
     * Между чтением и записью заметку мог изменить кто угодно — Obsidian на телефоне,
     * скрипт на Mac, Syncthing принёс чужую версию. Номер строки при этом съезжает,
     * и правка по старому индексу попала бы в соседнюю задачу. Поэтому индекс здесь
     * только подсказка: verified, если текст совпал, иначе ищем задачу по названию.
     * Не нашли — возвращаем null, и правка не применяется вовсе.
     */
    fun resolveTask(note: DailyNote, lineIndex: Int, title: String): VaultTask? {
        note.tasks.firstOrNull { it.lineIndex == lineIndex && it.title == title }?.let { return it }
        return note.tasks.filter { it.title == title }.singleOrNull()
    }

    fun resolveTask(note: DailyNote, task: VaultTask): VaultTask? {
        task.stableId?.let { id -> return note.tasks.filter { it.stableId == id }.singleOrNull() }
        return resolveTask(note, task.lineIndex, task.title)?.takeIf {
            it.projectId == task.projectId && it.goalId == task.goalId && it.areaId == task.areaId
        }
    }

    /** Меняет только текст задачи, сохраняя галочку, время и длительность. */
    fun renameTask(note: DailyNote, lineIndex: Int, title: String): String {
        val lines = note.lines.toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return lines.joinToString("\n")
        val match = TASK_REGEX.find(line) ?: return lines.joinToString("\n")
        val original = match.groupValues[2]
        val raw = original.replace(NOTE_LINK_REGEX, "")
        // Хвосты переносим как есть: пересобирать их из разобранных значений значило бы
        // молча нормализовать чужую разметку — например, превратить [time:: 9:00] в ⏳ 09:00.
        val suffix = buildString {
            (TIME_REGEX.find(raw) ?: INLINE_TIME_REGEX.find(raw))?.let { append(" ${it.value}") }
            DURATION_REGEX.find(raw)?.let { append(" ${it.value}") }
            PROJECT_REGEX.find(raw)?.let { append(" ${it.value}") }
            GOAL_REGEX.find(raw)?.let { append(" ${it.value}") }
            AREA_REGEX.find(raw)?.let { append(" ${it.value}") }
            NOTE_LINK_REGEX.find(original)?.let { append(" ${it.value}") }
            ID_REGEX.find(raw)?.let { append(" ${it.value}") }
        }
        val indent = line.takeWhile { it.isWhitespace() }
        val box = if (match.groupValues[1].lowercase() == "x") "x" else " "
        lines[lineIndex] = "$indent- [$box] ${title.replace('\n', ' ').replace('\r', ' ').trim()}$suffix"
        return lines.joinToString("\n")
    }

    /** Change only the ownership metadata, keeping time, completion and foreign task text. */
    fun linkTask(note: DailyNote, lineIndex: Int, projectId: String?, goalId: String?, areaId: String?, stableId: String, noteLink: String? = null): String {
        require(noteLink == null || NOTE_LINK_REGEX.matches("[focusdeck-note:: $noteLink]")) { "Ссылка на заметку имеет неподдерживаемую разметку" }
        require(listOfNotNull(projectId, goalId, areaId, stableId).all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        val lines = note.lines.toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return lines.joinToString("\n")
        if (!TASK_REGEX.matches(line)) return lines.joinToString("\n")
        val id = ID_REGEX.find(line)?.groupValues?.get(1) ?: stableId
        val clean = line.replace(NOTE_LINK_REGEX, "").replace(PROJECT_REGEX, "").replace(GOAL_REGEX, "").replace(AREA_REGEX, "").replace(ID_REGEX, "").trimEnd()
        lines[lineIndex] = buildString {
            append(clean)
            projectId?.let { append(" [project:: $it]") }
            goalId?.let { append(" [goal:: $it]") }
            areaId?.let { append(" [area:: $it]") }
            noteLink?.let { append(" [focusdeck-note:: $it]") }
            append(" ^task-$id")
        }
        return lines.joinToString("\n")
    }

    private fun preservingNoteLinks(line: String, transform: (String) -> String): String {
        val links = NOTE_LINK_REGEX.findAll(line).map { it.value }.toList()
        var index = 0
        val protected = line.replace(NOTE_LINK_REGEX) { "\u0000focusdeck-link-${index++}\u0000" }
        var result = transform(protected)
        links.forEachIndexed { i, value -> result = result.replace("\u0000focusdeck-link-$i\u0000", value) }
        return result
    }

    /** Задаёт длительность, не трогая время начала: задача может лежать в пуле без времени. */
    fun setDuration(note: DailyNote, lineIndex: Int, durationMinutes: Int): String {
        val lines = note.lines.toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return lines.joinToString("\n")
        val id = ID_REGEX.find(line)?.value
        lines[lineIndex] = preservingNoteLinks(line) { body ->
            val cleaned = body.replace(DURATION_REGEX, "").replace(ID_REGEX, "").trimEnd()
            "$cleaned [dur:: $durationMinutes]" + (id?.let { " $it" } ?: "")
        }
        return lines.joinToString("\n")
    }

    /** Удаляет строку задачи целиком. */
    fun deleteTask(note: DailyNote, lineIndex: Int): String {
        val lines = note.lines.toMutableList()
        if (lineIndex !in lines.indices) return lines.joinToString("\n")
        lines.removeAt(lineIndex)
        return lines.joinToString("\n")
    }

    /** Снимает задачу с расписания — убирает время и длительность, текст оставляет. */
    fun unscheduleTask(note: DailyNote, lineIndex: Int): String {
        val lines = note.lines.toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return lines.joinToString("\n")
        lines[lineIndex] = preservingNoteLinks(line) { body -> body
            .replace(TIME_REGEX, "")
            .replace(INLINE_TIME_REGEX, "")
            .replace(DURATION_REGEX, "")
            .trimEnd() }
        return lines.joinToString("\n")
    }

    /**
     * Заготовка новой заметки дня.
     *
     * Повторяет формат существующих заметок дословно, включая пустой блок
     * movebreak-daily-plan и шапку сводки: иначе плагин и скрипт на Mac
     * не найдут своих мест и создадут их сами, продублировав секции.
     */
    fun template(date: LocalDate): String = """
        ---
        day: %04d-%02d-%02d
        tags:
          - daily
          - planning
        ---

        ## Задачи

        # Day planner

        ```movebreak-daily-plan
        ```

        ## Итоги дня

        > [!summary] Краткая сводка дня

        <details class="daily-journal">
        <summary>Дневник</summary>

        - Утро:
        - День:
        - Вечер:
        - Выводы:

        </details>
    """.trimIndent().format(date.year, date.monthValue, date.dayOfMonth) + "\n"

    /** Проставляет задаче время начала и длительность, сохраняя текст. */
    fun scheduleTask(
        note: DailyNote,
        lineIndex: Int,
        startMinuteOfDay: Int,
        durationMinutes: Int?,
    ): String {
        val lines = note.lines.toMutableList()
        val line = lines.getOrNull(lineIndex) ?: return lines.joinToString("\n")
        val id = ID_REGEX.find(line)?.value
        lines[lineIndex] = preservingNoteLinks(line) { body ->
            val cleaned = body.replace(TIME_REGEX, "").replace(INLINE_TIME_REGEX, "")
                .replace(DURATION_REGEX, "").replace(ID_REGEX, "").trimEnd()
            val suffix = buildString {
                append(" ⏳ %02d:%02d".format(startMinuteOfDay / 60, startMinuteOfDay % 60))
                durationMinutes?.let { append(" [dur:: $it]") }
                id?.let { append(" $it") }
            }
            cleaned + suffix
        }
        return lines.joinToString("\n")
    }

    /** Заполняет поля внутри блока дневника, не трогая его обёртку. */
    fun writeJournal(note: DailyNote, journal: VaultJournal): String {
        val fields = mapOf(
            "Утро" to journal.morning,
            "День" to journal.day,
            "Вечер" to journal.evening,
            "Выводы" to journal.conclusions,
        )
        val detailsStart = Regex("""<\s*/?\s*details\b""", RegexOption.IGNORE_CASE)
        val detailsTag = Regex("""<\s*(/?)\s*details\b[^>]*>""", RegexOption.IGNORE_CASE)
        val journalClass = Regex("""\bdaily-journal\b""", RegexOption.IGNORE_CASE)
        val structureError = "Разметка дневника повреждена или неоднозначна. Исправь блок дневника в заметке и повтори сохранение."

        // The reader understands one canonical wrapper. Never append a second journal
        // beside an unsupported wrapper, or mistake a nested/foreign closing tag for ours.
        fun bounds(source: List<String>): Pair<Int, Int>? {
            val content = source.joinToString("\n")
            val tags = detailsTag.findAll(content).toList()
            check(detailsStart.findAll(content).count() == tags.size) { structureError }
            val stack = mutableListOf<Boolean>()
            var open: Int? = null
            var close: Int? = null
            for (tag in tags) {
                val lineIndex = content.take(tag.range.first).count { it == '\n' }
                if (tag.groupValues[1].isEmpty()) {
                    check(stack.none { it }) { structureError }
                    val isJournal = journalClass.containsMatchIn(tag.value)
                    if (isJournal) {
                        check(open == null && tag.value == JOURNAL_OPEN && source[lineIndex].trim() == JOURNAL_OPEN) { structureError }
                        open = lineIndex
                    }
                    stack.add(isJournal)
                } else {
                    check(stack.isNotEmpty()) { structureError }
                    if (stack.removeAt(stack.lastIndex)) {
                        check(source[lineIndex].trim() == JOURNAL_CLOSE) { structureError }
                        close = lineIndex
                    }
                }
            }
            check(stack.isEmpty() && ((open == null) == (close == null))) { structureError }
            return open?.let { it to checkNotNull(close) }
        }

        fun positions(source: List<String>, open: Int, close: Int): Map<String, Int> =
            fields.keys.mapNotNull { label ->
                val matches = ((open + 1) until close).filter { source[it].trim().startsWith("- $label:") }
                check(matches.size <= 1) { structureError }
                matches.singleOrNull()?.let { index ->
                    // Two-space indentation belongs to the preceding field, even though
                    // parseJournal also recognises its text as a new label.
                    check(!source[index].startsWith("  ")) { structureError }
                    label to index
                }
            }.toMap()

        require(fields.values.none { detailsStart.containsMatchIn(it) }) {
            "Текст дневника содержит тег details. Удали разметку обёртки из текста и повтори сохранение."
        }
        val lines = note.lines.toMutableList()
        val existingBounds = bounds(lines)
        val open: Int
        var close: Int
        if (existingBounds == null) {
            if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
            open = lines.size
            lines.addAll(listOf(JOURNAL_OPEN, "<summary>Дневник</summary>", JOURNAL_CLOSE))
            close = lines.lastIndex
        } else {
            open = existingBounds.first
            close = existingBounds.second
        }
        val originalPositions = positions(lines, open, close)
        val originalJournal = parseJournal(lines)
        val originalFields = mapOf("Утро" to originalJournal.morning, "День" to originalJournal.day,
            "Вечер" to originalJournal.evening, "Выводы" to originalJournal.conclusions)

        // Work backwards so a multiline field cannot shift the indexes of later fields.
        val orderedLabels = fields.keys.toList()
        orderedLabels.forEachIndexed { fieldIndex, label ->
            if ((open until close).none { lines[it].trim().startsWith("- $label:") }) {
                val next = orderedLabels.drop(fieldIndex + 1)
                    .mapNotNull { nextLabel ->
                        (open until close).firstOrNull { lines[it].trim().startsWith("- $nextLabel:") }
                    }.minOrNull() ?: close
                lines.add(next, "- $label:")
                close++
            }
        }
        val replacements = positions(lines, open, close).entries.sortedByDescending { it.value }
        for ((label, index) in replacements) {
            if (label in originalPositions && fields.getValue(label) == originalFields.getValue(label)) continue
            var end = index + 1
            while (end < close && lines[end].startsWith("  ")) end++
            val valueLines = fields.getValue(label).lines()
            val replacement = listOf("- $label:" + valueLines.first().let { if (it.isEmpty()) "" else " $it" }) +
                valueLines.drop(1).map { "  $it" }
            lines.subList(index, end).clear()
            lines.addAll(index, replacement)
            close += replacement.size - (end - index)
        }
        val writtenBounds = checkNotNull(bounds(lines)) { structureError }
        positions(lines, writtenBounds.first, writtenBounds.second)
        // In particular, the reader trims first lines and surrounding whitespace.
        // Refuse unrepresentable input instead of acknowledging a lossy write.
        require(parseJournal(lines) == journal) {
            "Текст дневника не удаётся сохранить без изменений. Проверь отступы и метки полей; запись не выполнена."
        }
        return lines.joinToString("\n")
    }

    /**
     * Обновляет строки в блоке `> [!summary]`.
     * Экранное время и фокус приходят с телефона — на Mac их взять неоткуда.
     */
    /**
     * Строки итога ровно в том виде, в каком они лягут в заметку.
     *
     * Вынесено наружу, чтобы главный экран показывал не пересказ, а те же самые
     * строки: пересказ рано или поздно разошёлся бы с тем, что пишется на диск.
     */
    fun summaryLines(summary: DailySummary): List<String> = listOf(
        "> - Экранное время (телефон): **${formatMinutes(summary.screenMinutes)}**.",
        "> - Фокус (телефон): **${formatMinutes(summary.focusMinutes.toLong())}**.",
        "> - Задачи: **${summary.tasksDone} из ${summary.tasksTotal}**.",
        "> - Сорвано блоков: **${summary.interruptedBlocks}**.",
        "> - Калории (телефон): **${summary.calories} из ${summary.calorieTarget}**.",
    )

    /** Есть ли в заметке блок, куда вообще можно писать итог. */
    fun hasSummarySection(note: DailyNote): Boolean =
        note.lines.any { it.trimStart().startsWith(SUMMARY_MARKER) }

    fun writeSummary(note: DailyNote, summary: DailySummary): String {
        val lines = note.lines.toMutableList()
        val start = lines.indexOfFirst { it.trimStart().startsWith(SUMMARY_MARKER) }
        if (start == -1) return lines.joinToString("\n")

        var end = start + 1
        while (end < lines.size && lines[end].trimStart().startsWith(">")) end++

        val replacement = summaryLines(summary)

        // Свои строки помечены «(телефон)» — чужие из Mac-скрипта не трогаем.
        val kept = lines.subList(start + 1, end).filterNot {
            it.contains("(телефон)") || it.contains("Задачи:") || it.contains("Сорвано блоков:")
        }
        val rebuilt = lines.subList(0, start + 1) + kept + replacement + lines.subList(end, lines.size)
        return rebuilt.joinToString("\n")
    }

    private fun formatMinutes(total: Long): String {
        if (total <= 0) return "0 мин"
        val h = total / 60
        val m = total % 60
        return if (h > 0) "$h ч $m мин" else "$m мин"
    }
}

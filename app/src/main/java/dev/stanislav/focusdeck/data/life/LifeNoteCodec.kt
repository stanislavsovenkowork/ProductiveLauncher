package dev.stanislav.focusdeck.data.life

import java.time.LocalDate

/** A deliberately small Markdown schema, compatible with Obsidian properties.
 * We only edit scalar properties and our named sections. Unknown YAML/body stays intact.
 * Unsupported or ambiguous structures fail closed instead of being normalized away.
 */
object LifeNoteCodec {
    private val property = Regex("^([a-z][a-z0-9_-]*):(?:[ \\t]*(.*))?$")
    private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{5,80}")
    private val sectionByHeading = LifeField.entries.associateBy { "## ${it.heading}" }
    private val metadataKeys = setOf("focusdeck", "id", "kind", "title", "status", "area_id", "goal_id", "project_id", "due", "review", "created", "updated", "decided_on", "reviewed_on", "period_start", "period_end")

    fun parse(path: String, raw: String): LifeEntry? {
        val lines = raw.replace("\r\n", "\n").lines()
        if (lines.firstOrNull() != "---") return null
        val end = lines.drop(1).indexOfFirst { it == "---" }.let { if (it < 0) -1 else it + 1 }
        if (end < 0) return null
        val problems = mutableListOf<String>()
        val values = mutableMapOf<String, String>()
        val seen = mutableSetOf<String>()
        var lastProperty: String? = null
        for (line in lines.subList(1, end)) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val match = property.matchEntire(line)
            if (match == null) {
                if (lastProperty in metadataKeys) problems += "Неподдерживаемый многострочный формат свойства $lastProperty"
                continue
            }
            val key = match.groupValues[1]
            lastProperty = key
            if (key !in metadataKeys) continue
            if (!seen.add(key)) problems += "Повтор свойства $key"
            val decoded = scalar(match.groupValues[2])
            if (decoded == null) problems += "Неподдерживаемый формат свойства $key"
            else values[key] = decoded
        }
        if (values["focusdeck"] != "1") return null
        val id = values["id"]?.takeIf { safeId.matches(it) } ?: return null
        val kind = LifeKind.entries.firstOrNull { it.name.equals(values["kind"], true) } ?: return null
        val status = LifeStatus.entries.firstOrNull { it.name.equals(values["status"], true) }
            ?: LifeStatus.DRAFT.also { if (!values["status"].isNullOrBlank()) problems += "Неизвестный статус" }
        fun date(key: String): LocalDate? {
            val value = values[key]?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { LocalDate.parse(value) }.getOrNull().also {
                if (it == null) problems += "Неверная дата $key"
            }
        }
        val sections = sections(lines, end + 1)
        val fields = mutableMapOf<LifeField, String>()
        for (section in sections) {
            val field = sectionByHeading[lines[section.first].trim()] ?: continue
            if (field in fields) problems += "Повтор раздела ${field.heading}"
            fields[field] = lines.subList(section.first + 1, section.second).joinToString("\n").trim()
        }
        return LifeEntry(
            id = id, kind = kind, title = values["title"].orEmpty(), status = status,
            areaId = values["area_id"]?.takeIf { it.isNotBlank() },
            goalId = values["goal_id"]?.takeIf { it.isNotBlank() },
            projectId = values["project_id"]?.takeIf { it.isNotBlank() },
            dueDate = date("due"), reviewDate = date("review"),
            decidedOn = date("decided_on"), reviewedOn = date("reviewed_on"),
            periodStart = date("period_start"), periodEnd = date("period_end"),
            created = date("created") ?: LocalDate.of(1970, 1, 1),
            updated = date("updated") ?: LocalDate.of(1970, 1, 1),
            fields = fields, path = path, raw = raw, problems = problems,
        )
    }

    fun create(entry: LifeEntry): String = buildString {
        require(safeId.matches(entry.id))
        append("---\n")
        metadata(entry).forEach { (key, value) -> append("$key: ${quote(value)}\n") }
        append("---\n\n# ${entry.title.replace('\n', ' ')}\n")
        (fieldsFor(entry.kind) + listOf(LifeField.LINKS, LifeField.HISTORY, LifeField.REVIEW_HISTORY).filter { entry.text(it).isNotBlank() }).forEach { field ->
            append("\n## ${field.heading}\n\n${entry.text(field).trim()}\n")
        }
    }

    /** Three-way field merge: unrelated external edits survive; same-field edits need review. */
    fun merge(base: LifeEntry, edited: LifeEntry, fresh: LifeEntry, today: LocalDate): String {
        require(base.id == edited.id && base.id == fresh.id && base.kind == fresh.kind && base.kind == edited.kind)
        check(fresh.problems.isEmpty()) { fresh.problems.joinToString("; ") }
        val before = metadata(base)
        val requested = metadata(edited)
        val latest = metadata(fresh)
        val changedKeys = requested.keys.filter { it !in setOf("updated", "created", "id", "kind", "focusdeck") && requested[it] != before[it] }
        val changedFields = LifeField.entries.filter { base.text(it) != edited.text(it) }
        val conflicts = changedKeys.filter { latest[it] != before[it] && latest[it] != requested[it] }.toMutableList()
        conflicts += changedFields.filter { fresh.text(it) != base.text(it) && fresh.text(it) != edited.text(it) }.map { it.heading }
        if (conflicts.isNotEmpty()) throw LifeConflictException(conflicts)
        if (changedKeys.isEmpty() && changedFields.isEmpty()) return fresh.raw
        val newline = if (fresh.raw.contains("\r\n")) "\r\n" else "\n"
        var lines = fresh.raw.replace("\r\n", "\n").lines().toMutableList()
        for (key in changedKeys + "updated") {
            val value = if (key == "updated") today.toString() else requested[key].orEmpty()
            val end = lines.drop(1).indexOfFirst { it == "---" } + 1
            val index = (1 until end).firstOrNull { property.matchEntire(lines[it])?.groupValues?.get(1) == key }
            if (index != null) lines[index] = "$key: ${quote(value)}" else lines.add(end, "$key: ${quote(value)}")
        }
        if ("title" in changedKeys) {
            val index = lines.indexOfFirst { it == "# ${fresh.title}" }
            if (index >= 0) lines[index] = "# ${edited.title.replace('\n', ' ')}"
        }
        for (field in changedFields) {
            val end = lines.drop(1).indexOfFirst { it == "---" } + 1
            val section = sections(lines, end + 1).firstOrNull { lines[it.first].trim() == "## ${field.heading}" }
            val content = edited.text(field).trim().lines()
            if (section == null) {
                if (lines.lastOrNull()?.isNotBlank() == true) lines.add("")
                lines.add("## ${field.heading}")
                lines.add("")
                lines.addAll(content)
                lines.add("")
            } else {
                lines.subList(section.first + 1, section.second).clear()
                lines.addAll(section.first + 1, listOf("") + content + listOf(""))
            }
        }
        return lines.joinToString(newline)
    }

    private fun metadata(entry: LifeEntry): Map<String, String> = linkedMapOf(
        "focusdeck" to "1", "id" to entry.id, "kind" to entry.kind.name.lowercase(),
        "title" to entry.title.trim(), "status" to entry.status.name.lowercase(),
        "area_id" to entry.areaId.orEmpty(), "goal_id" to entry.goalId.orEmpty(), "project_id" to entry.projectId.orEmpty(),
        "due" to entry.dueDate?.toString().orEmpty(), "review" to entry.reviewDate?.toString().orEmpty(),
        "created" to entry.created.toString(), "updated" to entry.updated.toString(),
        "decided_on" to entry.decidedOn?.toString().orEmpty(), "reviewed_on" to entry.reviewedOn?.toString().orEmpty(),
        "period_start" to entry.periodStart?.toString().orEmpty(), "period_end" to entry.periodEnd?.toString().orEmpty(),
    )

    private fun sections(lines: List<String>, from: Int): List<Pair<Int, Int>> {
        val heads = mutableListOf<Int>()
        var fence: Char? = null
        var fenceLength = 0
        for (index in from until lines.size) {
            val text = lines[index].trimStart()
            val marker = text.firstOrNull()
            val count = text.takeWhile { it == marker }.length
            if ((marker == '`' || marker == '~') && count >= 3) {
                if (fence == null) { fence = marker; fenceLength = count }
                else if (marker == fence && count >= fenceLength) fence = null
                continue
            }
            if (fence == null && (text.startsWith("## ") || text.startsWith("# "))) heads += index
        }
        return heads.mapIndexed { index, line -> line to (heads.getOrNull(index + 1) ?: lines.size) }
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\")
        .replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun scalar(source: String): String? {
        val value = source.trim()
        if (value.isEmpty() || value == "null" || value == "~") return ""
        if (value.startsWith("'")) {
            val match = Regex("^'((?:[^']|'')*)'(?:[ \\t]+#.*)?$").matchEntire(value) ?: return null
            return match.groupValues[1].replace("''", "'")
        }
        if (!value.startsWith('"')) {
            if (value.startsWith('|') || value.startsWith('>') || value.startsWith('[') || value.startsWith('{') || value.startsWith('&') || value.startsWith('*') || value.startsWith('!')) return null
            return value.substringBefore(" #").trimEnd()
        }
        val result = StringBuilder()
        var i = 1
        while (i < value.length) {
            val c = value[i++]
            if (c == '"') {
                val suffix = value.substring(i)
                return if (suffix.isBlank() || suffix.matches(Regex("[ \\t]+#.*"))) result.toString() else null
            }
            if (c != '\\') { result.append(c); continue }
            if (i >= value.length) return null
            when (val escape = value[i++]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                '\\', '"', '/' -> result.append(escape)
                'u' -> {
                    if (i + 4 > value.length) return null
                    val code = value.substring(i, i + 4).toIntOrNull(16) ?: return null
                    result.append(code.toChar()); i += 4
                }
                else -> return null
            }
        }
        return null // Missing closing quote.
    }
}

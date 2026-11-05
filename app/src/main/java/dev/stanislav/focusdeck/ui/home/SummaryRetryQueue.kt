package dev.stanislav.focusdeck.ui.home

import dev.stanislav.focusdeck.data.vault.DailyNote
import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.DailySummary
import java.time.LocalDate
import java.util.UUID

/** Small durable outbox: no vault enumeration; acknowledgements cannot erase newer requests. */
internal class SummaryRetryQueue(
    initial: Set<String>,
    private val persist: (Set<String>) -> Boolean,
) {
    data class Request(val date: LocalDate, val root: String, val token: String) {
        fun encoded(): String = "$date|$token|$root"
    }
    private var entries = initial.toSet()
    val pendingCount: Int get() = entries.size

    fun enqueue(date: LocalDate, root: String, changed: Boolean = false): Request {
        require(root.isNotBlank())
        val old = entries.mapNotNull(::decode).firstOrNull { it.date == date && it.root == root }
        if (old != null && !changed) return old
        val request = Request(date, root, UUID.randomUUID().toString())
        val next = entries.filterNot { decode(it)?.let { value -> value.date == date && value.root == root } == true }.toSet() + request.encoded()
        check(persist(next)) { "Cannot persist summary request" }
        entries = next
        return request
    }

    fun forRoot(root: String, today: LocalDate): List<Request> = entries.mapNotNull(::decode)
        .filter { it.root == root && it.date < today }.sortedBy { it.date }

    fun acknowledge(request: Request, written: Boolean) {
        if (!written || request.encoded() !in entries) return
        val next = entries - request.encoded()
        check(persist(next)) { "Cannot persist summary acknowledgement" }
        entries = next
    }

    companion object {
        fun decode(value: String): Request? = runCatching {
            val parts = value.split('|', limit = 3)
            require(parts.size == 3 && parts[1].isNotBlank() && parts[2].isNotBlank())
            Request(LocalDate.parse(parts[0]), parts[2], parts[1])
        }.getOrNull()
    }
}

/** Preflight applies only before enqueue: it never acknowledges an existing pending write. */
internal fun summaryNeedsWrite(note: DailyNote?, summary: DailySummary): Boolean =
    note != null && (!DailyNoteParser.hasSummarySection(note) ||
        DailyNoteParser.writeSummary(note, summary) != note.lines.joinToString("\n"))

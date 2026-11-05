package dev.stanislav.focusdeck.data.repo

import dev.stanislav.focusdeck.data.db.FocusBlock
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** Export only closed intervals. Old unclassified timers are not guessed or assigned an identity. */
object FocusEventExporter {
    fun jsonl(blocks: List<FocusBlock>): String = blocks.flatMap(::events).joinToString("\n", postfix = if (blocks.any { eligible(it) }) "\n" else "")

    private fun eligible(block: FocusBlock): Boolean = block.category in setOf("work", "study") &&
        runCatching { UUID.fromString(block.sessionId) }.isSuccess

    private fun events(block: FocusBlock): List<String> {
        if (!eligible(block)) return emptyList()
        val session = requireNotNull(block.sessionId)
        val category = requireNotNull(block.category)
        val records = mutableListOf(event(block, session, category, "started", "started", block.startEpochMillis))
        val breakStart = block.firstBreakStartedAt
        if (breakStart != null) {
            val valid = (block.endEpochMillis == null || breakStart <= block.endEpochMillis) && reliable(block.startEpochMillis, breakStart, block.startUptimeMillis, block.pauseUptimeMillis,
                block.startBootCount, block.breakBootCount, block.startProcessId, block.pauseProcessId)
            records += event(block, session, category, "paused", "paused", breakStart,
                block.startEpochMillis.takeIf { valid }, breakStart.takeIf { valid }, missing = !valid)
        }
        val end = block.endEpochMillis
        val resumed = block.firstBreakEndedAt
        if (breakStart != null && resumed != null) {
            records += event(block, session, category, "resumed", "resumed", resumed,
                missing = resumed < breakStart || (end != null && resumed > end))
        }
        if (end != null) {
            // If stopped during a break, the previous paused record already contains the last working interval.
            val segmentStart = if (breakStart == null) block.startEpochMillis else block.firstBreakEndedAt
            val valid = segmentStart != null && if (breakStart == null)
                reliable(segmentStart, end, block.startUptimeMillis, block.endUptimeMillis,
                    block.startBootCount, block.endBootCount, block.startProcessId, block.endProcessId)
            else reliable(segmentStart, end, block.resumeUptimeMillis, block.endUptimeMillis,
                block.resumeBootCount, block.endBootCount, block.resumeProcessId, block.endProcessId)
            records += event(block, session, category, if (block.interrupted) "stopped" else "completed",
                "finished", end, segmentStart?.takeIf { valid }, end.takeIf { valid }, missing = segmentStart != null && !valid)
        }
        return records
    }

    /** Wall-clock intervals are emitted only while process, boot and awake uptime agree. */
    private fun reliable(start: Long, end: Long, startUptime: Long?, endUptime: Long?,
                         startBoot: Int?, endBoot: Int?, startProcess: String?, endProcess: String?): Boolean {
        if (startUptime == null || endUptime == null || startBoot == null || startBoot < 0 || startBoot != endBoot ||
            startProcess == null || startProcess != endProcess || end < start || endUptime < startUptime) return false
        val wall = end - start
        val uptime = endUptime - startUptime
        return kotlin.math.abs(wall - uptime) <= 2_000L
    }

    private fun event(block: FocusBlock, session: String, category: String, type: String, identity: String,
                      time: Long, start: Long? = null, end: Long? = null, missing: Boolean = false): String {
        val id = UUID.nameUUIDFromBytes("focusdeck:android:$session:$identity".toByteArray(StandardCharsets.UTF_8))
        fun instant(value: Long) = Instant.ofEpochMilli(value).toString()
        val taskRef = block.taskStableId?.let(::json) ?: "null"
        val interval = if (start != null && end != null)
            ",\"startedAt\":${json(instant(start))},\"endedAt\":${json(instant(end))}" else ""
        val seconds = if (start != null && end != null) ((end - start) / 1000.0).toString() else "null"
        val coverage = if (missing) ",\"coverage\":\"missing\",\"reason\":\"clock_or_process_gap\"" else ""
        return "{\"schemaVersion\":1,\"eventId\":${json(id.toString())},\"sessionId\":${json(session)}," +
            "\"occurredAt\":${json(instant(time))},\"source\":\"android\",\"category\":${json(category)}," +
            "\"type\":${json(type)},\"evidence\":\"timer\",\"metrics\":{\"focusSeconds\":$seconds," +
            "\"distractionSeconds\":null,\"maintenanceSeconds\":null},\"taskRef\":$taskRef$interval$coverage}"
    }

    private fun json(value: String): String = buildString {
        append('"')
        value.forEach { c -> when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
}

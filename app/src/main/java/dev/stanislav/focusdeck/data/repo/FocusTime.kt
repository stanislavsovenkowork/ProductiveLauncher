package dev.stanislav.focusdeck.data.repo

import dev.stanislav.focusdeck.data.db.FocusBlock

/** A break remains paused until the person explicitly returns, even past its suggested limit. */
const val BREAK_MILLIS = 5 * 60_000L

data class FocusClockAnchor(val wallMillis: Long, val uptimeMillis: Long, val bootCount: Int, val processId: String)

fun FocusBlock.focusMillis(now: Long): Long {
    val end = endEpochMillis ?: now
    val pausedNow = breakStartedAt?.let { (end - it).coerceAtLeast(0) } ?: 0
    return (end - startEpochMillis - pausedMillis - pausedNow).coerceAtLeast(0)
}

fun FocusBlock.afterReturn(now: Long): FocusBlock = breakStartedAt?.let {
    copy(pausedMillis = pausedMillis + (now - it).coerceAtLeast(0), breakStartedAt = null, firstBreakEndedAt = now.coerceAtLeast(it))
} ?: this

/** Ending inside a break is not a return; preserve that distinction in the event log. */
fun FocusBlock.afterStop(now: Long): FocusBlock = breakStartedAt?.let {
    copy(pausedMillis = pausedMillis + (now - it).coerceAtLeast(0), breakStartedAt = null)
} ?: this

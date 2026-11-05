package dev.stanislav.focusdeck.ui.apps

import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.repo.BREAK_MILLIS

/** Only controls launches from this launcher; other Android entry points remain available. */
internal fun launcherRestricted(block: FocusBlock?, selected: Set<String>, packageName: String,
                                elapsedRealtime: Long, bootCount: Int): Boolean {
    if (block == null || block.endEpochMillis != null || packageName !in selected) return false
    return reliefRemainingMillis(block, elapsedRealtime, bootCount) == 0L
}

/** Unknown/rebooted clock ends relief conservatively without resuming the timer. */
internal fun reliefRemainingMillis(block: FocusBlock, elapsedRealtime: Long, bootCount: Int): Long {
    val start = block.breakStartedElapsedMillis ?: return 0
    if (block.breakStartedAt == null || bootCount < 0 || block.breakBootCount != bootCount || elapsedRealtime < start) return 0
    return (BREAK_MILLIS - (elapsedRealtime - start)).coerceAtLeast(0)
}

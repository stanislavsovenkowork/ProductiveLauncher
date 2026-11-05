package dev.stanislav.focusdeck.ui.ritual

import android.content.Context
import java.time.LocalDateTime

/** QA-only clock: a private file lets device tests reach 23:00/06:00 without changing Android time. */
internal fun ritualNow(context: Context): LocalDateTime {
    val override = context.filesDir.resolve("ritual-qa-clock.txt")
    if (!override.exists()) return LocalDateTime.now()
    return runCatching { LocalDateTime.parse(override.readText().trim()) }
        .getOrElse { LocalDateTime.now() }
}

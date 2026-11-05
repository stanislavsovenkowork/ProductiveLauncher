package dev.stanislav.focusdeck.ui.home

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** A suggestion about study context, not a change to the user's app selection. */
internal fun workMessagesOptionalAfter15(now: Instant): Boolean =
    !now.atZone(ZoneId.of("Europe/Minsk")).toLocalTime().isBefore(LocalTime.of(15, 0))

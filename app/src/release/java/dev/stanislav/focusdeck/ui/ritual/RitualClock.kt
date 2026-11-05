package dev.stanislav.focusdeck.ui.ritual

import android.content.Context
import java.time.LocalDateTime

/** Release builds always follow the actual device clock. */
internal fun ritualNow(@Suppress("UNUSED_PARAMETER") context: Context): LocalDateTime =
    LocalDateTime.now()

package dev.stanislav.focusdeck.ui.home

import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal fun parseProtectionTime(input: String): LocalTime =
    LocalTime.parse(input.trim(), DateTimeFormatter.ofPattern("HH:mm"))

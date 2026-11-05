package dev.stanislav.focusdeck.ui.restrictions

internal enum class AppGateStatus { DISCONNECTED, INITIALIZING, READY, UNAVAILABLE }

internal fun appGateStatus(connected: Boolean, ready: Boolean, failed: Boolean): AppGateStatus = when {
    !connected -> AppGateStatus.DISCONNECTED
    failed -> AppGateStatus.UNAVAILABLE
    !ready -> AppGateStatus.INITIALIZING
    else -> AppGateStatus.READY
}

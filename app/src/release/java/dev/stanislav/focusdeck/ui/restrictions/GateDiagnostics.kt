package dev.stanislav.focusdeck.ui.restrictions

/** Diagnostics are available only in the isolated QA build. */
internal object GateDiagnostics {
    fun emit(stage: String, error: Throwable? = null) = Unit
}

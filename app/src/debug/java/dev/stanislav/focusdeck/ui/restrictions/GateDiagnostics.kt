package dev.stanislav.focusdeck.ui.restrictions

import android.util.Log

/** QA-only lifecycle markers. Never log exception messages or event contents. */
internal object GateDiagnostics {
    internal fun marker(stage: String, error: Throwable? = null): String =
        if (error == null) stage else "$stage class=${error.javaClass.name}"

    fun emit(stage: String, error: Throwable? = null) {
        Log.i("FocusDeckGateQA", marker(stage, error))
    }
}

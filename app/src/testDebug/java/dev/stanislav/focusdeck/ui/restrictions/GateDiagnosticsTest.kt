package dev.stanislav.focusdeck.ui.restrictions

import org.junit.Assert.assertEquals
import org.junit.Test

class GateDiagnosticsTest {
    @Test fun logsOnlyStageAndExceptionClass() {
        assertEquals("service_connected_callback", GateDiagnostics.marker("service_connected_callback"))
        assertEquals("room_error class=java.lang.IllegalStateException",
            GateDiagnostics.marker("room_error", IllegalStateException("private text")))
    }
}

package dev.stanislav.focusdeck.ui.restrictions

import org.junit.Assert.assertEquals
import org.junit.Test

class AppGateStatusTest {
    @Test fun reportsConnectionInitializationAndFailureSeparately() {
        assertEquals(AppGateStatus.DISCONNECTED, appGateStatus(false, false, false))
        assertEquals(AppGateStatus.DISCONNECTED, appGateStatus(false, false, true))
        assertEquals(AppGateStatus.INITIALIZING, appGateStatus(true, false, false))
        assertEquals(AppGateStatus.READY, appGateStatus(true, true, false))
        assertEquals(AppGateStatus.UNAVAILABLE, appGateStatus(true, true, true))
    }
}

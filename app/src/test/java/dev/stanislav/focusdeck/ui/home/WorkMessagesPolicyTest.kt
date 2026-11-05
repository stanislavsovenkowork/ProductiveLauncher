package dev.stanislav.focusdeck.ui.home

import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkMessagesPolicyTest {
    @Test fun usesMinskTimeAtTheBoundaryRegardlessOfDeviceTimezone() {
        assertFalse(workMessagesOptionalAfter15(Instant.parse("2026-10-28T11:59:59Z")))
        assertTrue(workMessagesOptionalAfter15(Instant.parse("2026-10-28T12:00:00Z")))
    }
}

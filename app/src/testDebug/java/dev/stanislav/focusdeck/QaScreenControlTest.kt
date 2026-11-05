package dev.stanislav.focusdeck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaScreenControlTest {
    @Test fun keepScreenOnOnlyForOptedInResumedDebugQa() {
        val qa = "dev.stanislav.focusdeck.qa"
        assertTrue(QaScreenControl.shouldKeep(qa, true, true, true))
        assertFalse(QaScreenControl.shouldKeep(qa, true, true, false))
        assertFalse(QaScreenControl.shouldKeep(qa, true, false, true))
        assertFalse(QaScreenControl.shouldKeep(qa, false, true, true))
        assertFalse(QaScreenControl.shouldKeep("dev.stanislav.focusdeck", true, true, true))
    }
}

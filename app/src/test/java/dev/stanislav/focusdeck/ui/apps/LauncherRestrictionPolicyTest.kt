package dev.stanislav.focusdeck.ui.apps

import dev.stanislav.focusdeck.data.db.FocusBlock
import dev.stanislav.focusdeck.data.repo.BREAK_MILLIS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherRestrictionPolicyTest {
    private val app = "example.app"
    private val selected = setOf(app)
    private val active = FocusBlock(title = "x", plannedMinutes = 25, startEpochMillis = 1000, dayEpochDay = 1)
    private val paused = active.copy(breakStartedAt = 2000, breakStartedElapsedMillis = 3000, breakBootCount = 9)

    @Test fun boundedReliefEndsAutomaticallyWithoutResumingWork() {
        assertTrue(launcherRestricted(active, selected, app, 3000, 9))
        assertFalse(launcherRestricted(paused, selected, app, 3000 + BREAK_MILLIS - 1, 9))
        assertTrue(launcherRestricted(paused, selected, app, 3000 + BREAK_MILLIS, 9))
        assertTrue(paused.breakStartedAt != null)
    }

    @Test fun restartAndClockUncertaintyConservativelyEndRelief() {
        assertFalse(launcherRestricted(paused.copy(), selected, app, 3001, 9))
        assertTrue(launcherRestricted(paused, selected, app, 1, 10))
        assertTrue(launcherRestricted(paused, selected, app, 2999, 9))
        assertTrue(launcherRestricted(paused.copy(breakBootCount = null), selected, app, 3001, 9))
    }

    @Test fun criticalPackagesAreAlwaysExemptFromSelection() {
        for (pkg in listOf("com.android.settings", "com.android.systemui", "com.google.android.dialer",
            "com.android.emergency", "com.android.cellbroadcastreceiver", "dev.stanislav.focusdeck.qa")) {
            assertTrue(safetyNameExempt(pkg, "dev.stanislav.focusdeck.qa"))
        }
        assertFalse(safetyNameExempt("dev.stanislav.focusdeck.qa.test", "dev.stanislav.focusdeck.qa"))
    }

    @Test fun noSessionOrExplicitSelectionNeverBlocks() {
        assertFalse(launcherRestricted(null, selected, app, 3000, 9))
        assertFalse(launcherRestricted(active, emptySet(), app, 3000, 9))
        assertFalse(launcherRestricted(active.copy(endEpochMillis = 2000), selected, app, 3000, 9))
    }
}

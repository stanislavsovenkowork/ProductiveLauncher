package dev.stanislav.focusdeck.ui.restrictions

import dev.stanislav.focusdeck.ui.restrictions.RestrictionController.Action
import dev.stanislav.focusdeck.ui.restrictions.RestrictionController.Mode
import dev.stanislav.focusdeck.ui.restrictions.RestrictionController.Request
import dev.stanislav.focusdeck.ui.restrictions.RestrictionController.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionControllerTest {
    private val ready = Snapshot(Mode.NONE, true, true, false, 42)

    @Test fun `unmanaged phone never silently requests screen pinning`() {
        val decision = RestrictionController.evaluate(Request(true), ready.copy(packageAllowlisted = false))
        assertEquals(Action.NONE, decision.action)
        assertEquals(Mode.NONE, decision.observedMode)
    }

    @Test fun `requesting managed lock does not claim it is already active`() {
        val decision = RestrictionController.evaluate(Request(true), ready)
        assertEquals(Action.REQUEST_MANAGED_LOCK, decision.action)
        assertEquals(Mode.NONE, decision.observedMode)
    }

    @Test fun `pinning is not promoted or reported as managed lock`() {
        val decision = RestrictionController.evaluate(Request(true), ready.copy(mode = Mode.PINNED))
        assertEquals(Action.NONE, decision.action)
        assertEquals(Mode.PINNED, decision.observedMode)
    }

    @Test fun `only an owned active task can be released`() {
        for (mode in listOf(Mode.LOCKED, Mode.PINNED)) {
            val decision = RestrictionController.evaluate(Request(false, 42), ready.copy(mode = mode))
            assertEquals(Action.RELEASE_OWNED_LOCK, decision.action)
            assertFalse(decision.releaseNeedsOwner)
        }
    }

    @Test fun `external or lost sessions require managing owner recovery`() {
        for (ownedTask in listOf(null, 41)) {
            val decision = RestrictionController.evaluate(Request(false, ownedTask), ready.copy(mode = Mode.LOCKED))
            assertEquals(Action.NONE, decision.action)
            assertTrue(decision.releaseNeedsOwner)
        }
    }

    @Test fun `background and keyguard defer every action including release`() {
        for (snapshot in listOf(ready.copy(activityResumed = false), ready.copy(deviceLocked = true))) {
            assertEquals(Action.NONE, RestrictionController.evaluate(Request(true), snapshot).action)
            assertEquals(Action.NONE, RestrictionController.evaluate(Request(false, 42), snapshot.copy(mode = Mode.LOCKED)).action)
        }
    }

    @Test fun `unknown platform state cannot start or release a task`() {
        for (required in listOf(true, false)) {
            assertEquals(Action.NONE, RestrictionController.evaluate(Request(required, 42), ready.copy(mode = Mode.UNKNOWN)).action)
        }
    }

    @Test fun `invalid task identity cannot start or establish ownership`() {
        assertEquals(Action.NONE, RestrictionController.evaluate(Request(true), ready.copy(taskId = -1)).action)
        val decision = RestrictionController.evaluate(Request(false, -1), ready.copy(taskId = -1, mode = Mode.LOCKED))
        assertEquals(Action.NONE, decision.action)
        assertTrue(decision.releaseNeedsOwner)
    }

    @Test fun `permission revocation prevents start but not release of own lock`() {
        assertEquals(Action.NONE, RestrictionController.evaluate(Request(true), ready.copy(packageAllowlisted = false)).action)
        assertEquals(Action.RELEASE_OWNED_LOCK, RestrictionController.evaluate(Request(false, 42), ready.copy(mode = Mode.LOCKED, packageAllowlisted = false)).action)
    }

    @Test fun `active lock is not started twice and idle completion does not stop anything`() {
        assertEquals(Action.NONE, RestrictionController.evaluate(Request(true, 42), ready.copy(mode = Mode.LOCKED)).action)
        assertEquals(Action.NONE, RestrictionController.evaluate(Request(false, 42), ready).action)
    }
}

package dev.stanislav.focusdeck.ui.restrictions

import org.junit.Assert.*
import org.junit.Test

class LockOwnershipTest {
    private val own="dev.stanislav.focusdeck"
    @Test fun exactPrivateStartRequestSurvivesProcessDeath() {
        assertTrue(LockOwnership(52,14,own).matches(52,14,own,own))
    }
    @Test fun rebootNewTaskForeignRootAndInvalidRecordsAreNeverOwned() {
        val proof=LockOwnership(52,14,own)
        assertFalse(proof.matches(52,15,own,own))
        assertFalse(proof.matches(53,14,own,own))
        assertFalse(proof.matches(52,14,"other.app",own))
        assertFalse(proof.matches(52,14,null,own))
        assertFalse(LockOwnership(52,-1,own).matches(52,-1,own,own))
        assertFalse(LockOwnership(-1,14,own).matches(-1,14,own,own))
    }
}

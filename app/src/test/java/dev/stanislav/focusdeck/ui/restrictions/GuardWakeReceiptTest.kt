package dev.stanislav.focusdeck.ui.restrictions

import org.junit.Assert.*
import org.junit.Test

class GuardWakeReceiptTest {
    private val success = "WAKE_SCHEDULED; Schedule enabled=true, exactAllowed=true, ownerBAL=true, precision=EXACT_REQUESTED, last=NONE"
    @Test fun receiptRequiresAllIndependentConditions() {
        assertTrue(GuardWakeReceipt.fromResult(0, success).canRelyOnManagedReturn)
        assertFalse(GuardWakeReceipt.fromResult(0, success.replace("ownerBAL=true", "ownerBAL=false")).canRelyOnManagedReturn)
        assertFalse(GuardWakeReceipt.fromResult(0, success.replace("EXACT_REQUESTED", "INEXACT_MAY_BE_DELAYED")).canRelyOnManagedReturn)
        assertFalse(GuardWakeReceipt.fromResult(0, success.replace("exactAllowed=true", "exactAllowed=false")).canRelyOnManagedReturn)
    }
    @Test fun absentRefusedOrMalformedRepliesNeverGrantManagedPause() {
        assertFalse(GuardWakeReceipt.fromResult(-1,null).registered)
        assertFalse(GuardWakeReceipt.fromResult(2,success).registered)
        assertFalse(GuardWakeReceipt.fromResult(0,"REFUSED $success").registered)
        assertFalse(GuardWakeReceipt.fromResult(0,success.replace("ownerBAL=true", "ownerBAL=trueish")).canRelyOnManagedReturn)
    }
}

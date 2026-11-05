package dev.stanislav.focusdeck.ui.nutrition

import org.junit.Assert.*
import org.junit.Test

class WeightReservationTest {
    @Test fun failedOperationRetainsIdentityPayloadAndTimeForRetry() {
        val reserved = WeightDraft(20726L, "74,2", revision = 5, busy = true,
            creationId = -12345L, creationEpochMillis = 987654321L)
        val failed = reserved.complete(reserved, success = false)
        val retry = requireNotNull(failed.beginSave())
        assertEquals(reserved.reservedEntry(), retry.reservedEntry())
        assertEquals("74,2", retry.text)
        assertNull(retry.beginSave())
    }

    @Test fun acknowledgementClearsOnlyMatchingDateAndRevision() {
        val reserved = WeightDraft(20726L, "74.2", revision = 5, busy = true,
            creationId = -12345L, creationEpochMillis = 987654321L)
        val accepted = reserved.complete(reserved, success = true)
        assertEquals("", accepted.text)
        assertNull(accepted.creationId)
        assertNull(accepted.creationEpochMillis)
        val otherDay = reserved.copy(day = 20727L, text = "75")
        assertEquals(otherDay, otherDay.complete(reserved, success = true))
        val newer = reserved.copy(revision = 6, text = "76")
        assertEquals(newer, newer.complete(reserved, success = true))
    }
}

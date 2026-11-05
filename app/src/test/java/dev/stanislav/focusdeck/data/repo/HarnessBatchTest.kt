package dev.stanislav.focusdeck.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessBatchTest {
    @Test fun countLimitAndByteLimitAreBothRespected() {
        val event = "{\"text\":\"тест\"}"
        val batches = harnessBatches(List(257) { event }.joinToString("\n"))
        assertEquals(2, batches.size)
        assertEquals(256, Regex("тест").findAll(batches.first()).count())
        val bigEvent = "{\"text\":\"" + "я".repeat(900) + "\"}"
        val big = harnessBatches(List(100) { bigEvent }.joinToString("\n"))
        assertTrue(big.size > 1)
        assertTrue(big.all { it.toByteArray(Charsets.UTF_8).size <= 128 * 1024 })
    }
    @Test fun receiverRetentionDoesNotLetOldRowsBlockNewRows() {
        val now = java.time.Instant.parse("2026-10-28T12:00:00Z")
        val old = "{\"occurredAt\":\"2026-07-01T12:00:00Z\"}"
        val fresh = "{\"occurredAt\":\"2026-10-28T11:00:00Z\"}"
        val (retained, excluded) = retainedUploadSnapshot("$old\n$fresh\n", now, 90)
        assertEquals(1, excluded)
        assertEquals("$fresh\n", retained)
    }
    @Test fun emptySnapshotProducesNoNetworkBatch() {
        assertEquals(emptyList<String>(), harnessBatches(""))
    }
}

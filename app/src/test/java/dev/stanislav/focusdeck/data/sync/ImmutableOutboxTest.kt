package dev.stanislav.focusdeck.data.sync

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ImmutableOutboxTest {
    private fun entry(id:String=UUID.randomUUID().toString(),category:String="study")=SyncEntry(id,
        "{\"schemaVersion\":1,\"eventId\":\"$id\",\"sessionId\":null,\"occurredAt\":\"2026-10-28T12:00:00Z\",\"source\":\"android\",\"category\":\"$category\",\"type\":\"health\",\"evidence\":\"activity\",\"metrics\":{\"focusSeconds\":null,\"distractionSeconds\":null,\"maintenanceSeconds\":null}}")
    @Test fun restartAndAcknowledgementAreIdempotent() {
        val root=Files.createTempDirectory("telemetry-outbox").toFile()
        try {
            val events=List(300){entry()}
            val first=ImmutableOutbox(root)
            val producer=first.producerId
            assertEquals(300,first.enqueue(events).added)
            assertEquals(2,first.pending().size)
            first.pending().forEach { assertTrue(it.length()<=SYNC_MAX_BYTES);assertTrue(it.readLines().size<=SYNC_MAX_EVENTS);assertEquals(it.name,digest(it.readBytes())+".jsonl") }
            val restarted=ImmutableOutbox(root)
            assertEquals(producer,restarted.producerId)
            assertEquals(0,restarted.enqueue(events).added)
            restarted.pending().forEach(restarted::acknowledge)
            assertEquals(0,ImmutableOutbox(root).enqueue(events).pendingBatches)
        } finally { root.deleteRecursively() }
    }
    @Test fun crashBeforeIndexCommitRecoversDurableBatch() {
        val root=Files.createTempDirectory("telemetry-crash").toFile()
        try {
            val events=List(3){entry()};val box=ImmutableOutbox(root)
            box.enqueue(events)
            java.io.File(root,"seen.tsv").delete()
            assertEquals(0,ImmutableOutbox(root).enqueue(events).added)
            assertEquals(1,box.pending().size)
        } finally { root.deleteRecursively() }
    }
    @Test fun conflictAndPartialBatchNeverSilentlyAcknowledged() {
        val root=Files.createTempDirectory("telemetry-corrupt").toFile()
        try {
            val original=entry();val box=ImmutableOutbox(root);box.enqueue(listOf(original))
            assertThrows(IllegalArgumentException::class.java){box.enqueue(listOf(entry(original.eventId,"work")))}
            val pending=box.pending().single();pending.writeText("partial")
            assertThrows(IllegalArgumentException::class.java){box.pending()}
            assertTrue(pending.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun installationsHaveDifferentProducerDirectories() {
        val root=Files.createTempDirectory("telemetry-producers").toFile()
        try { assertNotEquals(ImmutableOutbox(java.io.File(root,"one")).producerId,ImmutableOutbox(java.io.File(root,"two")).producerId) }
        finally { root.deleteRecursively() }
    }
}

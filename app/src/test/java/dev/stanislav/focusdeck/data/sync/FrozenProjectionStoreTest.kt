package dev.stanislav.focusdeck.data.sync

import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class FrozenProjectionStoreTest {
    private fun entry(id:String,reason:String?=null)=SyncEntry(id,"{\"schemaVersion\":1,\"eventId\":\"$id\",\"sessionId\":null,\"occurredAt\":\"2026-10-28T12:00:00Z\",\"source\":\"android\",\"category\":null,\"type\":\"heartbeat\",\"evidence\":\"unknown\",\"metrics\":{}"+(reason?.let{",\"reason\":\"$it\""} ?: "")+"}")
    @Test fun unknownFreeReasonDoesNotBlockNextHeartbeatAndLaterLearningCannotMutateWire() {
        val root=Files.createTempDirectory("telemetry-projection").toFile()
        try {
            assertNull(TelemetrySnapshotCodec.safeReason("new_reason_with_private_free_text"))
            assertEquals("user_disabled",TelemetrySnapshotCodec.safeReason("user_disabled"))
            val firstId=UUID.randomUUID().toString();val nextId=UUID.randomUUID().toString()
            val index=FrozenProjectionStore(root)
            val first=index.freeze(listOf(entry(firstId),entry(nextId)))
            assertEquals(2,first.size)
            val afterUpdate=FrozenProjectionStore(root).freeze(listOf(entry(firstId,"user_disabled"),entry(nextId)))
            assertEquals(first,afterUpdate)
            val outbox=ImmutableOutbox(java.io.File(root,"queue"))
            assertEquals(2,outbox.enqueue(first).added)
            assertEquals(0,outbox.enqueue(afterUpdate).added)
        } finally {root.deleteRecursively()}
    }
    @Test fun coreMutationIsNotSilentlyAccepted() {
        val root=Files.createTempDirectory("telemetry-core").toFile()
        try {
            val id=UUID.randomUUID().toString();val index=FrozenProjectionStore(root);val original=entry(id)
            index.freeze(listOf(original))
            val changed=SyncEntry(id,original.json.replace("heartbeat","completed"))
            assertThrows(IllegalArgumentException::class.java){index.freeze(listOf(changed))}
            assertEquals(listOf(original),index.all())
        } finally {root.deleteRecursively()}
    }
    @Test fun alternateNamesRemainContentAddressedAndBounded() {
        val hash="a".repeat(64)
        assertEquals(hash,syncedBatchDigest("$hash.jsonl"))
        assertEquals(hash,syncedBatchDigest("$hash.retry1.jsonl"))
        assertNull(syncedBatchDigest("$hash.retry4.jsonl"))
        assertNull(syncedBatchDigest("$hash.part"))
    }
}

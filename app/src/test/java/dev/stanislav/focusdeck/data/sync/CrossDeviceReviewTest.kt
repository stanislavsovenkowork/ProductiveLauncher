package dev.stanislav.focusdeck.data.sync

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CrossDeviceReviewTest {
    private val now=Instant.parse("2026-10-28T12:00:00Z")
    private fun sample(id:String,category:String?="work",start:String="2026-10-28T09:00:00Z",end:String="2026-10-28T10:00:00Z")=
        MetricSample(id,id,category,Instant.parse(end).toEpochMilli(),Instant.parse(start).toEpochMilli(),Instant.parse(end).toEpochMilli(),3600.0,"timer")
    @Test fun devicesAndToolSwitchesNeverDoubleCount() {
        val result=CrossDeviceMath.review(listOf(sample("mac"),sample("phone"),sample("mac")),now)
        assertEquals(3600.0,result.workTimerSeconds!!,0.001)
        assertNull(result.studyTimerSeconds)
    }
    @Test fun unknownConflictingAndFutureRemainUnknownNotZero() {
        assertNull(CrossDeviceMath.review(emptyList(),now).workTimerSeconds)
        val overlap=CrossDeviceMath.review(listOf(sample("mac"),sample("phone","study")),now)
        assertNull(overlap.workTimerSeconds);assertEquals(3600.0,overlap.ambiguousSeconds,0.001)
        val future=sample("future",end="2030-10-28T10:00:00Z")
        val result=CrossDeviceMath.review(listOf(future),now)
        assertEquals(1,result.futureRecords);assertNull(result.workTimerSeconds)
    }
}

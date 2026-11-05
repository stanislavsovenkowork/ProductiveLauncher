package dev.stanislav.focusdeck.data.repo

import dev.stanislav.focusdeck.data.db.FocusBlock
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAuditTest {
    @Test fun auditContainsOnlyPseudonymousContextAndNullDuration() {
        val block = FocusBlock(title = "PRIVATE TITLE", plannedMinutes = 25, startEpochMillis = 1, dayEpochDay = 1,
            category = "study", sessionId = "d6457bfa-352c-4da6-a271-f1f9c862c055", taskStableId = "qa-task")
        val line = auditEventLine("launcher_attempt_denied", block,
            UUID.fromString("62e1d8bc-c477-4da4-8b2f-28b17980c929"), Instant.parse("2026-10-28T12:00:00Z"))
        assertTrue(line.contains("\"sessionId\":\"d6457bfa-352c-4da6-a271-f1f9c862c055\""))
        assertTrue(line.contains("\"category\":\"study\""))
        assertTrue(line.contains("\"distractionSeconds\":null"))
        assertTrue(line.contains("\"taskRef\":\"qa-task\""))
        assertFalse(line.contains("PRIVATE TITLE"))
        System.getenv("FOCUSDECK_AUDIT_FIXTURE_PATH")?.let { File(it).writeText(line) }
    }
    @Test fun windowAuditFixtureUsesV1HealthAndPseudonymousId() {
        val window = "d6457bfa-352c-4da6-a271-f1f9c862c055"
        val lines = listOf("bounded_relief_started", "rest_without_feeds", "rest_returned", "window_started").map {
            auditEventLine(it, null, UUID.randomUUID(), Instant.parse("2026-10-28T12:00:00Z"), window)
        }
        assertTrue(lines.all { it.contains("\"protectionId\":\"$window\"") && it.contains("\"type\":\"health\"") })
        assertTrue(lines.last().contains("\"evidence\":\"unknown\""))
        System.getenv("FOCUSDECK_V2_AUDIT_FIXTURE_PATH")?.let { File(it).writeText(lines.joinToString("")) }
    }
    @Test fun outsideSessionIsNotAttributedToAWorkCategory() {
        val line = auditEventLine("ritual_optout", null, UUID.randomUUID(), Instant.parse("2026-10-28T12:00:00Z"))
        assertTrue(line.contains("\"sessionId\":null"))
        assertTrue(line.contains("\"category\":null"))
        assertTrue(line.contains("\"type\":\"disabled\""))
    }
}

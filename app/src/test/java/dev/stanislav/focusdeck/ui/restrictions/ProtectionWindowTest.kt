package dev.stanislav.focusdeck.ui.restrictions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtectionWindowTest {
    private val start = ProtectionClock(1_000_000, 100_000, 7)
    private val base = ProtectionWindow("synthetic", "study", setOf("qa.probe"), null, null, start.wall,
        start.wall + PROTECTION_MILLIS, start.elapsed, start.elapsed + PROTECTION_MILLIS, start.boot)

    @Test fun strictSchedulingRequiresExplicitExperimentalTestContext() {
        assertTrue(strictSchedulingAllowed(false, false))
        assertFalse(strictSchedulingAllowed(true, false))
        assertTrue(strictSchedulingAllowed(true, true))
    }

    @Test fun protectionOutlivesWorkTimerAndSnapshotIgnoresFutureChanges() {
        val now = start.copy(elapsed = start.elapsed + 10_000, wall = start.wall + 10_000)
        assertTrue(protectionRestricted(base, "qa.probe", now))
        assertFalse(protectionRestricted(base, "other", now))
        assertEquals(PROTECTION_MILLIS - 10_000, remaining(base, now))
        assertFalse(protectionRestricted(base, "qa.probe", now.copy(elapsed = base.endElapsed)))
    }
    @Test fun reliefExtendsDeadlineOnceAndNeverBeyondThirtyMinutes() {
        val first = requireNotNull(grantRelief(base, start))
        assertEquals(start.elapsed + MAX_PROTECTION_MILLIS, first.endElapsed)
        assertEquals(start.wall + MAX_PROTECTION_MILLIS, first.endWall)
        assertEquals(null, grantRelief(first, start))
    }
    @Test fun restKeepsRestrictionsAndConsumesTheSameSingleExtension() {
        val rest = requireNotNull(grantRest(base, start))
        assertTrue(protectionRestricted(rest, "qa.probe", start))
        assertEquals(start.elapsed + MAX_PROTECTION_MILLIS, rest.endElapsed)
        assertEquals(null, grantRelief(rest, start))
        assertEquals(null, grantRest(rest, start))
        assertFalse(protectionRestricted(rest, "qa.probe", start.copy(elapsed = rest.endElapsed)))
    }
    @Test fun reliefExpiresWithoutResumingWork() {
        val paused = base.copy(reliefWall = start.wall + RELIEF_MILLIS, reliefElapsed = start.elapsed + RELIEF_MILLIS,
            reliefBoot = 7, reliefUsed = true)
        assertFalse(protectionRestricted(paused, "qa.probe", start))
        assertTrue(protectionRestricted(paused, "qa.probe", start.copy(elapsed = start.elapsed + RELIEF_MILLIS)))
        assertTrue(paused.reliefUsed)
    }
    @Test fun rebootAndBackwardsClockNeverCreateIndefiniteReliefOrExit() {
        val paused = base.copy(reliefWall = start.wall + RELIEF_MILLIS, reliefElapsed = start.elapsed + RELIEF_MILLIS,
            reliefBoot = 7, reliefUsed = true, exitWall = start.wall + EXIT_COOLDOWN_MILLIS,
            exitElapsed = start.elapsed + EXIT_COOLDOWN_MILLIS, exitBoot = 7)
        val reboot = start.copy(boot = 8, elapsed = 50_000)
        assertEquals(0, reliefRemaining(paused, reboot))
        assertEquals(0, exitRemaining(paused, reboot))
        assertEquals(0, remaining(base, reboot.copy(wall = start.wall - 1)))
    }
    @Test fun exitRequiresPrecommittedCooldownDuringSameBoot() {
        val requested = base.copy(exitWall = start.wall + EXIT_COOLDOWN_MILLIS,
            exitElapsed = start.elapsed + EXIT_COOLDOWN_MILLIS, exitBoot = 7)
        assertEquals(EXIT_COOLDOWN_MILLIS, exitRemaining(requested, start))
        assertEquals(0, exitRemaining(requested, start.copy(elapsed = start.elapsed + EXIT_COOLDOWN_MILLIS)))
    }

    @Test fun strictModeRestrictsOnlyChosenFixtureAcrossReliefReturnAndExpiry() {
        val strict = base.copy(policyVersion = 2, packages = setOf("qa.browser", "qa.feed"))
        assertTrue(protectionRestricted(strict, "qa.browser", start))
        assertTrue(protectionRestricted(strict, "qa.feed", start))
        assertFalse(protectionRestricted(strict, "qa.materials", start))
        val relief = requireNotNull(grantRelief(strict, start))
        assertFalse(protectionRestricted(relief, "qa.browser", start))
        val earlyReturn = relief.copy(reliefWall = start.wall + 10_000, reliefElapsed = start.elapsed + 10_000, reliefEndedEarly = true)
        val afterReturn = start.copy(wall = start.wall + 10_000, elapsed = start.elapsed + 10_000)
        assertTrue(protectionRestricted(earlyReturn, "qa.browser", afterReturn))
        val expired = start.copy(wall = strict.endWall, elapsed = strict.endElapsed)
        assertFalse(protectionRestricted(earlyReturn, "qa.browser", expired))
        assertFalse(protectionRestricted(null, "qa.browser", afterReturn))
    }
    @Test fun scheduledWindowKeepsFixedEndAndBudgetAcrossReliefAndRest() {
        val scheduled = base.copy(policyVersion = 2, startWall = start.wall + 10_000,
            endWall = start.wall + 70_000, startElapsed = start.elapsed + 10_000, endElapsed = start.elapsed + 70_000)
        assertFalse(protectionRestricted(scheduled, "qa.probe", start))
        val active = start.copy(wall = start.wall + 10_000, elapsed = start.elapsed + 10_000)
        assertTrue(protectionRestricted(scheduled, "qa.probe", active))
        val rest = requireNotNull(grantRest(scheduled, active))
        assertTrue(protectionRestricted(rest, "qa.probe", active))
        assertFalse(rest.reliefUsed)
        val paused = requireNotNull(grantRelief(rest.copy(resting = false), active))
        assertEquals(scheduled.endWall, paused.endWall)
        assertEquals(scheduled.endWall, paused.reliefWall)
        assertFalse(protectionRestricted(paused, "qa.probe", active))
        assertEquals(null, grantRelief(paused, active))
        assertFalse(protectionRestricted(paused, "qa.probe", active.copy(wall = scheduled.endWall, elapsed = scheduled.endElapsed)))
    }
    @Test fun scheduledExitWaitSurvivesRestartAndExpiryWins() {
        val requested = base.copy(policyVersion = 2, exitDelayMillis = 60_000,
            exitWall = start.wall + 60_000, exitElapsed = start.elapsed + 60_000, exitBoot = start.boot)
        val restarted = start.copy(boot = 8, elapsed = 100, wall = start.wall + 10_000)
        assertEquals(60_000, exitRemaining(requested, restarted))
        assertTrue(exitClockUncertain(requested, restarted))
        val reanchored = requested.copy(exitBoot = 8, exitElapsed = 60_100, exitWall = restarted.wall + 60_000, exitReanchored = true)
        assertEquals(50_000, exitRemaining(reanchored, restarted.copy(elapsed = 10_100)))
        assertEquals(0, exitRemaining(reanchored, restarted.copy(elapsed = 60_100)))
        assertFalse(windowActive(requested, restarted.copy(wall = requested.endWall)))
    }
    @Test fun strictWindowKeepsRestrictionsDuringRestAndRequiresAnHourUnlessItEnds() {
        val strict = base.copy(policyVersion = 2, strict = true, endWall = start.wall + 7_200_000,
            endElapsed = start.elapsed + 7_200_000, exitDelayMillis = STRICT_EXIT_DELAY_MILLIS,
            exitWall = start.wall + STRICT_EXIT_DELAY_MILLIS, exitElapsed = start.elapsed + STRICT_EXIT_DELAY_MILLIS, exitBoot = start.boot)
        assertEquals(null, grantRelief(strict, start))
        val resting = requireNotNull(grantRest(strict, start))
        assertTrue(protectionRestricted(resting, "qa.probe", start))
        val almost = start.copy(wall = start.wall + 3_599_000, elapsed = start.elapsed + 3_599_000)
        assertEquals(1_000L, exitRemaining(strict, almost))
        assertEquals(0L, exitRemaining(strict, almost.copy(elapsed = start.elapsed + 3_600_000)))
        assertEquals(STRICT_EXIT_DELAY_MILLIS, exitRemaining(strict, start.copy(boot = 8, elapsed = 10)))
        val shorter = strict.copy(endWall = start.wall + 900_000, endElapsed = start.elapsed + 900_000)
        assertFalse(windowActive(shorter, start.copy(wall = shorter.endWall, elapsed = shorter.endElapsed)))
    }
    @Test fun strictStartedWindowSurvivesRebootAndUntrustedClockWithBoundedRecovery() {
        val original = base.copy(policyVersion = 2, strict = true, strictStarted = true,
            endWall = start.wall + 7_200_000, endElapsed = start.elapsed + 7_200_000,
            exitDelayMillis = STRICT_EXIT_DELAY_MILLIS)
        assertEquals(null, recoverStrictWindow(original, start, "process-a"))
        for (wall in listOf(start.wall - 86_400_000, original.endWall + 86_400_000)) {
            val reboot = ProtectionClock(wall, 1_000, 8)
            val recovered = requireNotNull(recoverStrictWindow(original, reboot, "process-b"))
            assertTrue(recovered.strictRecovery)
            assertTrue(windowActive(recovered, reboot))
            assertEquals(7_200_000L, remaining(recovered, reboot))
            assertEquals(original.endWall, recovered.endWall)
            assertEquals(null, recoverStrictWindow(recovered, reboot, "process-c"))
            val end = reboot.copy(elapsed = reboot.elapsed + 7_200_000)
            assertFalse(windowActive(recovered, end))
        }
        val unknown = ProtectionClock(original.endWall + 1, 5_000, -1)
        val first = requireNotNull(recoverStrictWindow(original, unknown, "process-a"))
        assertTrue(windowActive(first, unknown))
        assertEquals(null, recoverStrictWindow(first, unknown, "process-a"))
        assertTrue(requireNotNull(recoverStrictWindow(first, unknown.copy(elapsed = 7_000), "process-b")).strictRecovery)
        assertEquals(null, recoverStrictWindow(original.copy(strictStarted = false), unknown, "process-a"))
        assertEquals(null, recoverStrictWindow(original.copy(strict = false), unknown, "process-a"))
    }
    @Test fun wallClockJumpsDoNotShortenOrLengthenExitWaitOnSameBoot() {
        val requested = base.copy(policyVersion = 2, exitDelayMillis = 60_000,
            exitWall = start.wall + 60_000, exitElapsed = start.elapsed + 60_000, exitBoot = start.boot)
        for (shift in listOf(-300_000L, 300_000L)) {
            val jumped = start.copy(wall = start.wall + shift, elapsed = start.elapsed + 10_000)
            assertEquals(50_000, exitRemaining(requested, jumped))
            assertTrue(exitClockUncertain(requested, jumped))
        }
    }
    @Test fun scheduledProtectionAndReliefIgnoreWallClockJumpsOnSameBoot() {
        val scheduled = base.copy(policyVersion = 2, endWall = start.wall + 600_000, endElapsed = start.elapsed + 600_000)
        val paused = requireNotNull(grantRelief(scheduled, start))
        for (shift in listOf(-900_000L, 900_000L)) {
            val jumped = start.copy(wall = start.wall + shift, elapsed = start.elapsed + 10_000)
            assertEquals(590_000, remaining(scheduled, jumped))
            assertEquals(290_000, reliefRemaining(paused, jumped))
            assertTrue(windowActive(scheduled, jumped))
            assertTrue(windowClockUncertain(scheduled, jumped))
        }
        val reboot = start.copy(boot = 8, elapsed = 10, wall = start.wall - 900_000)
        assertTrue(windowClockUncertain(scheduled, reboot))
        assertEquals(600_000, remaining(scheduled, reboot))
        assertEquals(300_000, reliefRemaining(paused, reboot))
    }
    @Test fun disablingFutureScheduleDoesNotTreatActiveWindowAsFutureAfterWallClockMovesBack() {
        val scheduled = base.copy(policyVersion = 2, strict = true, startWall = start.wall + 10_000,
            startElapsed = start.elapsed + 10_000, endWall = start.wall + 120_000, endElapsed = start.elapsed + 120_000)
        assertTrue(scheduledWindowIsFuture(scheduled, start))
        val activeWithClockMovedBack = start.copy(wall = start.wall - 60_000, elapsed = start.elapsed + 20_000)
        assertTrue(windowActive(scheduled, activeWithClockMovedBack))
        assertFalse(scheduledWindowIsFuture(scheduled, activeWithClockMovedBack))
    }
    @Test fun unknownBootMonotonicAnchorHasBoundedDelay() {
        val unknown = start.copy(boot = -1)
        val requested = base.copy(policyVersion = 2, exitDelayMillis = 60_000, exitBoot = -2,
            exitProcessToken = "synthetic", exitWall = unknown.wall + 60_000, exitElapsed = unknown.elapsed + 60_000)
        assertEquals(50_000, exitRemaining(requested, unknown.copy(elapsed = unknown.elapsed + 10_000, wall = unknown.wall + 300_000)))
        assertEquals(0, exitRemaining(requested, unknown.copy(elapsed = unknown.elapsed + 60_000)))
        assertTrue(exitClockUncertain(requested, unknown))
    }
    @Test fun scheduledReliefSurvivesRestartButCannotExceedGrant() {
        val scheduled = base.copy(policyVersion = 2, endWall = start.wall + 600_000)
        val paused = requireNotNull(grantRelief(scheduled, start))
        val restarted = start.copy(boot = 8, elapsed = 100, wall = start.wall + 60_000)
        assertEquals(240_000, reliefRemaining(paused, restarted))
        assertTrue(paused.reliefUsed)
    }

    @Test fun crossPlatformBoundaryFixtureUsesRealPolicy() {
        fun clockAt(seconds: Long) = start.copy(wall = start.wall + seconds * 1_000, elapsed = start.elapsed + seconds * 1_000)
        val paused = requireNotNull(grantRelief(base, clockAt(600)))
        val points = listOf(599L, 600L, 899L, 900L, 1500L, 1799L, 1800L)
        val states = points.associateWith { protectionRestricted(if (it < 600) base else paused, "qa.probe", clockAt(it)) }
        assertEquals(listOf(true, false, false, true, true, true, false), points.map { states.getValue(it) })
        val expiry = (paused.endElapsed - start.elapsed) / 1_000
        val secondPause = grantRelief(paused, clockAt(900)) != null
        assertEquals(1800, expiry)
        assertFalse(secondPause)
        System.getenv("FOCUSDECK_WINDOW_FIXTURE_PATH")?.let { path ->
            val boundaries = states.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
            File(path).writeText("{\"schemaVersion\":1,\"baseWorkSeconds\":${PROTECTION_MILLIS / 1000}," +
                "\"pauseSeconds\":${RELIEF_MILLIS / 1000},\"maxProtectionSeconds\":${MAX_PROTECTION_MILLIS / 1000}," +
                "\"expiryFromStartSeconds\":$expiry,\"secondPauseAccepted\":$secondPause,\"restrictedAt\":{$boundaries}}\n")
        }
    }
}

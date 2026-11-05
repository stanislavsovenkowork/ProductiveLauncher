package dev.stanislav.focusdeck.guard;

import org.junit.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static dev.stanislav.focusdeck.guard.WakeScheduler.*;
import static org.junit.Assert.*;

public class WakeSchedulerTest {
    private final Fake fake = new Fake();
    private final WakeScheduler scheduler = new WakeScheduler(fake, fake);
    private static long time(String text) { return Instant.parse(text).toEpochMilli(); }
    private static final class Fake implements Port, Store {
        State state = State.empty();
        long now = time("2026-09-29T19:59:00Z"); // Minsk 22:59
        ZoneId zone = ZoneId.of("Europe/Minsk");
        boolean active = true, authority = true, exact = true, unlocked = true, failWrite, failArm, revokeExactOnArm;
        int launches;
        final EnumMap<Kind, Long> armed = new EnumMap<>(Kind.class);
        final List<String> notes = new ArrayList<>();
        @Override public State read() { return state; }
        @Override public void write(State value) {
            if (failWrite) throw new IllegalStateException();
            state = value;
        }
        @Override public long now() { return now; }
        @Override public ZoneId zone() { return zone; }
        @Override public boolean activePolicy() { return active; }
        @Override public boolean hasBackgroundLaunchAuthority() { return authority; }
        @Override public boolean exactAlarmsAvailable() { return exact; }
        @Override public boolean unlockedForegroundUser() { return unlocked; }
        @Override public void arm(Kind kind, long at, long generation) {
            if (kind == Kind.WAKE && revokeExactOnArm) {
                exact = false;
                throw new SecurityException("Exact access revoked during registration");
            }
            if (failArm) throw new IllegalStateException();
            armed.put(kind, at);
        }
        @Override public void cancel(Kind kind) { armed.remove(kind); }
        @Override public void launchFixedTarget() { launches++; }
        @Override public void note(String diagnostic) { notes.add(diagnostic); }
    }
    private void fireDaily() {
        long generation = fake.state.generation(), due = fake.state.dailyAt();
        fake.now = due;
        scheduler.fire(Kind.DAILY, generation, due);
    }

    @Test public void dormantBootTimeChangesDoNotArmOrLaunch() {
        scheduler.reschedule();
        assertFalse(fake.state.enabled());
        assertTrue(fake.armed.isEmpty());
        assertEquals(0, fake.launches);
    }
    @Test public void activationNeedsActivePolicyAndNeverImmediatelyLaunches() {
        fake.active = false;
        assertThrows(IllegalStateException.class, scheduler::activate);
        fake.active = true;
        scheduler.activate();
        assertEquals(time("2026-09-29T20:00:00Z"), fake.state.dailyAt());
        assertEquals(1, fake.armed.size());
        assertEquals(0, fake.launches);
    }
    @Test public void local2300IsRecomputedAcrossDstRatherThanAdding24Hours() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        long before = time("2026-10-24T21:00:00Z"); // 23:00 before fall-back.
        assertEquals(time("2026-10-25T22:00:00Z"), nextDaily(before, berlin, Long.MIN_VALUE));
    }
    @Test public void dailyLaunchIsConsumedOnceAndNextDayArmed() {
        scheduler.activate();
        long due = fake.state.dailyAt(), generation = fake.state.generation();
        fireDaily();
        scheduler.fire(Kind.DAILY, generation, due);
        assertEquals(1, fake.launches);
        assertEquals(time("2026-09-30T20:00:00Z"), fake.state.dailyAt());
    }
    @Test public void lockedScreenOrBackgroundUserIsSkippedWithoutPolling() {
        scheduler.activate();
        fake.unlocked = false;
        fireDaily();
        assertEquals(0, fake.launches);
        assertEquals(List.of("LOCKED_OR_BACKGROUND_USER_SKIPPED"), fake.notes);
        assertEquals(1, fake.armed.size());
    }
    @Test public void unaffiliatedProfileOwnerDoesNotPretendToHaveBalAuthority() {
        scheduler.activate();
        fake.authority = false;
        fireDaily();
        assertEquals(0, fake.launches);
        assertEquals(List.of("BAL_UNSUPPORTED_OWNER"), fake.notes);
    }
    @Test public void revokedPolicyCancelsBothSlotsEvenForAlreadyDeliveredBroadcast() {
        scheduler.activate();
        scheduler.scheduleWake(fake.now + MAX_WAKE_DELAY, Reason.EMERGENCY);
        fake.active = false;
        fireDaily();
        assertTrue(fake.armed.isEmpty());
        assertFalse(fake.state.enabled());
        assertEquals(0, fake.launches);
    }
    @Test public void recoveryCancelsAllAndStaleBroadcastCannotLaunch() {
        scheduler.activate();
        long due = fake.state.dailyAt(), generation = fake.state.generation();
        scheduler.scheduleWake(fake.now + MAX_WAKE_DELAY, Reason.EMERGENCY);
        scheduler.cancel();
        fake.now = due;
        scheduler.fire(Kind.DAILY, generation, due);
        assertTrue(fake.armed.isEmpty());
        assertEquals(0, fake.launches);
    }
    @Test public void diskFailureStillAttemptsBothAlarmCancellations() {
        scheduler.activate();
        scheduler.scheduleWake(fake.now + MAX_WAKE_DELAY, Reason.EMERGENCY);
        fake.failWrite = true;
        assertThrows(IllegalStateException.class, scheduler::cancel);
        assertTrue(fake.armed.isEmpty());
    }
    @Test public void bootAfter23SchedulesTomorrowWithoutSurpriseCatchUp() {
        scheduler.activate();
        fake.now = time("2026-09-29T21:00:00Z");
        scheduler.reschedule();
        assertEquals(time("2026-09-30T20:00:00Z"), fake.state.dailyAt());
        assertEquals(0, fake.launches);
    }
    @Test public void timezoneChangeInvalidatesOldBroadcastAndUsesNewLocal23() {
        scheduler.activate();
        long oldGeneration = fake.state.generation(), oldDue = fake.state.dailyAt();
        fake.zone = ZoneId.of("UTC");
        scheduler.reschedule();
        assertEquals(time("2026-09-29T23:00:00Z"), fake.state.dailyAt());
        fake.now = oldDue;
        scheduler.fire(Kind.DAILY, oldGeneration, oldDue);
        assertEquals(0, fake.launches);
    }
    @Test public void clockBackDoesNotReplayAnAlreadyConsumedRitualDate() {
        scheduler.activate();
        fireDaily();
        fake.now = time("2026-09-29T19:00:00Z");
        scheduler.reschedule();
        assertEquals(time("2026-09-30T20:00:00Z"), fake.state.dailyAt());
    }
    @Test public void wakeRequiresEnabledActiveStateAndStrictFuture15MinuteBound() {
        assertFalse(scheduler.scheduleWake(fake.now + MAX_WAKE_DELAY, Reason.EMERGENCY));
        scheduler.activate();
        assertFalse(scheduler.scheduleWake(Long.MAX_VALUE, Reason.EMERGENCY));
        assertFalse(scheduler.scheduleWake(fake.now, Reason.EMERGENCY));
        assertFalse(scheduler.scheduleWake(fake.now + MAX_WAKE_DELAY + 1, Reason.EMERGENCY));
        assertTrue(scheduler.scheduleWake(fake.now + MAX_WAKE_DELAY, Reason.EMERGENCY));
        fake.active = false;
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
    }
    @Test public void wakeCanMoveEarlierButNeverExtendPendingPause() {
        scheduler.activate();
        long due = fake.now + 10 * MINUTE;
        assertTrue(scheduler.scheduleWake(due, Reason.EMERGENCY));
        assertTrue(scheduler.scheduleWake(due, Reason.EMERGENCY));
        assertFalse(scheduler.scheduleWake(due + MINUTE, Reason.EMERGENCY));
        assertTrue(scheduler.scheduleWake(due - MINUTE, Reason.EMERGENCY));
        assertEquals(due - MINUTE, fake.state.wakeAt());
        assertEquals(2, fake.armed.size());
    }
    @Test public void consumedWakeCannotCreateMinuteByMinuteLaunchLoop() {
        fake.now = time("2026-09-29T20:00:00Z");
        scheduler.activate();
        long due = fake.now + MINUTE;
        scheduler.scheduleWake(due, Reason.EMERGENCY);
        fake.now = due;
        scheduler.fire(Kind.WAKE, fake.state.generation(), due);
        assertEquals(1, fake.launches);
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        fake.now += 15 * MINUTE;
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        fake.now = time("2026-09-30T03:00:00Z"); // Next morning window, Minsk 06:00.
        assertTrue(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
    }
    @Test public void staleWakeIsDroppedOnBootAndNeverLaunchedFromReschedule() {
        scheduler.activate();
        scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY);
        fake.now += 2 * MINUTE;
        scheduler.reschedule();
        assertEquals(0, fake.state.wakeAt());
        assertFalse(fake.armed.containsKey(Kind.WAKE));
        assertEquals(0, fake.launches);
    }
    @Test public void futureWakeSurvivesRecreationButOldGenerationCannotFire() {
        scheduler.activate();
        scheduler.scheduleWake(fake.now + 10 * MINUTE, Reason.EMERGENCY);
        long oldGeneration = fake.state.generation();
        WakeScheduler recreated = new WakeScheduler(fake, fake);
        recreated.reschedule();
        long due = fake.state.wakeAt();
        fake.now = due;
        recreated.fire(Kind.WAKE, oldGeneration, due);
        assertEquals(0, fake.launches);
        recreated.fire(Kind.WAKE, fake.state.generation(), due);
        assertEquals(1, fake.launches);
    }
    @Test public void failedWakeRegistrationDoesNotAcknowledgeOrRetainPhantomRequest() {
        scheduler.activate();
        State before = fake.state;
        fake.failArm = true;
        assertThrows(IllegalStateException.class, () -> scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        assertEquals(before, fake.state);
    }
    @Test public void storageFailureBeforeConsumptionDoesNotLaunch() {
        scheduler.activate();
        fake.failWrite = true;
        assertThrows(IllegalStateException.class, this::fireDaily);
        assertEquals(0, fake.launches);
    }
    @Test public void veryLateDailyDeliveryDoesNotOpenLauncherDuringNextDay() {
        scheduler.activate();
        long due = fake.state.dailyAt();
        fake.now = due + 8 * 60 * MINUTE;
        scheduler.fire(Kind.DAILY, fake.state.generation(), due);
        assertEquals(0, fake.launches);
        assertEquals(List.of("STALE_ALARM_SKIPPED"), fake.notes);
    }

    @Test public void graceThenSeparateEmergencyAcceptedAndBothBudgetsPersist() {
        fake.now = time("2026-09-29T20:00:00Z"); // 23:00 Minsk
        scheduler.activate();
        long grace = fake.now + 5 * MINUTE;
        assertTrue(scheduler.scheduleWake(grace, Reason.FOCUS_GRACE));
        assertTrue(new WakeScheduler(fake, fake).scheduleWake(grace, Reason.FOCUS_GRACE));
        assertFalse(scheduler.scheduleWake(grace, Reason.EMERGENCY));
        fake.now = grace;
        scheduler.fire(Kind.WAKE, fake.state.generation(), grace);
        fake.now += MINUTE; // 23:06 -> 23:21
        WakeScheduler cold = new WakeScheduler(fake, fake);
        long emergency = fake.now + 15 * MINUTE;
        assertFalse(cold.scheduleWake(fake.now + MINUTE, Reason.FOCUS_GRACE));
        assertTrue(cold.scheduleWake(emergency, Reason.EMERGENCY));
        assertTrue(cold.scheduleWake(emergency, Reason.EMERGENCY));
        assertFalse(cold.scheduleWake(emergency + 1, Reason.EMERGENCY));
        fake.now = emergency;
        cold.fire(Kind.WAKE, fake.state.generation(), emergency);
        assertFalse(cold.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        cold.reschedule();
        assertFalse(new WakeScheduler(fake, fake).scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        cold.cancel();
        assertTrue(fake.armed.isEmpty());
        assertFalse(fake.state.enabled());
        cold.fire(Kind.WAKE, fake.state.generation(), emergency);
        assertTrue(fake.armed.isEmpty());
    }
    @Test public void graceOnlyInsideLocalWindowAndNeverBeyond2315() {
        scheduler.activate(); // 22:59
        assertFalse(scheduler.scheduleWake(fake.now + 5 * MINUTE, Reason.FOCUS_GRACE));
        fake.now = time("2026-09-29T20:00:00Z");
        assertFalse(scheduler.scheduleWake(fake.now + 15 * MINUTE + 1, Reason.FOCUS_GRACE));
        assertTrue(scheduler.scheduleWake(fake.now + 15 * MINUTE, Reason.FOCUS_GRACE));
        assertTrue(scheduler.scheduleWake(fake.now + 5 * MINUTE, Reason.FOCUS_GRACE));
        assertFalse(scheduler.scheduleWake(fake.now + 6 * MINUTE, Reason.FOCUS_GRACE));
        fake.now = time("2026-09-29T20:15:00Z");
        assertFalse(scheduler.scheduleWake(fake.now + 1, Reason.FOCUS_GRACE));
        fake.now = time("2026-09-30T20:00:00Z");
        assertTrue(scheduler.scheduleWake(fake.now + MINUTE, Reason.FOCUS_GRACE));
    }
    @Test public void emergencyWindowsUsePreviousEveningUntilSixAndCurrentEveningFrom23() {
        ZoneId zone = ZoneId.of("Europe/Minsk");
        long evening = time("2026-09-29T20:00:00Z");
        assertEquals(ritualWindow(evening, zone), ritualWindow(time("2026-09-30T02:59:59Z"), zone));
        assertEquals(ritualWindow(evening, zone) + 1, ritualWindow(time("2026-09-30T03:00:00Z"), zone));
        assertEquals(ritualWindow(evening, zone) + 1, ritualWindow(time("2026-09-30T19:59:59Z"), zone));
        assertEquals(ritualWindow(evening, zone) + 2, ritualWindow(time("2026-09-30T20:00:00Z"), zone));
    }
    @Test public void invalidOrMissingReasonDoesNotConsumeBudget() {
        scheduler.activate();
        State before = fake.state;
        assertNull(parseReason(null));
        assertNull(parseReason("emergency"));
        assertNull(parseReason("LEGACY"));
        assertNull(parseReason("EMERGENCY "));
        assertEquals(Reason.EMERGENCY, parseReason("EMERGENCY"));
        assertEquals(Reason.FOCUS_GRACE, parseReason("FOCUS_GRACE"));
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, null));
        assertEquals(before, fake.state);
    }
    @Test public void migrationRetainsPendingIdentityAndReservesBothBudgetsAcrossBoundary() {
        long requested = time("2026-09-29T19:59:00Z");
        long wake = time("2026-09-29T20:05:00Z");
        fake.state = migrateV1(true, 7, requested + MINUTE, wake, Long.MIN_VALUE,
                requested, 0, fake.zone);
        assertEquals(7, fake.state.generation());
        assertEquals(wake, fake.state.wakeAt());
        assertEquals("LEGACY", fake.state.wakeReason());
        fake.now = requested;
        assertFalse(scheduler.scheduleWake(wake, Reason.EMERGENCY));
        fake.now = wake;
        scheduler.fire(Kind.WAKE, 7, wake);
        assertEquals(1, fake.launches);
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.FOCUS_GRACE));
        fake.now = time("2026-09-29T20:16:00Z");
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        fake.now = time("2026-09-30T03:00:00Z");
        assertTrue(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
    }
    @Test public void migrationConsumedWakeKeepsCooldownAndDormantMigrationDoesNotArm() {
        long requested = time("2026-09-29T19:59:00Z");
        fake.state = migrateV1(true, 2, requested + MINUTE, 0, Long.MIN_VALUE,
                requested, requested + 1000, fake.zone);
        fake.now = time("2026-09-29T20:01:00Z"); // New semantic window, legacy cooldown still applies.
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        fake.now = requested + MAX_WAKE_DELAY;
        assertTrue(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        fake.state = migrateV1(false, 0, 0, 0, Long.MIN_VALUE, 0, 0, fake.zone);
        scheduler.reschedule();
        assertTrue(fake.armed.isEmpty());
        assertFalse(fake.state.enabled());
    }
    @Test public void clockRollbackDoesNotReopenConsumedReasonBudgets() {
        fake.now = time("2026-09-29T20:00:00Z");
        scheduler.activate();
        long due = fake.now + MINUTE;
        assertTrue(scheduler.scheduleWake(due, Reason.EMERGENCY));
        fake.now = due;
        scheduler.fire(Kind.WAKE, fake.state.generation(), due);
        fake.now = time("2026-09-29T12:00:00Z");
        scheduler.reschedule();
        assertFalse(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
    }

    @Test public void missingReturnPrerequisitesRejectBothReasonsWithoutSpendingBudgets() {
        fake.now = time("2026-09-29T20:00:00Z");
        scheduler.activate();
        State before = fake.state;
        for (Reason reason : Reason.values()) {
            fake.authority = false;
            fake.exact = true;
            assertFalse(scheduler.scheduleWake(fake.now + MINUTE, reason));
            assertEquals(before, fake.state);
            fake.authority = true;
            fake.exact = false;
            assertFalse(scheduler.scheduleWake(fake.now + MINUTE, reason));
            assertEquals(before, fake.state);
        }
        assertFalse(fake.armed.containsKey(Kind.WAKE));
        fake.exact = true;
        assertTrue(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
    }
    @Test public void exactAccessRevokedDuringRegistrationRestoresUnusedBudget() {
        scheduler.activate();
        State before = fake.state;
        fake.revokeExactOnArm = true;
        assertThrows(SecurityException.class, () -> scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
        assertEquals(before, fake.state);
        assertFalse(fake.armed.containsKey(Kind.WAKE));
        fake.revokeExactOnArm = false;
        fake.exact = true;
        assertTrue(scheduler.scheduleWake(fake.now + MINUTE, Reason.EMERGENCY));
    }
    @Test public void declinedIdempotentRetryPreservesExistingPendingRecord() {
        scheduler.activate();
        long due = fake.now + MINUTE;
        assertTrue(scheduler.scheduleWake(due, Reason.EMERGENCY));
        State before = fake.state;
        fake.authority = false;
        assertFalse(scheduler.scheduleWake(due, Reason.EMERGENCY));
        assertEquals(before, fake.state);
    }
    @Test public void dailyRemainsAvailableWithoutExactPermission() {
        fake.exact = false;
        scheduler.activate();
        assertTrue(fake.armed.containsKey(Kind.DAILY));
        fireDaily();
        assertEquals(1, fake.launches);
    }
}

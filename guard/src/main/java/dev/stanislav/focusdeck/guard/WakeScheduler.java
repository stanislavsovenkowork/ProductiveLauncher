package dev.stanislav.focusdeck.guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Two alarm slots, no polling, no vault input. Persist consumption before requesting a launch. */
final class WakeScheduler {
    static final long MINUTE = 60_000L;
    static final long MAX_WAKE_DELAY = 15 * MINUTE;
    enum Kind { DAILY, WAKE }
    enum Reason { FOCUS_GRACE, EMERGENCY }
    static Reason parseReason(String value) {
        if ("FOCUS_GRACE".equals(value)) return Reason.FOCUS_GRACE;
        if ("EMERGENCY".equals(value)) return Reason.EMERGENCY;
        return null;
    }
    static long localDay(long at, ZoneId zone) {
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay();
    }
    // Monotonically ordered local windows: morning(day)*2, evening(day)*2+1.
    static long ritualWindow(long at, ZoneId zone) {
        var local = Instant.ofEpochMilli(at).atZone(zone);
        long day = local.toLocalDate().toEpochDay();
        return local.getHour() < 6 ? (day - 1) * 2 + 1
                : local.getHour() < 23 ? day * 2 : day * 2 + 1;
    }
    record State(boolean enabled, long generation, long dailyAt, long wakeAt,
                 long lastDailyDay, long lastWakeRequest, long lastAttempt,
                 String wakeReason, long graceDay, long emergencyWindow, long legacyUntil) {
        static State empty() { return new State(false, 0, 0, 0, Long.MIN_VALUE, 0, 0,
                "NONE", Long.MIN_VALUE, Long.MIN_VALUE, 0); }
    }
    static State migrateV1(boolean enabled, long generation, long dailyAt, long wakeAt,
                           long lastDailyDay, long lastRequest, long lastAttempt, ZoneId zone) {
        // A v1 wake has no trustworthy semantic reason. Reserve BOTH budgets conservatively
        // for the request and deadline; retain the old pending identity and cooldown.
        long grace = Long.MIN_VALUE, emergency = Long.MIN_VALUE;
        for (long at : new long[] { lastRequest, wakeAt }) {
            if (at > 0) {
                grace = Math.max(grace, localDay(at, zone));
                emergency = Math.max(emergency, ritualWindow(at, zone));
            }
        }
        long until = lastRequest > 0 ? (lastRequest > Long.MAX_VALUE - MAX_WAKE_DELAY
                ? Long.MAX_VALUE : lastRequest + MAX_WAKE_DELAY) : 0;
        return new State(enabled, generation, dailyAt, wakeAt, lastDailyDay, lastRequest,
                lastAttempt, wakeAt > 0 ? "LEGACY" : "NONE", grace, emergency, until);
    }
    interface Store {
        State read();
        void write(State state);
    }
    interface Port {
        long now();
        ZoneId zone();
        boolean activePolicy();
        boolean hasBackgroundLaunchAuthority();
        boolean exactAlarmsAvailable();
        boolean unlockedForegroundUser();
        void arm(Kind kind, long at, long generation);
        void cancel(Kind kind);
        void launchFixedTarget();
        void note(String diagnostic);
    }
    private final Store store;
    private final Port port;
    WakeScheduler(Store store, Port port) { this.store = store; this.port = port; }

    static long nextDaily(long now, ZoneId zone, long lastDailyDay) {
        LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (day.toEpochDay() <= lastDailyDay) day = LocalDate.ofEpochDay(lastDailyDay).plusDays(1);
        long at = day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli();
        if (at <= now) at = day.plusDays(1).atTime(23, 0).atZone(zone).toInstant().toEpochMilli();
        return at;
    }

    synchronized void activate() {
        if (!port.activePolicy()) throw new IllegalStateException("Policy is not ACTIVE");
        State before = store.read();
        State state = new State(true, before.generation() + 1,
                nextDaily(port.now(), port.zone(), before.lastDailyDay()), 0,
                before.lastDailyDay(), before.lastWakeRequest(), before.lastAttempt(),
                "NONE", before.graceDay(), before.emergencyWindow(), before.legacyUntil());
        store.write(state);
        port.cancel(Kind.WAKE);
        port.arm(Kind.DAILY, state.dailyAt(), state.generation());
    }

    synchronized void cancel() {
        // Cancellation is attempted even when the persistent record cannot be read/written.
        RuntimeException failure = null;
        try { store.write(State.empty()); } catch (RuntimeException e) { failure = e; }
        try { port.cancel(Kind.DAILY); } catch (RuntimeException e) { failure = e; }
        try { port.cancel(Kind.WAKE); } catch (RuntimeException e) { failure = e; }
        if (failure != null) throw failure;
    }

    synchronized void reschedule() {
        State old = store.read();
        if (!old.enabled() || !port.activePolicy()) { cancel(); return; }
        long now = port.now();
        long wake = old.wakeAt() > now && old.wakeAt() - now <= MAX_WAKE_DELAY ? old.wakeAt() : 0;
        State next = new State(true, old.generation() + 1,
                nextDaily(now, port.zone(), old.lastDailyDay()), wake,
                old.lastDailyDay(), old.lastWakeRequest(), old.lastAttempt(),
                wake == 0 ? "NONE" : old.wakeReason(), old.graceDay(), old.emergencyWindow(), old.legacyUntil());
        store.write(next);
        port.arm(Kind.DAILY, next.dailyAt(), next.generation());
        if (wake == 0) port.cancel(Kind.WAKE); else port.arm(Kind.WAKE, wake, next.generation());
    }

    synchronized boolean scheduleWake(long epochMillis, Reason reason) {
        State old = store.read();
        long now = port.now();
        if (reason == null || !old.enabled() || !port.activePolicy() || epochMillis <= now
                || epochMillis > now + MAX_WAKE_DELAY) return false;
        // A managed pause must not spend its budget when known return prerequisites are absent.
        // These checks cannot guarantee future delivery or prevent later revocation/keyguard skips.
        if (!port.hasBackgroundLaunchAuthority() || !port.exactAlarmsAvailable()) return false;
        long day = localDay(now, port.zone());
        long window = ritualWindow(now, port.zone());
        if (reason == Reason.FOCUS_GRACE) {
            var local = Instant.ofEpochMilli(now).atZone(port.zone());
            long end = local.toLocalDate().atTime(23, 15).atZone(port.zone()).toInstant().toEpochMilli();
            if (local.getHour() != 23 || now >= end || epochMillis > end) return false;
        }
        boolean pending = old.wakeAt() > now;
        if (pending) {
            if (!reason.name().equals(old.wakeReason())) return false;
            if (epochMillis == old.wakeAt()) {
                port.arm(Kind.WAKE, epochMillis, old.generation());
                return true; // Same pending receipt, including after process recreation.
            }
            if (epochMillis > old.wakeAt()) return false;
        } else {
            if (now < old.legacyUntil()) return false;
            if (reason == Reason.FOCUS_GRACE && day <= old.graceDay()) return false;
            if (reason == Reason.EMERGENCY && window <= old.emergencyWindow()) return false;
        }
        State next = new State(true, old.generation(), old.dailyAt(), epochMillis,
                old.lastDailyDay(), pending ? old.lastWakeRequest() : now, old.lastAttempt(),
                reason.name(), reason == Reason.FOCUS_GRACE ? Math.max(day, old.graceDay()) : old.graceDay(),
                reason == Reason.EMERGENCY ? Math.max(window, old.emergencyWindow()) : old.emergencyWindow(),
                old.legacyUntil());
        store.write(next);
        try { port.arm(Kind.WAKE, epochMillis, next.generation()); }
        catch (RuntimeException failure) {
            // Do not acknowledge an alarm which failed to register.
            store.write(old);
            if (old.wakeAt() > now) port.arm(Kind.WAKE, old.wakeAt(), old.generation());
            else port.cancel(Kind.WAKE);
            port.note("WAKE_REGISTRATION_FAILED");
            throw failure;
        }
        return true;
    }

    synchronized void fire(Kind kind, long generation, long scheduledAt) {
        State old = store.read();
        long now = port.now();
        if (!old.enabled() || old.generation() != generation || scheduledAt <= 0
                || (kind == Kind.DAILY ? old.dailyAt() : old.wakeAt()) != scheduledAt || now < scheduledAt) return;
        if (!port.activePolicy()) { cancel(); return; }
        long dailyDay = kind == Kind.DAILY
                ? Instant.ofEpochMilli(scheduledAt).atZone(port.zone()).toLocalDate().toEpochDay()
                : old.lastDailyDay();
        State consumed = new State(true, old.generation(),
                kind == Kind.DAILY ? nextDaily(now, port.zone(), dailyDay) : old.dailyAt(),
                kind == Kind.WAKE ? 0 : old.wakeAt(), dailyDay, old.lastWakeRequest(), now,
                kind == Kind.WAKE ? "NONE" : old.wakeReason(), old.graceDay(), old.emergencyWindow(), old.legacyUntil());
        store.write(consumed); // A duplicate/replayed broadcast cannot launch again.
        if (kind == Kind.DAILY) port.arm(Kind.DAILY, consumed.dailyAt(), consumed.generation());
        if (now - scheduledAt > (kind == Kind.DAILY ? 7 * 60 * MINUTE : MAX_WAKE_DELAY)) {
            port.note("STALE_ALARM_SKIPPED"); return;
        }
        if (old.lastAttempt() != 0 && now - old.lastAttempt() >= 0 && now - old.lastAttempt() < 2_000) {
            port.note("COINCIDENT_WAKE_COALESCED"); return;
        }
        if (!port.hasBackgroundLaunchAuthority()) { port.note("BAL_UNSUPPORTED_OWNER"); return; }
        if (!port.unlockedForegroundUser()) { port.note("LOCKED_OR_BACKGROUND_USER_SKIPPED"); return; }
        // Android can silently deny BAL. A successful return is not foreground verification.
        port.launchFixedTarget();
        port.note("LAUNCH_REQUESTED_NOT_CONFIRMED");
    }
}

package dev.stanislav.focusdeck.guard;

import java.io.IOException;
import java.util.Set;

/** Policy transaction, independent of Android for fault-injection tests. Never starts a task. */
public final class GuardController {
    // Android constants: GLOBAL_ACTIONS (16), KEYGUARD (32), BLOCK_ACTIVITY_START_IN_TASK (64).
    public static final int DEFAULT_FEATURES = 16;
    public static final int GUARDED_FEATURES = 16 | 32 | 64;
    public static final String PRODUCTION_PACKAGE = "dev.stanislav.focusdeck";
    public static final String TARGET_ACTIVITY = "dev.stanislav.focusdeck.MainActivity";
    public static final String QA_PACKAGE = "dev.stanislav.focusdeck.qa";
    public static final Policy DEFAULT_POLICY = new Policy(Set.of(), DEFAULT_FEATURES);

    public record Policy(Set<String> packages, int features) {
        public Policy { packages = Set.copyOf(packages); }
    }
    public enum Phase { PREPARED, ACTIVE, RESTORED }
    public record Journal(Policy before, Phase phase) {}
    public enum Code { ACTIVE, RESTORED, DORMANT, REFUSED, NEEDS_RECOVERY, UNVERIFIED }
    public record Result(Code code, String message) {}

    public record Home(String packageName, String className) {}

    public interface Platform {
        boolean eligibleOwner();
        boolean secureDevice();
        boolean lockTaskIdle();
        boolean trustedTargets(Set<String> packages);
        Home defaultHome();
        Policy readPolicy();
        void setFeatures(int features);
        void setPackages(Set<String> packages);
    }
    public interface Store {
        Journal read() throws IOException;
        /** Must commit atomically and durably before returning. */
        void write(Journal journal) throws IOException;
    }
    public interface ScheduleLifecycle {
        void activated();
        void cancel();
    }

    private final Platform platform;
    private final Store store;
    private final Set<String> targets;
    private final Policy guardedPolicy;
    private final ScheduleLifecycle schedule;

    public GuardController(Platform platform, Store store, boolean debugBuild) {
        this(platform, store, debugBuild, new ScheduleLifecycle() {
            @Override public void activated() {}
            @Override public void cancel() {}
        });
    }
    public GuardController(Platform platform, Store store, boolean debugBuild, ScheduleLifecycle schedule) {
        this.platform = platform;
        this.store = store;
        this.schedule = schedule;
        targets = debugBuild ? Set.of(QA_PACKAGE) : Set.of(PRODUCTION_PACKAGE);
        guardedPolicy = new Policy(targets, GUARDED_FEATURES);
    }

    /** True only after local credential confirmation or framework-authenticated DUMP command. */
    public synchronized Result activate(boolean explicitlyAuthorized) {
        try {
            if (!explicitlyAuthorized) return refused("Explicit administrative authorization required.");
            if (!platform.eligibleOwner()) return refused("Guard is not an eligible owner.");
            if (!platform.secureDevice()) return refused("Set a device screen-lock credential first.");
            if (!platform.trustedTargets(targets)) {
                return refused("The target launcher is missing or its signing certificate does not match Guard.");
            }
            if (!defaultHomeMatches()) {
                return refused("Set " + targets.iterator().next() + "/" + TARGET_ACTIVITY
                        + " as the default Home in this Android user first. Guard will not change Home.");
            }
            Journal previous = store.read();
            if (previous != null && previous.phase() != Phase.RESTORED) {
                return refused("A saved transaction already exists. Recover it before activating.");
            }
            if (!platform.lockTaskIdle()) return refused("Exit existing pinning/Lock Task before setup.");
            Policy baseline = platform.readPolicy();
            if (!DEFAULT_POLICY.equals(baseline)) {
                return refused("Existing Lock Task policy is not the default. Guard will not replace it.");
            }
            // No policy change is allowed before this durable recovery record exists.
            Journal prepared = new Journal(baseline, Phase.PREPARED);
            store.write(prepared);
            try {
                platform.setFeatures(GUARDED_FEATURES);
                platform.setPackages(targets);
                if (!guardedPolicy.equals(platform.readPolicy()) || !platform.trustedTargets(targets)
                        || !defaultHomeMatches()) {
                    throw new IllegalStateException("Resolved policy differs");
                }
                store.write(new Journal(baseline, Phase.ACTIVE));
                schedule.activated();
                if (!defaultHomeMatches()) throw new IllegalStateException("Default Home changed during activation");
                return new Result(Code.ACTIVE,
                        "Policy read-back matches; defaultHomeMatches=true. Launcher must still enter LOCKED; panels are not blocked yet.");
            } catch (RuntimeException | IOException failure) {
                Result rollback = recover();
                return new Result(rollback.code() == Code.RESTORED ? Code.REFUSED : Code.NEEDS_RECOVERY,
                        "Activation did not complete; recheck the target default Home and policy. " + rollback.message());
            }
        } catch (RuntimeException | IOException failure) {
            return refused("Preflight or recovery-record write failed. No policy change was attempted.");
        }
    }

    /**
     * Only an authenticated UI operation or the permission-protected receiver may call this.
     * Removing this admin's empty package policy restores the previously resolved policy on
     * Android 16. Do NOT write old resolved feature values as a new policy owned by Guard.
     */
    public synchronized Result recover() {
        boolean scheduleCancelled = true;
        try { schedule.cancel(); } catch (RuntimeException failure) { scheduleCancelled = false; }
        try {
            if (!platform.eligibleOwner()) return refused("Guard is not an eligible owner; use owner removal if needed.");
            Journal saved = null;
            boolean readable = true;
            try { saved = store.read(); } catch (IOException | RuntimeException failure) { readable = false; }
            // Recovery must remain possible even if the journal is corrupt or storage is full.
            platform.setPackages(Set.of());
            Policy resolved = platform.readPolicy();
            if (!readable || saved == null) {
                return new Result(Code.UNVERIFIED,
                        "Guard policy removal requested; no usable baseline exists to verify restoration. Check Android policy.");
            }
            if (!saved.before().equals(resolved)) {
                return new Result(Code.NEEDS_RECOVERY,
                        "Guard policy removal requested; resolved policy differs from the saved baseline. Another admin may apply policy.");
            }
            store.write(new Journal(saved.before(), Phase.RESTORED));
            if (!scheduleCancelled) {
                return new Result(Code.NEEDS_RECOVERY,
                        "Policy restored; alarm cancellation was not fully verified. Inactive policy prevents scheduled launches.");
            }
            return new Result(Code.RESTORED,
                    "Guard policy removed and prior Lock Task settings read back. Verify task state separately; Guard remains enrolled.");
        } catch (RuntimeException | IOException failure) {
            return new Result(Code.NEEDS_RECOVERY,
                    "Recovery could not be fully verified. Retry ADB recovery or remove the test-only admin; do not clear app data.");
        }
    }

    public synchronized Result status() {
        try {
            if (!platform.eligibleOwner()) return new Result(Code.DORMANT, "Dormant: no eligible owner authority.");
            Journal saved = store.read();
            Policy actual = platform.readPolicy();
            boolean homeMatches = defaultHomeMatches();
            String homeDiagnostic = " defaultHomeMatches=" + homeMatches + ".";
            if (saved == null) return new Result(Code.DORMANT, "Owner enrolled; Guard has not been activated. Resolved feature mask: " + actual.features() + homeDiagnostic);
            if (saved.phase() == Phase.ACTIVE && guardedPolicy.equals(actual) && platform.trustedTargets(targets)) {
                if (!homeMatches) return new Result(Code.NEEDS_RECOVERY,
                        "Default Home no longer matches the target. Guard policy remains installed; recover before reactivating." + homeDiagnostic);
                return new Result(Code.ACTIVE, "Guard policy read-back matches. This does not prove an active locked task." + homeDiagnostic);
            }
            if (saved.phase() == Phase.RESTORED && saved.before().equals(actual)) {
                return new Result(Code.RESTORED, "Prior Lock Task settings restored; Guard is inactive but still enrolled." + homeDiagnostic);
            }
            return new Result(Code.NEEDS_RECOVERY, "Interrupted transaction or changed policy. Recover before activating again." + homeDiagnostic);
        } catch (RuntimeException | IOException failure) {
            return new Result(Code.UNVERIFIED, "Policy/journal could not be read. Use independent ADB recovery.");
        }
    }

    private boolean defaultHomeMatches() {
        Home home = platform.defaultHome();
        return home != null && targets.contains(home.packageName()) && TARGET_ACTIVITY.equals(home.className());
    }

    private static Result refused(String message) { return new Result(Code.REFUSED, message); }
}

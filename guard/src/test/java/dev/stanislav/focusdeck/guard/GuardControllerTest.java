package dev.stanislav.focusdeck.guard;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static dev.stanislav.focusdeck.guard.GuardController.*;
import static org.junit.Assert.*;

public class GuardControllerTest {
    private final List<String> events = new ArrayList<>();
    private final FakePlatform platform = new FakePlatform();
    private final FakeStore store = new FakeStore();
    private static final Policy GUARDED_POLICY = new Policy(Set.of(PRODUCTION_PACKAGE), GUARDED_FEATURES);
    private final GuardController controller = new GuardController(platform, store, false);

    private final class FakePlatform implements Platform {
        boolean owner = true, secure = true, idle = true, trusted = true;
        boolean failFeatures, failPackages, failRecovery, mismatchAfterApply;
        Home home = new Home(PRODUCTION_PACKAGE, TARGET_ACTIVITY);
        boolean homeChangesAfterApply, homeReadFails;
        @Override public Home defaultHome() {
            if (homeReadFails) throw new IllegalStateException("Home unavailable");
            return home;
        }
        Policy external = DEFAULT_POLICY;
        Policy own;
        @Override public boolean eligibleOwner() { return owner; }
        @Override public boolean secureDevice() { return secure; }
        @Override public boolean lockTaskIdle() { return idle; }
        @Override public boolean trustedTargets(Set<String> packages) { return trusted; }
        @Override public Policy readPolicy() {
            if (mismatchAfterApply && own != null && !own.packages().isEmpty()) return DEFAULT_POLICY;
            return own == null ? external : own;
        }
        @Override public void setFeatures(int features) {
            events.add("features");
            own = new Policy(own == null ? Set.of() : own.packages(), features);
            if (failFeatures) throw new SecurityException();
        }
        @Override public void setPackages(Set<String> packages) {
            events.add(packages.isEmpty() ? "remove-own-policy" : "packages");
            if (packages.isEmpty()) {
                if (failRecovery) throw new SecurityException();
                own = null;
            } else {
                own = new Policy(packages, own == null ? DEFAULT_FEATURES : own.features());
                if (homeChangesAfterApply) home = new Home("other.home", "other.Home");
                if (failPackages) throw new IllegalStateException();
            }
        }
    }
    private final class FakeStore implements Store {
        Journal journal;
        boolean corrupt, failPrepared, failActive, failRestored;
        @Override public Journal read() throws IOException {
            if (corrupt) throw new IOException();
            return journal;
        }
        @Override public void write(Journal value) throws IOException {
            events.add("save-" + value.phase());
            if ((value.phase() == Phase.PREPARED && failPrepared)
                    || (value.phase() == Phase.ACTIVE && failActive)
                    || (value.phase() == Phase.RESTORED && failRestored)) throw new IOException();
            journal = value;
        }
    }

    @Test public void enrollmentAndStatusAreDormantAndNeverMutate() {
        assertEquals(Code.DORMANT, controller.status().code());
        assertTrue(events.isEmpty());
        assertNull(store.journal);
    }
    @Test public void explicitConfirmationIsRequiredEvenForOwner() {
        assertEquals(Code.REFUSED, controller.activate(false).code());
        assertTrue(events.isEmpty());
    }
    @Test public void ordinaryAdminCannotMutateEvenDuringRecovery() {
        platform.owner = false;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(Code.REFUSED, controller.recover().code());
        assertTrue(events.isEmpty());
    }
    @Test public void deviceCredentialAndIdleTaskAreRequired() {
        platform.secure = false;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        platform.secure = true;
        platform.idle = false;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertTrue(events.isEmpty());
    }
    @Test public void nonDefaultPolicyIsPreservedWithoutTakingOwnershipOfIt() {
        platform.external = new Policy(Set.of("other.kiosk"), 32);
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(Set.of("other.kiosk"), platform.readPolicy().packages());
        assertTrue(events.isEmpty());
    }
    @Test public void emptyPackagesWithNonDefaultFeaturesAlsoRefused() {
        platform.external = new Policy(Set.of(), 32);
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertTrue(events.isEmpty());
    }
    @Test public void journalIsDurableBeforeAnyPlatformMutation() {
        assertEquals(Code.ACTIVE, controller.activate(true).code());
        assertEquals(List.of("save-PREPARED", "features", "packages", "save-ACTIVE"), events);
        assertEquals(GUARDED_POLICY, platform.readPolicy());
        assertEquals(DEFAULT_POLICY, store.journal.before());
    }
    @Test public void fullDiskBeforeSnapshotPreventsEveryPolicyChange() {
        store.failPrepared = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(List.of("save-PREPARED"), events);
        assertNull(platform.own);
    }
    @Test public void corruptJournalPreventsActivation() {
        store.corrupt = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertTrue(events.isEmpty());
    }
    @Test public void featureFailureRollsBackPartialPolicy() {
        platform.failFeatures = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
        assertEquals(Phase.RESTORED, store.journal.phase());
    }
    @Test public void packageFailureRollsBackPartialPolicy() {
        platform.failPackages = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
    }
    @Test public void readBackConflictDoesNotClaimActivation() {
        platform.mismatchAfterApply = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertNull(platform.own);
    }
    @Test public void finalJournalFailureRollsBackRatherThanLosingRecoveryState() {
        store.failActive = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
        assertEquals(Phase.RESTORED, store.journal.phase());
    }
    @Test public void failedRollbackRetainsOriginalBaselineForIndependentRetry() {
        platform.failPackages = true;
        platform.failRecovery = true;
        assertEquals(Code.NEEDS_RECOVERY, controller.activate(true).code());
        assertEquals(Phase.PREPARED, store.journal.phase());
        assertEquals(DEFAULT_POLICY, store.journal.before());
        platform.failRecovery = false;
        assertEquals(Code.RESTORED, controller.recover().code());
    }
    @Test public void repeatedActivationCannotOverwriteBaseline() {
        controller.activate(true);
        Journal saved = store.journal;
        events.clear();
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertEquals(saved, store.journal);
        assertTrue(events.isEmpty());
    }
    @Test public void recreatedProcessCanRecoverPreparedTransactionWithoutVaultOrActivity() {
        store.journal = new Journal(DEFAULT_POLICY, Phase.PREPARED);
        platform.own = GUARDED_POLICY;
        assertEquals(Code.RESTORED, new GuardController(platform, store, false).recover().code());
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
    }
    @Test public void recoveryDoesNotNeedCredentialOrIdleTask() {
        controller.activate(true);
        platform.secure = false;
        platform.idle = false;
        assertEquals(Code.RESTORED, controller.recover().code());
    }
    @Test public void recoveryRemovesOnlyOwnPolicyAndDetectsOtherAdministratorChanges() {
        controller.activate(true);
        platform.external = new Policy(Set.of("other.admin.kiosk"), 32);
        events.clear();
        assertEquals(Code.NEEDS_RECOVERY, controller.recover().code());
        assertEquals(List.of("remove-own-policy"), events);
        assertEquals(Set.of("other.admin.kiosk"), platform.readPolicy().packages());
        assertEquals(Phase.ACTIVE, store.journal.phase());
    }
    @Test public void corruptOrMissingJournalDoesNotPreventEmergencyPolicyRemoval() {
        platform.own = GUARDED_POLICY;
        assertEquals(Code.UNVERIFIED, controller.recover().code());
        assertNull(platform.own);
        platform.own = GUARDED_POLICY;
        store.corrupt = true;
        assertEquals(Code.UNVERIFIED, controller.recover().code());
        assertNull(platform.own);
    }
    @Test public void recoveryDiskFailureStillRemovesPolicyButDoesNotClaimDurableSuccess() {
        controller.activate(true);
        store.failRestored = true;
        assertEquals(Code.NEEDS_RECOVERY, controller.recover().code());
        assertNull(platform.own);
        assertEquals(Phase.ACTIVE, store.journal.phase());
    }
    @Test public void recoveryIsRepeatableAndReactivationNeedsAnotherConfirmation() {
        controller.activate(true);
        assertEquals(Code.RESTORED, controller.recover().code());
        assertEquals(Code.RESTORED, controller.recover().code());
        assertEquals(Code.REFUSED, controller.activate(false).code());
        assertEquals(Code.ACTIVE, controller.activate(true).code());
    }
    @Test public void statusDetectsPolicyDriftWithoutReapplyingAnything() {
        controller.activate(true);
        platform.own = null;
        events.clear();
        assertEquals(Code.NEEDS_RECOVERY, controller.status().code());
        assertTrue(events.isEmpty());
    }

    @Test public void missingOrWrongCertificateCannotReceiveAnAllowlist() {
        platform.trusted = false;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertTrue(events.isEmpty());
    }
    @Test public void releaseNeverAllowlistsQaAndDebugNeverAllowlistsProduction() {
        controller.activate(true);
        assertEquals(Set.of(PRODUCTION_PACKAGE), platform.readPolicy().packages());
        controller.recover();
        platform.home = new Home(QA_PACKAGE, TARGET_ACTIVITY);
        GuardController debug = new GuardController(platform, store, true);
        assertEquals(Code.ACTIVE, debug.activate(true).code());
        assertEquals(Set.of(QA_PACKAGE), platform.readPolicy().packages());
    }
    @Test public void certificateLossIsReportedButDoesNotPreventIndependentRecovery() {
        controller.activate(true);
        platform.trusted = false;
        assertEquals(Code.NEEDS_RECOVERY, controller.status().code());
        assertEquals(Code.RESTORED, controller.recover().code());
    }

    @Test public void privilegedActivateUsesOnlyTheFixedBuildTargetAndFeatureMask() {
        assertEquals(Code.ACTIVE, GuardCommands.dispatch(controller, GuardCommands.ACTIVATE).code());
        assertEquals(new Policy(Set.of(PRODUCTION_PACKAGE), 112), platform.readPolicy());
        assertEquals(DEFAULT_POLICY, store.journal.before());
    }
    @Test public void privilegedActivateStillEnforcesOwnerCertificateAndBaseline() {
        platform.owner = false;
        assertEquals(Code.REFUSED, GuardCommands.dispatch(controller, GuardCommands.ACTIVATE).code());
        platform.owner = true;
        platform.trusted = false;
        assertEquals(Code.REFUSED, GuardCommands.dispatch(controller, GuardCommands.ACTIVATE).code());
        platform.trusted = true;
        platform.external = new Policy(Set.of("another.kiosk"), 16);
        assertEquals(Code.REFUSED, GuardCommands.dispatch(controller, GuardCommands.ACTIVATE).code());
        assertTrue(events.isEmpty());
    }
    @Test public void statusUnknownAndMissingActionCannotActivate() {
        assertEquals(Code.DORMANT, GuardCommands.dispatch(controller, GuardCommands.STATUS).code());
        assertEquals(Code.REFUSED, GuardCommands.dispatch(controller, "ACTIVATE").code());
        assertEquals(Code.REFUSED, GuardCommands.dispatch(controller, null).code());
        assertTrue(events.isEmpty());
    }
    @Test public void privilegedRecoverRemovesTheSamePolicyWithoutLauncherProcess() {
        GuardCommands.dispatch(controller, GuardCommands.ACTIVATE);
        assertEquals(Code.RESTORED, GuardCommands.dispatch(new GuardController(platform, store, false),
                GuardCommands.RECOVER).code());
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
    }

    @Test public void scheduleIsArmedOnlyAfterActiveJournalAndCancelledBeforePolicyRecovery() {
        GuardController integrated = new GuardController(platform, store, false, new ScheduleLifecycle() {
            @Override public void activated() {
                assertEquals(Phase.ACTIVE, store.journal.phase());
                events.add("arm-schedule");
            }
            @Override public void cancel() { events.add("cancel-schedule"); }
        });
        assertEquals(Code.ACTIVE, integrated.activate(true).code());
        assertEquals("arm-schedule", events.get(events.size() - 1));
        events.clear();
        assertEquals(Code.RESTORED, integrated.recover().code());
        assertEquals("cancel-schedule", events.get(0));
        assertEquals("remove-own-policy", events.get(1));
    }
    @Test public void scheduleRegistrationFailureRollsBackActivation() {
        GuardController integrated = new GuardController(platform, store, false, new ScheduleLifecycle() {
            @Override public void activated() { throw new IllegalStateException(); }
            @Override public void cancel() { events.add("cancel-schedule"); }
        });
        assertEquals(Code.REFUSED, integrated.activate(true).code());
        assertTrue(events.contains("cancel-schedule"));
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
    }
    @Test public void alarmCancellationFailureCannotPreventRemovingPolicy() {
        GuardController integrated = new GuardController(platform, store, false, new ScheduleLifecycle() {
            @Override public void activated() {}
            @Override public void cancel() { throw new IllegalStateException(); }
        });
        integrated.activate(true);
        assertEquals(Code.NEEDS_RECOVERY, integrated.recover().code());
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
        assertEquals(Phase.RESTORED, store.journal.phase());
    }

    @Test public void missingResolverWrongPackageAndWrongComponentHomeRefuseBeforeAnyWrite() {
        for (Home home : new Home[] { null, new Home("android", "com.android.internal.app.ResolverActivity"),
                new Home("com.google.android.apps.nexuslauncher", "some.Home"),
                new Home(QA_PACKAGE, TARGET_ACTIVITY), new Home(PRODUCTION_PACKAGE, "other.Activity") }) {
            platform.home = home;
            Result result = GuardCommands.dispatch(controller, GuardCommands.ACTIVATE);
            assertEquals(Code.REFUSED, result.code());
            assertTrue(result.message().contains("default Home"));
            assertTrue(events.isEmpty());
            assertNull(store.journal);
        }
    }
    @Test public void debugRequiresQaHomeEvenWhenProductionHomeHasMatchingCertificate() {
        GuardController debug = new GuardController(platform, store, true);
        assertEquals(Code.REFUSED, debug.activate(true).code());
        assertTrue(events.isEmpty());
        platform.home = new Home(QA_PACKAGE, TARGET_ACTIVITY);
        assertEquals(Code.ACTIVE, debug.activate(true).code());
    }
    @Test public void homeResolutionFailureCannotChangePolicyOrBlockRecovery() {
        platform.homeReadFails = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertTrue(events.isEmpty());
        platform.homeReadFails = false;
        assertEquals(Code.ACTIVE, controller.activate(true).code());
        platform.homeReadFails = true;
        assertEquals(Code.UNVERIFIED, controller.status().code());
        assertEquals(Code.RESTORED, controller.recover().code());
    }
    @Test public void homeChangingDuringPolicyApplicationRollsBackBeforeActiveJournal() {
        platform.homeChangesAfterApply = true;
        assertEquals(Code.REFUSED, controller.activate(true).code());
        assertFalse(events.contains("save-ACTIVE"));
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
        assertEquals(Phase.RESTORED, store.journal.phase());
    }
    @Test public void homeChangingDuringScheduleRegistrationCancelsAndRollsBack() {
        GuardController integrated = new GuardController(platform, store, false, new ScheduleLifecycle() {
            @Override public void activated() { platform.home = null; events.add("arm-schedule"); }
            @Override public void cancel() { events.add("cancel-schedule"); }
        });
        assertEquals(Code.REFUSED, integrated.activate(true).code());
        assertTrue(events.contains("arm-schedule"));
        assertTrue(events.contains("cancel-schedule"));
        assertEquals(DEFAULT_POLICY, platform.readPolicy());
    }
    @Test public void laterHomeDriftIsReportedWithoutPolicyWritesAndRecoveryStillWorks() {
        controller.activate(true);
        platform.home = null;
        events.clear();
        Result result = controller.status();
        assertEquals(Code.NEEDS_RECOVERY, result.code());
        assertTrue(result.message().contains("defaultHomeMatches=false"));
        assertTrue(events.isEmpty());
        assertEquals(GUARDED_POLICY, platform.readPolicy());
        assertEquals(Code.RESTORED, controller.recover().code());
        assertEquals(Code.RESTORED, controller.status().code());
        assertTrue(controller.status().message().contains("defaultHomeMatches=false"));
    }
}

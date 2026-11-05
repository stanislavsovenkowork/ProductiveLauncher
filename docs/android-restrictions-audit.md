# Android restrictions audit — 2026-09-29

## Verdict

**Managed Lock Task can prevent ordinary Recents/shade escape. That capability is not currently configured for FocusDeck on this Pixel. A non-destructive enrollment route exists in Android, but eligibility with this phone's existing accounts remains unverified.** Do not tell the user that a reset is categorically required, or that the present launcher gate is impossible to bypass.

The useful distinctions are:

| Option | Works with existing personal data? | Meets the requested guarantee? |
| --- | --- | --- |
| Current launcher gate | Yes | No; applies inside FocusDeck only |
| Explicit screen pinning | Yes | No; user can unpin, even if credentials are required |
| ADB status-bar disable flags | No enrollment required | Potentially blocks the two named UI routes during a supervised session; not persistent lockdown |
| DPC managed Lock Task | Conditional enrollment | Yes for ordinary navigation while actually locked with restrictive features; lifecycle/recovery still need testing |
| Work-profile DPC | Can be added without clearing personal data | No control over the whole personal environment; managed profiles cannot use this Lock Task route |
| Profile owner in a full user | Conditional | AOSP permits Lock Task without a device owner in a non-managed-profile user; account restrictions still apply to enrollment |

There is no verified durable, autonomous whole-phone lockdown under today's constraints (keep accounts, no installs or policy changes, no AccessibilityService). There **is** a plausible conditional managed route and a limited reversible ADB experiment. Neither was executed.

## Evidence from this device

All device commands were read-only, explicitly targeted to serial `62051JEA324712`. No UI interaction, installation, account enumeration, policy mutation, root attempt, network change, or reset occurred. Account presence is user-provided context; account names/types/features were not read. The device-policy output was filtered; user-list output was reduced to IDs before displaying it.

| Check | Observed |
| --- | --- |
| Connection | `device` |
| Model / OS / SDK | Pixel 10a / Android 16 / 36 |
| Build ID | `BD6A.251031.001.A4` |
| Build type / debuggable / verified boot | `user` / `0` / `green` |
| `device_provisioned` / `user_setup_complete` | `1` / `1` |
| Current user / listed user IDs | `0` / `0` only |
| Device owner | `Device Owner Type: -1`; `dpm list-owners` returned no entries |
| Active device admin | `com.stayfocused/.admin.FocusedAdminReceiver` |
| Activity task lock state | `NONE`, empty locked-task list and package map |
| DEVICE_POLICY_MANAGEMENT role | `com.google.android.apps.work.clouddpc` |
| FINANCED_DEVICE_KIOSK role query | No holder returned |

The policy dump also contains role-authority entries for `com.google.android.devicelockcontroller`. Their presence is not proof of active kiosk confinement or authority granted to FocusDeck. The observed task state is still NONE. Do not replace/remove Google role holders or assume they offer a third-party allowlisting API.

The repository has unrelated work in progress. This audit only adds this document and files under the new production/test `ui/restrictions/` packages. MainActivity, RootScreen, manifest, and ritual files were read but not edited. Current MainActivity already distinguishes PINNED/LOCKED, checks resumed/keyguard before managed entry, and only automatically starts when allowlisted. It cannot grant itself that allowlist.

Reproducible non-sensitive checks:

```sh
adb -s 62051JEA324712 get-state
adb -s 62051JEA324712 shell getprop ro.build.version.sdk
adb -s 62051JEA324712 shell settings get global device_provisioned
adb -s 62051JEA324712 shell settings get secure user_setup_complete
adb -s 62051JEA324712 shell dpm list-owners
adb -s 62051JEA324712 shell dumpsys device_policy | rg 'Device Owner Type|mUserSetupComplete|Enabled Device Admins'
adb -s 62051JEA324712 shell dumpsys activity activities | rg 'mLockTaskModeState|mLockTaskModeTasks|mLockTaskPackages'
adb -s 62051JEA324712 shell cmd role get-role-holders --user 0 android.app.role.DEVICE_POLICY_MANAGEMENT
adb -s 62051JEA324712 shell cmd statusbar help
```

## What enrollment actually permits

Android's development cookbook explicitly documents setting a device owner without factory reset when accounts are absent. Setup-completed alone is therefore not decisive. [Official development setup](https://developer.android.com/work/dpc/dedicated-devices/cookbook#development-setup).

For account-preserving enrollment, inspected AOSP `android-16.0.0_r1` provides more specific evidence:

1. `setDeviceOwner()` checks incompatible accounts across users. Even if compatible, existing accounts require a **test-only admin**; a normal production admin is rejected.
2. `checkDeviceOwnerProvisioningPreConditionLocked()` allows ADB after setup but rejects incompatible accounts and additional non-test users, and checks existing owners and running-user conditions. The current single-user observation clears only one of these barriers.
3. `CalculateHasIncompatibleAccountsTask` requires every account authenticator to report `ACCOUNT_FEATURE_DEVICE_OR_PROFILE_OWNER_ALLOWED` and not report `...DISALLOWED`. An uninitialized compatibility cache is treated conservatively. `android:testOnly=true` is not a general bypass for arbitrary accounts.
4. `enforceCanSetProfileOwnerLocked()` also checks incompatible accounts after setup for ADB. A full-user profile owner is a narrower candidate: `canDPCManagedUserUseLockTaskLocked()` accepts a profile owner in a non-managed-profile user when there is no device owner. A conventional work profile is explicitly excluded.
5. Device-owner assignment is materially state-changing: the implementation disables the system-user backup service and adds profile restrictions. It is not a harmless eligibility probe.

Source: [Android 16 DevicePolicyManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-16.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java). Methods above are exact search anchors. Source was successfully read from the [official AOSP GitHub mirror](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java) because the Gitiles fetch failed. This is a reference implementation, not a decompilation of the installed Pixel build.

**Unresolved:** whether this phone's authenticators satisfy those feature checks. No supported read-only eligibility command appeared in this phone's `dpm help`. `is-operation-safe` concerns operation safety conditions, not device-owner provisioning eligibility. `dpm set-device-owner` first activates an admin and then attempts ownership; even failed enrollment can have side effects. Do not run it as a test under this audit's read-only authorization. [Shell implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerServiceShellCommand.java).

Android also has a permission/role-based management path. `MANAGE_DEVICE_POLICY_LOCK_TASK` is `internal|role`, not a runtime permission that `pm grant` or `WRITE_SECURE_SETTINGS` supplies. Replacing the role holder is a policy change, not a supported shortcut for this app. AOSP's role-qualification bypass check itself considers incompatible accounts and non-test users; it is not evidence of arbitrary account-preserving enrollment. [Permission declaration](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/res/AndroidManifest.xml), [official CTS role tests](https://android.googlesource.com/platform/cts/+/refs/heads/master/tests/devicepolicy/src/android/devicepolicy/cts/DevicePolicyManagementRoleHolderTest.java).

Do not toggle setup-complete flags, remove accounts, mark users as test users, manipulate role qualification, root the phone, or remove the existing admin to evade these conditions. None is necessary to deliver the audit, and none is authorized here.

## Managed Lock Task versus ADB and pinning

The DPC sets the packages and feature mask; FocusDeck enters with `startLockTask()` while foreground and unlocked. An ordinary non-allowlisted call requests pinning. Observe `ActivityManager.lockTaskModeState` afterwards; a successful method return does not establish LOCKED. The feature mask matters: LOCKED alone does not establish that Overview or notification UI is disabled. The ordinary app should report that feature verification belongs to the manager. [Official Lock Task guide](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode).

AOSP's `am task lock TASK_ID` invokes `startSystemLockTaskMode()`. `LockTaskController.startLockTaskMode(..., isSystemCaller=true, ...)` selects **PINNED**, not LOCKED. Shell access therefore does not turn this command into managed kiosk mode. [ActivityManagerShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/am/ActivityManagerShellCommand.java), [LockTaskController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/wm/LockTaskController.java).

The phone's `cmd statusbar help` confirms `recents`, `statusbar-expansion`, and `quick-settings` disable flags. The shell command uses a static Binder token hosted inside system_server. Disable records live in memory there and are returned to SystemUI when it reconnects. Consequently, **ADB disconnection or a SystemUI restart alone need not clear these flags**; reboot/system_server restart does. This is source-derived behavior, not a Pixel gesture/persistence test. [StatusBarShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/statusbar/StatusBarShellCommand.java), [StatusBarManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/statusbar/StatusBarManagerService.java).

Future supervised experiment only — these policy/UI changes were **not run**:

```sh
# Only after explicit authorization for this experiment and a prepared rollback.
# Begin with FocusDeck already visible and recovery access available.
adb -s 62051JEA324712 shell cmd statusbar send-disable-flag recents statusbar-expansion quick-settings
# Rollback when this experiment started with no existing shell disable flags:
adb -s 62051JEA324712 shell cmd statusbar send-disable-flag none
```

First record pre-existing disable state from a narrowly filtered status-bar dump. The command replaces the shell token's flags; `none` clears that token, not other policy owners. If the shell token already had flags, restore those instead. Test gesture navigation and three-button navigation separately; help text is not enforcement proof. Home, Back, assistant, links, notification/full-screen intents, Settings entry points, alternate users and reboot are not comprehensively covered by these flags.

A host daemon could reapply shell changes while connected, but disconnection, reboot and timing gaps remain. Running a shell-privileged companion does not confer device ownership, and bootstrap after reboot remains an unsolved requirement here. Ordinary app alarms/background services are not a general right to seize the foreground. Android documents limited exceptions including device-owner DPCs. [Background activity restrictions](https://developer.android.com/guide/components/activities/secure-bal).

Other ADB grants/app-ops may persist, but do not grant Lock Task management authority. Package suspension/disable commands affect individual apps and have exempt/critical packages; they do not close system navigation or constitute an atomic scheduled kiosk policy. They can also impair recovery. No such package changes were attempted. The shell suspension entry point is `runSuspend()` in [PackageManagerShellCommand](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/pm/PackageManagerShellCommand.java). No boot-persistence guarantee for suspension was tested on this build.

## Executable implementation sequence for the main agent

1. **Current phone:** retain honest launcher-only/pinned/managed status. Integrate the pure controller below if helpful. This phase needs no provisioning, manifest permission, or AccessibilityService.
2. **Prepare a separate Android 16 test device/emulator:** build a small DPC with a declared DeviceAdminReceiver and its XML metadata, a debug test-only variant, restrictive Lock Task configuration, and a separate authenticated recovery command. Keep it separate from the release launcher so emergency repair does not depend on the ritual UI. No DPC APK is supplied by this audit.
3. **Validate non-destructive provisioning on that test environment:** use an account-free configured user first. Then test compatible/incompatible authenticator cases. The enrollment command below is a template, deliberately requiring a real prepared component. It is not authorized for the personal Pixel during this task.

   ```sh
   # Future TEST DEVICE only; explicit install/provisioning authorization required.
   : "${TEST_SERIAL:?Choose the separate test device}"
   : "${DPC_COMPONENT:?Set the installed, declared test DPC receiver}"
   adb -s "$TEST_SERIAL" shell dpm set-device-owner --user 0 "$DPC_COMPONENT"
   adb -s "$TEST_SERIAL" shell dpm list-owners
   # Test-only removal after clearing that DPC's lock policy:
   adb -s "$TEST_SERIAL" shell dpm remove-active-admin --user 0 "$DPC_COMPONENT"
   ```

   Removal is supported for test-only admins; do not assume production ownership can be undone by this shell command. Validate data and backup behavior through the complete enroll/recover cycle.
4. **DPC policy candidate on the test device:** allowlist only the actual launcher variant (`dev.stanislav.focusdeck` or `.qa`, never confuse the two). Set features to `LOCK_TASK_FEATURE_KEYGUARD | LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK`; omit HOME, OVERVIEW and NOTIFICATIONS. This preserves the keyguard and closes ordinary same-task external-activity entry. Do not blindly use `LOCK_TASK_FEATURE_NONE`: it also suppresses keyguard. Verify calls/alerts with this precise feature mask before adoption. The DPC must read back resolved policy; Android 16 can resolve policies from multiple authorities.
5. **Scheduling:** recompute ritual requirements after foreground entry, unlock, boot and clock/date/time-zone changes. Ensure the DPC can bring the launcher forward at the deadline and after an allowed pause. A check only when Home resumes does not interrupt an already-open app at 23:00. An exact alarm alone does not solve launch authority or reboot recovery. Determine the persisted schedule and trustworthy clock policy without depending on editable vault markers alone.
6. **Restrict secondary bypasses only after recovery works:** evaluate safe boot, user switching/addition, launcher replacement, uninstall/clear-data and time changes. Apply only the needed DPC policies, record their previous values, and test rollback. Keep debugging available through validation. With authorized ADB still available, the owner retains an administrative escape. A factory-reset restriction in Settings is not a guarantee against physical recovery/reset. Forced reboot remains possible.
7. **Personal-device decision:** after tests, present the concrete DPC, recovery procedure and effects for authorization. If existing accounts fail eligibility, stop the account-preserving branch. Account removal/reset is a separate migration decision, not a hidden setup step. Full-user profile ownership is worth testing as a narrower alternative, but is not presumed eligible or equivalent to whole-device ownership.

## Emergency and vault recovery requirements

Emergency calling must remain reachable independently of ritual completion, note length, network, and vault availability. Keep keyguard available; validate its emergency dialer, hardware emergency/SOS route, incoming calls, and wireless emergency alerts on the test device. Do not place a real emergency call as a QA test. AOSP has explicit keyguard-related emergency-call and wireless-alert exceptions, but OEM behavior and interactions with in-task blocking need device verification. An arbitrary browser or general Settings allowlist is not an emergency solution. [Android 16 LockTaskController](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/wm/LockTaskController.java).

Separate the ordinary one-time 15-minute ritual pause from **break-glass recovery**. A broken vault or expired URI grant must not strand the phone after that pause is exhausted. Provide an authenticated local recovery path independent of the vault, plus a DPC-controlled exit. The recovery credential should not be the ordinary unlock PIN if the goal is meaningful self-binding; choosing who holds it is a product decision. No credential was created in this audit.

The recovery flow should preserve drafts in private app storage, record “ritual incomplete / vault unavailable,” and allow reconnecting the vault or exporting drafts. It must never mark the ritual complete just because storage failed. A persisted Storage Access Framework URI grant can stop working if the target is moved/deleted; reconnecting requires user interaction. [Official document access guidance](https://developer.android.com/training/data-storage/shared/documents-files).

DocumentsUI/providers may be blocked by the restricted activity policy. Test the exact picker/provider path in maintenance mode; do not allowlist Settings, arbitrary file managers, or browsers wholesale to fix it. If a timed recovery mode exits Lock Task, it is an intentional bypass and re-entry must be independently scheduled. Permit repeated authenticated break-glass use when faults persist. The owning DPC can remove the launcher from its lock allowlist to exit; test this while the launcher is crashed or unable to read its vault. Do not make recovery depend solely on an in-process ownership Boolean.

## Standalone controller and integration boundary

Added:

- `app/src/main/java/dev/stanislav/focusdeck/ui/restrictions/RestrictionController.kt`
- `app/src/test/java/dev/stanislav/focusdeck/ui/restrictions/RestrictionControllerTest.kt`

This is intentionally a **pure decision controller**, not a DPC or Android service. It performs no Android API calls. Input is a fresh platform snapshot plus the ritual's validated required/not-required decision. Output separates observed mode from requested action, never auto-requests pinning, defers actions while locked/backgrounded, and releases only a task whose start belongs to the calling activity session. It cannot guarantee confinement or prove the DPC feature mask.

Main-agent wiring (not applied here):

1. Sample `ActivityManager.lockTaskModeState`, `DevicePolicyManager.isLockTaskPermitted(packageName)`, resumed lifecycle state, `KeyguardManager.isKeyguardLocked || isDeviceLocked`, and `Activity.taskId`. Map failed platform reads to UNKNOWN, never LOCKED.
2. Feed ritualRequired from the existing engine, including its deliberate pause policy. Storage failure alone is not completion. Pass ownedTaskId only for a start issued by this same live activity session. Never infer ownership from a global LOCKED state or a vault marker.
3. For REQUEST_MANAGED_LOCK, immediately recheck foreground/keyguard/allowlisting and call `startLockTask()`. Catch security/lifecycle failures, then resample. The permission check and start are not atomic: a policy revocation between them can yield pinning. Report observed PINNED honestly and do not automatically fight the user's unpin gesture.
4. Track an outstanding request in the adapter so repeated callbacks do not issue duplicate starts before Android confirms a transition. The pure controller itself has no timer/pending state. Clear stale ownership when NONE is observed after a settled session. After an unexpected loss of ownership, request DPC recovery rather than adopting another task's lock.
5. For RELEASE_OWNED_LOCK, call `stopLockTask()` and resample. Preserve ownership if release throws or remains pending. ReleaseNeedsOwner must surface a usable manager/recovery path. Activity/process recreation intentionally does not reconstruct ownership; the DPC must recover a remaining locked task. An ownership handoff, if later added, needs independently tested task/session identity.
6. Keep explicit screen pinning a separate user action. Do not send PINNED through a UI label that promises mandatory confinement. Even LOCKED should say that panels are governed by administrator policy until those features are verified.

The controller does not invoke AccessibilityService, add manifest components, provision owners, suppress system UI, or provide boot/deadline scheduling. Its tests cover decision safety, not Android/OEM enforcement.

## Acceptance and verification

Local verification completed: `:app:testDebugUnitTest --tests 'dev.stanislav.focusdeck.ui.restrictions.RestrictionControllerTest'` succeeded, **10 tests, 0 failures, 0 errors, 0 skipped**. This also compiled the current debug application and test sources; it was not a full test-suite run. `git diff --check` passed for tracked changes. No APK was installed and no Android restriction was exercised on the phone.

Required future device checks, all still **unverified** in this audit: enter/exit managed LOCKED; both navigation modes; shade/Quick Settings/Recents; external same-task links and full-screen intents; lock/unlock; existing foreground app at deadline; 15-minute pause expiry; midnight/morning/time-zone changes; launcher crash/process death and update; reboot before/after first unlock; safe mode; clock tampering; emergency paths; missing/moved/read-only vault; DocumentsUI return; independent DPC exit; and restoration of prior policy. Run on a spare device first.

The permitted device audit establishes capability and state only. It does not establish that any proposed restriction was applied, tested, or survived a reboot.

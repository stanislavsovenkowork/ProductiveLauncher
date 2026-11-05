# Guard: daily 23:00 and bounded launcher wake

## Latest checkpoint — 03:45–03:46 Minsk, reported by main

Main repeated the production restrictions/recovery checks **after cleanup, at 03:45–03:46 Minsk**: user 0 is current and the only user; `dpm list-owners` is empty; Guard STATUS is `DORMANT` with no eligible owner, `Schedule enabled=false`, `next23=0`, `wake=0`, `exactAllowed=false` and `ownerBAL=false`; LockTask is `NONE`; backup manager is enabled. The sole enabled device admin remains the unchanged StayFocused (`com.stayfocused`). Guard has no owner/admin authority and no active schedule. These are main's reported device observations; this agent did not repeat device commands.

Production FocusDeck **1.1.0, pass19** was installed at **03:38 Minsk**, with its hash verified by main. This is an installation checkpoint, not the final build designation: main's small weight fix/pass20 is underway; its completion or installation is not claimed here.

**Visual verification on user 0 remains pending user unlock:** the primary keyguard is still locked. Installation/hash verification and the restrictions checkpoint do not establish a visual Home/ritual UI pass. Production managed hard mode and guaranteed alarm return are not claimed. This update changes Guard documentation only, with no source, build or device changes.

## Device checkpoint preserved

Earlier QA report from main, **not a device check by this agent**: schema-2 Guard debug was profile owner in the disposable full secondary **user 10** during QA. ACTIVATE applied mask 112 and QA entered actual `LOCKED`. With PixelLauncher still the default Home, injected Home opened BlockedAppActivity and Back exposed the existing PixelLauncher while the mode remained LOCKED. Main recovered, explicitly made QA the default Home, reactivated, and launched via Home. Main then reports Recents/Home/bottom swipe/shade swipe/Back all remained in QA with LOCKED; after force-stopping QA, independent RECOVER returned the mode to NONE. **Earlier cleanup checkpoint reported by main: RECOVER and admin removal completed; disposable user 10 removed; user 0 current and the only remaining user; Guard debug uninstalled; backup enabled. That was the pre-enrollment cleanup checkpoint; the subsequent user-0 enrollment refusal and confirmed post-refusal cleanup are recorded below.** These observations distinguish the earlier privileged injected-key path from the later navigation retest. This agent did not run ADB or change any Home/account/owner setting. The new default-Home preflight and scheduling-prerequisite code below still needs main's device validation.

## Personal-user enrollment result

**Production outcome reported by main (2026-09-29):** release Guard was installed on user 0 with the matching production certificate. The ordinary `dpm set-device-owner --user 0` attempt was refused because accounts already exist on the device (main reported: “Not allowed ... already some accounts on device.”). No production owner enrollment or Guard ACTIVATE success was reported. **Managed production hard mode is unavailable through the attempted account-preserving enrollment path on this phone.** Installing Guard, choosing FocusDeck as Home, or ordinary device-admin activation does not provide that authority. No PO retry, account removal, reset, setup-setting workaround or Accessibility change is planned.

**Post-refusal cleanup confirmed by main:** `dpm list-owners` is empty; Guard STATUS is DORMANT with scheduling disabled, `ownerBAL=false` and `exactAllowed=false`; the existing `com.stayfocused` admin is the sole enabled device admin; backup manager is enabled; LockTask is NONE; user 0 is current and the only remaining user; production FocusDeck is the default Home. Guard has no remaining owner/admin authority. These are main's verified observations, not a fresh device check by this agent.

[Plain-language recovery instructions](RECOVERY.md).

## What Android permits

Inspected AOSP `android-16.0.0_r1` `BackgroundActivityStartController` explicitly exempts the **device owner** and **affiliated profile owner** from background activity launch (BAL) restrictions. A non-affiliated profile owner in a full secondary user can be allowed to manage Lock Task without qualifying for this BAL exception. The former user-10 test configuration had no device owner to affiliate with, so profile ownership alone does **not** establish that Guard can interrupt another app at 23:00. Guard reports `ownerBAL=false`, refuses new bounded WAKE requests without reserving a budget, and consumes a delivered daily/previously-accepted alarm with `BAL_UNSUPPORTED_OWNER`, without pretending to foreground anything. It does not create affiliations, adopt the Home role, request overlays, or start a foreground service to evade this limit. [Android 16 BAL source](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/core/java/com/android/server/wm/BackgroundActivityStartController.java).

Alarm delivery and BAL authorization are separate. This implementation uses an immutable **broadcast** PendingIntent to Guard, then a direct `startActivity` from the qualifying DPC. It does not pass an activity PendingIntent to another app or rely on incidental temporary privileges granted by alarm delivery. Android can silently block a launch; returning from `startActivity` is recorded as `LAUNCH_REQUESTED_NOT_CONFIRMED`, never foreground success. [Official activity security/BAL guide](https://developer.android.com/guide/components/activities/secure-bal).

AlarmManager permits exact alarms only when `canScheduleExactAlarms()` allows them. Guard declares `SCHEDULE_EXACT_ALARM`, checks that method, and requests `setExactAndAllowWhileIdle` when eligible. It does not declare `USE_EXACT_ALARM` or open a permission screen automatically. If permission is missing/revoked at registration, **DAILY only** falls back to `setAndAllowWhileIdle` and reports `INEXACT_MAY_BE_DELAYED`. Bounded WAKE requires exact-alarm access: known absence refuses before reservation, and revocation during registration throws so the scheduler restores the previous record/budget instead of silently degrading. Rearming a previously accepted wake without exact access also fails; it never substitutes an inexact WAKE. Even idle-capable exact alarms have delivery quotas and can be delayed. Revoking exact-alarm access deletes exact alarms; Android broadcasts the permission-state action when access is **granted**, not as a reliable revocation callback. Consequently, after revocation there may be no daily alarm until the next supported reschedule event or explicit reactivation. This limitation is not hidden by a polling service. [AlarmManager reference](https://developer.android.com/reference/android/app/AlarmManager).

## Implemented lifecycle

Only a successful, explicit ACTIVATE arms scheduling. It happens after the policy is read back and its ACTIVE journal is committed. Registration/persistence failure causes the existing activation rollback path to run. Installing, enrolling, STATUS, or opening the UI never enables the schedule.

There are exactly two stable PendingIntent identities:

- **DAILY:** the next local 23:00, computed with `java.time` and the current time zone. A consumed alarm schedules the next calendar occurrence, rather than adding a fixed 24-hour interval. Repeating alarms are avoided because Android makes them inexact. An already-consumed local date is not repeated after a backward clock adjustment.
- **WAKE:** at most one one-shot timestamp requested by the authenticated launcher. It is consumed once and is never repeated automatically.

`GuardScheduleReceiver` is **not exported** and is direct-boot-aware. It handles its own immutable alarms and system BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED, MY_PACKAGE_REPLACED and exact-alarm-grant broadcasts. It reschedules only when both the persisted schedule latch and freshly verified policy are ACTIVE. Generation/time checks reject obsolete alarm deliveries after rescheduling. Data lives in device-protected private preferences, independent of the launcher process and vault, with existing backup exclusions.

BOOT/TIME/TIMEZONE/update events recalculate **future** alarms only. They do not immediately foreground the launcher, and past one-shot deadlines are discarded. Upgrading an old ACTIVE Guard that has no schedule latch does not silently enable it; main must RECOVER then explicitly ACTIVATE the new version. Reboot clears OS alarms and broadcast delivery is conditional on app/user lifecycle; force-stop, a stopped user, permission revocation and a crash between persistence and registration remain gaps. A boot/update/time event or repeated identical wake request can repair appropriate future registrations. [Official alarm scheduling guidance](https://developer.android.com/develop/background-work/services/alarms).

Every delivered alarm freshly checks owner/policy/default Home/certificate, supported BAL authority, current foreground Android user, credential-unlocked state, keyguard state and interactive screen. The fixed target is:

- release: `dev.stanislav.focusdeck/dev.stanislav.focusdeck.MainActivity`
- debug: `dev.stanislav.focusdeck.qa/dev.stanislav.focusdeck.MainActivity`

No caller can supply a component, Intent, package, URI or flags. The launch uses MAIN/LAUNCHER and NEW_TASK/RESET_TASK_IF_NEEDED; it does not clear the task, set lock-task options, dismiss keyguard, turn the screen on, or set `showWhenLocked`. Main must preserve its current lack of show-over-keyguard flags. ACTIVATE resolves the unscoped MAIN/HOME intent in Guard's own Android user and requires the exact build-specific component above; it repeats the check during activation and before returning success. It includes both direct-boot-aware and direct-boot-unaware candidates so locked-boot resolution does not incorrectly treat the configured launcher as missing; launch eligibility still requires unlock. It never changes Home. STATUS reports `defaultHomeMatches=true/false`; an ACTIVE journal with Home drift becomes NEEDS_RECOVERY without changing installed policy. Existing scheduling eligibility then fails; an alarm/reschedule callback disables the schedule rather than launching into an unvalidated setup. Restore the intended Home deliberately and RECOVER/ACTIVATE to re-establish scheduling. A setup-time check cannot prevent later system changes, and selecting a default Home alone does not prove the current task is of HOME type. The main ritual engine decides whether a ritual is actually required and whether to call `startLockTask`.

When locked, asleep, or in a background user, the attempt is consumed with `LOCKED_OR_BACKGROUND_USER_SKIPPED`. There is **no minute polling and no guaranteed retry after ordinary keyguard unlock**. Main's resume/unlock gate remains necessary; if an unrelated app resumes after unlock, this implementation alone does not guarantee immediate capture. Daily deliveries more than seven hours late and wake deliveries more than fifteen minutes late are discarded. Coincident triggers within two seconds are coalesced. These limits prevent stale launches and must not be described as complete, unavoidable lockdown.

RECOVER first persists the schedule as disabled and attempts cancellation of **both** PendingIntents, before DPC policy removal. An alarm cancellation error cannot prevent policy recovery; it causes an unverified result rather than a false full-success report. Every remaining callback still checks actual ACTIVE policy. Admin disable/removal also attempts cancellation. No services, AccessibilityService, wake-lock permission, network or vault reads were added.

## Narrow main-app API: scheduleWake(epochMillis, reason)

This is a scheduling-only API, **not** a channel for ACTIVATE/RECOVER or general policy commands.

- Receiver: `dev.stanislav.focusdeck.guard/.WakeRequestReceiver`
- Action: `dev.stanislav.focusdeck.guard.SCHEDULE_WAKE`
- Required fields: long `epochMillis` (wall-clock UTC milliseconds), string `reason` exactly `FOCUS_GRACE` or `EMERGENCY`. Missing, unknown, lower-case or whitespace-padded reasons are refused. There is no default reason.
- Required permission: `dev.stanislav.focusdeck.guard.permission.SCHEDULE_WAKE`, declared by Guard at **signature** protection level.
- Main must share authenticated broadcast identity using `BroadcastOptions.setShareIdentityEnabled(true)`. Guard additionally checks the real sender UID equals the installed build-specific launcher UID **in this user**, and that its signing certificate matches Guard. Missing identity, an unrelated app signed with the same key, ordinary third-party apps and shell callers are refused. Do not add DUMP as an alternate route to this receiver.

Bounds shared by both reasons: ACTIVE schedule, matching policy/default Home/certificate, supported owner BAL and current exact-alarm access; `now < epochMillis <= now + 15 minutes`. The current device time zone defines local dates and windows.

- **FOCUS_GRACE:** accept only during `[23:00, 23:15)` local time, deadline at or before 23:15. One initial acceptance per local date.
- **EMERGENCY:** one initial acceptance per ritual window. Evening is `[23:00, next-day 06:00)` and uses the evening's date; morning is `[06:00, 23:00)`. A deadline may cross a window boundary, but must remain within 15 minutes. The budget belongs to the window when accepted, not the deadline. Main still enforces its own once-per-window access rules.
- A pending **future** wake can be repeated with identical epoch + reason (idempotent registration receipt) or moved earlier with the same reason. It cannot be extended or changed to the other reason, even if that reason has budget. `LEGACY` pending wakes cannot be replaced by either reason.
- Once a wake is consumed or its timestamp has passed, its budget remains spent. An identical past request is refused, not acknowledged as a fresh schedule. A different reason with an unused budget can now schedule: grace requested at 23:00 for 23:05, then emergency requested at 23:06 for 23:21, is accepted. A second emergency in that evening window is refused even after 15 minutes.
- Budgets are committed with the pending record before registration; failed registration attempts restore the previous record/alarm. Consumption, keyguard/BAL skips, process recreation and boot do not replenish budgets. Earlier replacement keeps the original budget spent. Local day/window high-water marks reject backward-clock replay; this is not a security boundary against deliberate clock/timezone changes or administrator actions. RECOVER disables/cancels both slots and clears scheduler state; explicit reactivation is administrative control outside the self-control limit.

Known missing BAL/exact prerequisites yield result 2 without changing the pending record or either reason budget. If exact access is revoked between preflight and registration, result 1 indicates registration not verified, with attempted restoration of the old state/alarm. An already-spent earlier budget is not refunded by a rejected retry. This prevents the known user-10 `ownerBAL=false` / `exactAllowed=false` case from consuming emergency/grace allowances. Revocation after acceptance, loss of a receipt, Main's own storage failure and later keyguard/user-state changes cannot be made atomic across the two apps; Main must persist its outgoing request and retry identical epoch+reason where still future, rather than create a later deadline after an uncertain response. Daily scheduling still supports degraded configurations.

Storage schema remains **2**. On first read, v1 is atomically migrated before further operations. Enabled state, generation, daily/wake timestamps and last attempt are preserved. An existing pending wake is labelled internal `LEGACY`, preserving its delivery identity. Since v1 has no reason, **both** budgets are reserved conservatively up to the later local date/window represented by its original request and pending deadline. Its original 15-minute cooldown is retained as an additional migration-only bound. A consumed v1 wake still reserves budgets from its saved request; an empty dormant record remains dormant. Unknown schema versions fail closed. Migration may deny a pause which a fresh v2 record would allow; it never invents a fresh budget for an existing v1 request. `LEGACY` and `NONE` are status/storage values, never accepted public reasons.

Main decides actual ritual eligibility and persists the accepted deadline/receipt before relying on return scheduling; Guard validates the named scheduling bounds without reading vault or completion markers. Handle result 1/2 and `ownerBAL=false` honestly. An alarm registration receipt is not a guarantee of delivery, precision, foreground launch or successful Lock Task.

Main manifest integration (main agent only):

```xml
<uses-permission android:name="dev.stanislav.focusdeck.guard.permission.SCHEDULE_WAKE" />
```

Kotlin call, Android 16+ (uses literal protocol constants; no Guard library dependency):

```kotlin
fun scheduleGuardWake(context: Context, epochMillis: Long, reason: String, onResult: (Int, String?) -> Unit) {
    if (Build.VERSION.SDK_INT < 36) {
        onResult(-1, "GUARD_UNSUPPORTED_ANDROID_VERSION")
        return
    }
    require(reason == "FOCUS_GRACE" || reason == "EMERGENCY")
    val intent = Intent("dev.stanislav.focusdeck.guard.SCHEDULE_WAKE")
        .setComponent(ComponentName(
            "dev.stanislav.focusdeck.guard",
            "dev.stanislav.focusdeck.guard.WakeRequestReceiver",
        ))
        .putExtra("epochMillis", epochMillis)
        .putExtra("reason", reason)
    val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
    context.sendOrderedBroadcast(
        intent,
        null, // This argument restricts the receiver; sender auth is the receiver's manifest permission.
        options,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                onResult(resultCode, resultData)
            }
        },
        Handler(Looper.getMainLooper()),
        -1, // Remains -1 if no authorized receiver produced a result: never treat that as success.
        null,
        null,
    )
}
```

Result 0: alarm registered (`WAKE_SCHEDULED`), with scheduler diagnostics. Result 2: refused sender/action/reason/inactive/prerequisites/bounds/budget; result 1: registration not verified. Main supports API 31+, but Guard supports API 36+ only; keep the version check before using this API. Handle permission exceptions, absent receivers, unchanged initial result and callback timeout. The result callback is only a receipt; do not grant temporary access on an assumed successful return from `sendOrderedBroadcast`.

## Status and QA handoff

The existing DUMP-protected STATUS/ACTIVATE/RECOVER responses now append: `Schedule enabled`, `next23`, `wake`, `reason`, `schema=2`, `exactAllowed`, `ownerBAL`, `precision`, and `last`. These are schedule records and current capability checks; `next23/wake` are not independent inspection of AlarmManager's queue, and diagnostic strings may describe a previous attempt. The former user-10 configuration had `ownerBAL=false`; selecting Home did not grant owner BAL. No affiliation is created here.

Reference QA for a separately authorized supported test environment (user 10 has been removed; do not recreate it or retry user-0 enrollment as part of this handoff):

1. Preserve a known recovered/no-active-policy checkpoint and independent RECOVER/removal commands. Use a matching Guard variant in a disposable test environment without altering personal user 0. Review the new permissions; verify no network/service/Accessibility additions.
2. Verify cold STATUS shows schedule disabled. Before activation, deliberately select the matching launcher as default Home in that test user. Verify wrong/missing Home refuses ACTIVATE before policy/journal writes. Confirm ACTIVATE creates only two fixed-policy effects plus the daily alarm; `next23` is correct for the current zone. Test permitted and denied exact-alarm access separately, with the latter explicitly degraded.
3. Add Main's narrow permission/call. Test known missing BAL/exact prerequisites refuse without spending budgets (as in the former user-10 configuration), then separately test successful scheduling in a genuinely supported owner configuration. Test requests from correct QA UID/signature, missing identity, another same-signed app UID, wrong signer, absent action, past time, >15-minute time, absent/wrong reason, repeated same deadline+reason, attempted extension/reason switch, grace outside 23:00–23:15, and second emergency in the same window. Verify grace 23:00→23:05 followed by emergency 23:06→23:21 succeeds in local unit tests. Verify v1 migration preserves a pending wake and spent budgets. None may alter Guard policy.
4. On a supported device-owner/affiliated-owner test environment, verify actual foreground transition while another app is open. For an unaffiliated full-user PO configuration equivalent to the former user 10, verify `BAL_UNSUPPORTED_OWNER` and **do not mark foreground-at-deadline solved**. Avoid synthetic minute-by-minute alarms on the personal device.
5. Test locked/off-screen and background-user skips, launcher/main process death, duplicate deliveries, cold Guard process, future wake after reboot, timezone/DST recomputation and missed-deadline behavior. Main's gate should re-evaluate requirements after any real launch, including already-complete rituals.
6. RECOVER with both slots present; verify both are canceled and stale deliveries cannot launch/rearm. Confirm DPC policy baseline and task exit separately. Never change system time or reboot the user's phone solely to test the pure date arithmetic; local unit tests cover those calculations.

All device steps are proposals for main, not actions performed by this agent. See `VERIFICATION.md` for local results.

# Verification record

## Latest checkpoint — 03:45–03:46 Minsk, reported by main

Main repeated the production restrictions/recovery checks **after cleanup, at 03:45–03:46 Minsk**: user 0 is current and the only user; `dpm list-owners` is empty; Guard STATUS is `DORMANT` with no eligible owner, `Schedule enabled=false`, `next23=0`, `wake=0`, `exactAllowed=false` and `ownerBAL=false`; LockTask is `NONE`; backup manager is enabled. The sole enabled device admin remains the unchanged StayFocused (`com.stayfocused`). Guard has no owner/admin authority and no active schedule. These are main's reported device observations; this agent did not repeat device commands.

Production FocusDeck **1.1.0, pass19** was installed at **03:38 Minsk**, with its hash verified by main. This is an installation checkpoint, not the final build designation: main's small weight fix/pass20 is underway; its completion or installation is not claimed here.

**Visual verification on user 0 remains pending user unlock:** the primary keyguard is still locked. Installation/hash verification and the restrictions checkpoint do not establish a visual Home/ritual UI pass. Production managed hard mode and guaranteed alarm return are not claimed. This update changes Guard documentation only, with no source, build or device changes.

The restrictions agent has not installed, enrolled, activated, recovered, switched users, or changed settings on any device for this implementation. No account/removal/reset/root/Accessibility actions occurred.

## Hardware checkpoint reported by main, preserved during scheduling work

Earlier QA report from main, **not a device check by this agent**: schema-2 Guard debug was profile owner in the disposable full secondary **user 10** during QA. ACTIVATE applied mask 112 and QA entered actual `LOCKED`. With PixelLauncher still the default Home, injected Home opened BlockedAppActivity and Back exposed the existing PixelLauncher while the mode remained LOCKED. Main recovered, explicitly made QA the default Home, reactivated, and launched via Home. Main then reports Recents/Home/bottom swipe/shade swipe/Back all remained in QA with LOCKED; after force-stopping QA, independent RECOVER returned the mode to NONE. **Earlier cleanup checkpoint reported by main: RECOVER and admin removal completed; disposable user 10 removed; user 0 current and the only remaining user; Guard debug uninstalled; backup enabled. That was the pre-enrollment cleanup checkpoint; the subsequent user-0 enrollment refusal and confirmed post-refusal cleanup are recorded below.** These observations distinguish the earlier privileged injected-key path from the later navigation retest. This agent did not run ADB or change any Home/account/owner setting. The new default-Home preflight and scheduling-prerequisite code below still needs main's device validation.

Standalone build/check tasks were invoked again after main's cleanup: BUILD SUCCESSFUL (incremental; existing 75-test results remain passing), both APK signatures reverified against the current corresponding app APKs, and packaged manifests rechecked. No root build or ADB command was run. Artifact hashes below are unchanged.

## Personal-user enrollment result

**Production outcome reported by main (2026-09-29):** release Guard was installed on user 0 with the matching production certificate. The ordinary `dpm set-device-owner --user 0` attempt was refused because accounts already exist on the device (main reported: “Not allowed ... already some accounts on device.”). No production owner enrollment or Guard ACTIVATE success was reported. **Managed production hard mode is unavailable through the attempted account-preserving enrollment path on this phone.** Installing Guard, choosing FocusDeck as Home, or ordinary device-admin activation does not provide that authority. No PO retry, account removal, reset, setup-setting workaround or Accessibility change is planned.

**Post-refusal cleanup confirmed by main:** `dpm list-owners` is empty; Guard STATUS is DORMANT with scheduling disabled, `ownerBAL=false` and `exactAllowed=false`; the existing `com.stayfocused` admin is the sole enabled device admin; backup manager is enabled; LockTask is NONE; user 0 is current and the only remaining user; production FocusDeck is the default Home. Guard has no remaining owner/admin authority. These are main's verified observations, not a fresh device check by this agent.

[Plain-language recovery instructions](RECOVERY.md).

## Current scheduler build — 2026-09-29 (default Home preflight, WAKE prerequisites, schema 2)

Standalone `testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease` succeeded after the scheduler changes. **75 tests passed**, 0 failures/errors/skips: 38 policy/lifecycle/command tests, 31 wake-scheduler tests, 4 journal tests and 2 manifest/backup-boundary tests. Coverage includes wrong/missing/chooser Home refusal before any writes, Home drift during/following activation, recovery despite failed Home resolution, missing owner BAL/exact access without budget consumption, exact-access revocation during WAKE registration with budget rollback, future local 23:00, DST, timezone/time changes, dormant boot, stale/duplicate alarms, skipped keyguard/background users, unsupported profile-owner BAL, reason-specific durable budgets, grace 23:00→23:05 followed by emergency 23:06→23:21, emergency repeat rejection, exact 06:00/23:00 window boundaries, missing/wrong reason, v1 pending/consumed/dormant migration, bounded wake/extension checks, process recreation, and cancellation on recovery even when persistence fails.

Both lint runs have 0 errors and 20 warnings (SDK/tool/dependency update and untranslated administrative text/catalog suggestions). The prior scheduler packaged APK inspection verified testOnly, DUMP protection, the narrow signature scheduling permission, exact-alarm declaration, and absence of INTERNET permission/services. Signature verification passed again for the schema-2 artifacts: debug matches the existing QA APK and release matches the existing production APK.

Current APK SHA-256:

- `build/outputs/apk/debug/FocusDeckGuard-debug.apk`: `2bedffef36e2ed5a0b0ffb0a7f6281b1d4f7739460a174c1c9b51d53c328729d`
- `build/outputs/apk/release/FocusDeckGuard-release.apk`: `65588f4511921e47c108b45e3aed10df72f1c822d6a6daf9ca892b7fa4242dbe`

These replace the historical artifact hashes below. New default-Home resolution (including before first unlock), prerequisite refusal, SharedPreferences migration commit and OS delivery remain device-unverified by this agent; pure migration transformations are unit-tested. No device-level alarm delivery, signature-broadcast caller enforcement, exact-alarm grant/revocation, BAL, foreground transition, or recovery cancellation has been tested by this agent. In particular, the former user-10 test configuration's full-user profile ownership does not establish the affiliated-owner BAL exception. The main agent should integrate the precise protocol in `SCHEDULING.md` and preserve that limitation in test conclusions.

## Initial mini-DPC local results — 2026-09-29 (before scheduling)

Standalone command succeeded:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/stanislav/Library/Android/sdk' \
./gradlew -p guard testDebugUnitTest assembleDebug assembleRelease lintDebug lintRelease --console=plain
```

- 35 JVM tests passed, 0 failures/errors/skips: 29 controller/privileged-command tests, 4 journal codec tests, 2 manifest/backup boundary tests. These cover the ACTIVATE dispatcher and fixed policy, owner/certificate/baseline refusal, rollback failures, independent recovery with lost/corrupt state, variant separation, and protection of exported receivers. They do not execute Android's Binder permission checks.
- Debug and signed release APKs built successfully.
- Both lint runs: 0 errors, 20 warnings. Remaining categories concern SDK/tool/dependency updates, using a version catalog, and untranslated text in this small administrative UI. Backup/data-extraction and missing-icon findings were fixed.
- APK signature verification passed. Public signing-certificate digests were compared against the existing `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`: debug Guard matches QA; release Guard matches production; debug and release keys differ. No keystore secret was displayed. Gradle alone consumed the configured signing properties.
- Inspected both packaged manifests, not just source: `testOnly=true`, DUMP-protected recovery/activation receiver, no INTERNET permission, explicit backup/transfer exclusions; release is not debuggable.
- No changes to root settings/build files or existing app source by this agent; all implementation changes are under `guard/`.

Artifacts from this verification:

| Variant | Path | APK SHA-256 |
| --- | --- | --- |
| QA/debug | `build/outputs/apk/debug/FocusDeckGuard-debug.apk` | `2b9d51e0494104f0f096633fd7002a85b4d3fcae87fe7c7df15e0a3ce23b24ca` |
| Production/release | `build/outputs/apk/release/FocusDeckGuard-release.apk` | `4ee44634abf1f99c4ee1f50c2eb316beadbb37906e762e054e37f9d78f62eb20` |

Rebuilding can change APK hashes; re-check before testing. A separate release unit-test task is not exposed by the current AGP configuration; build-variant selection is covered explicitly in the JVM controller tests, and both actual APK variants were built and inspected.

Historical pre-scheduling verification limits (main's later reported results at the top supersede these where applicable): installation and testOnly removal, negative sender permission test from an ordinary third-party UID, credential confirmation UI, owner enrollment with existing accounts, full-user profile-owner mode, policy callbacks/read-back on Pixel, actual LOCKED navigation enforcement, emergency/system UI paths, independent recovery while locked/cold/before unlock, reboot behavior, and all coexistence/rollback effects on the installed build.

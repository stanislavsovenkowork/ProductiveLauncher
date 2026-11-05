<!-- FOCUSDECK-PROJECT-CONTRACT-20261103:BEGIN -->
## ОБЯЗАТЕЛЬНЫЙ КОНТРАКТ ПОЛЬЗОВАТЕЛЯ — ТОЛЬКО PRODUCTIVELAUNCHER / FOCUSDECK

ЭТИ ПРАВИЛА ДЕЙСТВУЮТ ТОЛЬКО ДЛЯ ЭТОГО ПРОЕКТА. ОБЩИЕ НАСТРОЙКИ CODEX И ДРУГИЕ ПРОЕКТЫ НЕ МЕНЯТЬ. ПРИ ПРОТИВОРЕЧИИ СО СТАРЫМИ ПРОЕКТНЫМИ ПЛАНАМИ ПРИМЕНЯТЬ ЭТО ПРЯМОЕ УКАЗАНИЕ ПОЛЬЗОВАТЕЛЯ ОТ 3 НОЯБРЯ 2026.

КАЖДОЕ СООБЩЕНИЕ ПО ПРОЕКТУ, ВКЛЮЧАЯ COMMENTARY, ИТОГИ, ВОПРОСЫ, ПОРУЧЕНИЯ И ОТЧЁТЫ СУБАГЕНТОВ, НАЧИНАТЬ ТРЕМЯ КОРОТКИМИ ПУНКТАМИ КАПСОМ:
1. КОНЕЧНАЯ ЦЕЛЬ — ПОЛЕЗНЫЙ, ГОТОВЫЙ К ИСПОЛЬЗОВАНИЮ FOCUSDECK НА MAC И PIXEL СО СТОЙКОЙ СИСТЕМНОЙ БЛОКИРОВКОЙ. НАЗВАТЬ КОНКРЕТНЫЙ ИНКРЕМЕНТ ТЕКУЩЕГО ШАГА, ЕГО ПОЛЬЗУ И ПРОВЕРЯЕМЫЙ РЕЗУЛЬТАТ: ЧТО ИМЕННО ПОЛЬЗОВАТЕЛЬ ПОЛУЧИТ В ПРОГРАММЕ. ОБЪЯСНИТЬ, ПОЧЕМУ ЭТОТ ШАГ ДАЁТ ПРИКЛАДНОЙ РЕЗУЛЬТАТ, А НЕ ЕЩЁ ЧАС ПОДГОТОВКИ. НЕ ОБЕЩАТЬ НЕПРОВЕРЕННУЮ ГОТОВНОСТЬ; ДЛЯ ШАГА ТОЛЬКО С ИНСТРУКЦИЯМИ ПРЯМО УКАЗАТЬ, ЧТО ФУНКЦИЮ ОН НЕ ДОБАВЛЯЕТ.
2. ПРИОРИТЕТ — ПИСАТЬ ПРИКЛАДНОЙ КОД И ДАВАТЬ ГОТОВЫЙ ПРОДУКТ. ПРОВЕРКИ МИНИМАЛЬНО НЕОБХОДИМЫЕ, ПРЕЖДЕ ВСЕГО В ИТОГОВОМ ПРИЛОЖЕНИИ НА РЕАЛЬНОМ MAC/PIXEL. ПОЛЬЗОВАТЕЛЬ ЯВНО ПРИНИМАЕТ ТЕХНИЧЕСКИЕ РИСКИ И РИСКИ БЕЗОПАСНОСТИ ЭТОЙ РАЗРАБОТКИ. НЕ ПОДМЕНЯТЬ ПРОДУКТ НОВЫМИ ФИКСТУРАМИ, ДЕМОНСТРАЦИОННЫМИ ПРОГРАММАМИ, АУДИТАМИ, ОТЧЁТАМИ И ПЕРЕСТРАХОВОЧНЫМИ ПРОЦЕССАМИ. НЕ ЗАПРАШИВАТЬ ПОВТОРНО ОБЩИЕ РАЗРЕШЕНИЯ. РЕАЛЬНЫЕ ОГРАНИЧЕНИЯ ПЛАТФОРМЫ/ИНСТРУМЕНТА СОБЛЮДАТЬ И НАЗЫВАТЬ ТОЧНО.
3. ТРЕБОВАНИЕ — STOP, QUIT, СМЕНА БРАУЗЕРА И ОБЫЧНОЕ УДАЛЕНИЕ НЕ ДОЛЖНЫ БЫСТРО СНИМАТЬ ЗАПРЕТ; ДОСРОЧНОЕ СНЯТИЕ ДОЛЖНО ТРЕБОВАТЬ СУЩЕСТВЕННОГО ВРЕМЕНИ.

ТРЕТИЙ ПУНКТ ПОВТОРЯТЬ ДОСЛОВНО. НЕ ЗАМЕНЯТЬ ЕГО ФРАЗАМИ ОБ ОТСУТСТВИИ КНОПКИ ИЛИ О ЗАКРЫТИИ ИНТЕРФЕЙСА. СТОЙКОСТЬ К ОБХОДАМ, ОТКЛЮЧЕНИЮ И УДАЛЕНИЮ — ОБЯЗАТЕЛЬНЫЙ КРИТЕРИЙ ПРИЁМКИ, А НЕ УТВЕРЖДЕНИЕ О НАЛИЧИИ КОДА. ОРИЕНТИР ПОЛЬЗОВАТЕЛЯ ДЛЯ СУЩЕСТВЕННОГО ВРЕМЕНИ — ОКОЛО ЧАСА. ЧЕСТНО РАЗДЕЛЯТЬ НАПИСАНО, УСТАНОВЛЕНО И ПРОВЕРЕНО.

ЭТИ ТРИ ПУНКТА ПЕРЕДАВАТЬ ДОСЛОВНО В КАЖДОМ НОВОМ ПОРУЧЕНИИ ИСПОЛНИТЕЛЮ И СОХРАНЯТЬ ПРИ КОМПАКЦИИ/ПЕРЕДАЧЕ КОНТЕКСТА. ПО ПОСЛЕДНЕМУ ПРЯМОМУ УКАЗАНИЮ ПОЛЬЗОВАТЕЛЯ ROOT САМ ПИШЕТ ПРИКЛАДНОЙ КОД; ПРЕЖНЕЕ ОГРАНИЧЕНИЕ ТОЛЬКО КООРДИНАЦИЕЙ ОТМЕНЕНО. COMPUTER USE ДЕЛЕГИРОВАТЬ ТОЛЬКО GPT-6.1-TERRA XHIGH, БЕЗ ЗАПРОСА FAST. ТЕКУЩИЙ ЭТАП ТОЛЬКО MAC, PIXEL НЕ ТРОГАТЬ. ПАРАЛЛЕЛИТЬ ТОЛЬКО НЕЗАВИСИМЫЕ ПРИКЛАДНЫЕ ИНКРЕМЕНТЫ С ВЛАДЕЛЬЦЕМ И КРИТЕРИЕМ ГОТОВНОСТИ. КАЖДАЯ ИТЕРАЦИЯ ДОЛЖНА ЗАКАНЧИВАТЬСЯ ИЗМЕНЕНИЕМ В РЕАЛЬНОЙ ПРОГРАММЕ И КОРОТКОЙ ПРОВЕРКОЙ; ЕСЛИ ЭТО НЕДОСТИЖИМО, СРАЗУ НАЗВАТЬ ПРЕПЯТСТВИЕ И НЕ ПРОДОЛЖАТЬ БЕЗРЕЗУЛЬТАТНЫЙ РАСХОД.

ПРИНЯТИЕ РИСКОВ НЕ ОТМЕНЯЕТ ПРЯМОГО ТРЕБОВАНИЯ СОХРАНЯТЬ ДАННЫЕ И СВЯЗЬ: MAC ПОЛУЧАЕТ ИНТЕРНЕТ ЧЕРЕЗ PIXEL. НЕ ПЕРЕЗАГРУЖАТЬ ТЕЛЕФОН И НЕ МЕНЯТЬ РАЗДАЧУ, USB, VPN И МАРШРУТЫ. СОХРАНЯТЬ ПРОЗРАЧНЫЙ КОНЕЧНЫЙ ВЫХОД; НЕ ОБЕЩАТЬ НЕДОКАЗАННУЮ АБСОЛЮТНУЮ ЗАЩИТУ ОТ ВЛАДЕЛЬЦА С ROOT. НЕ ВЫДАВАТЬ ЗАПИСАННЫЕ ТРЕБОВАНИЯ, СБОРКУ ИЛИ ЧИСЛО ТЕСТОВ ЗА УСТАНОВЛЕННУЮ ЗАЩИТУ И ДОКАЗАННУЮ ПОЛЬЗУ.
<!-- FOCUSDECK-PROJECT-CONTRACT-20261103:END -->

# FocusDeck Guard — dormant mini-DPC

## Latest checkpoint — 03:45–03:46 Minsk, reported by main

Main repeated the production restrictions/recovery checks **after cleanup, at 03:45–03:46 Minsk**: user 0 is current and the only user; `dpm list-owners` is empty; Guard STATUS is `DORMANT` with no eligible owner, `Schedule enabled=false`, `next23=0`, `wake=0`, `exactAllowed=false` and `ownerBAL=false`; LockTask is `NONE`; backup manager is enabled. The sole enabled device admin remains the unchanged StayFocused (`com.stayfocused`). Guard has no owner/admin authority and no active schedule. These are main's reported device observations; this agent did not repeat device commands.

Production FocusDeck **1.1.0, pass19** was installed at **03:38 Minsk**, with its hash verified by main. This is an installation checkpoint, not the final build designation: main's small weight fix/pass20 is underway; its completion or installation is not claimed here.

**Visual verification on user 0 remains pending user unlock:** the primary keyguard is still locked. Installation/hash verification and the restrictions checkpoint do not establish a visual Home/ritual UI pass. Production managed hard mode and guaranteed alarm return are not claimed. This update changes Guard documentation only, with no source, build or device changes.

**Production outcome reported by main (2026-09-29):** release Guard was installed on user 0 with the matching production certificate. The ordinary `dpm set-device-owner --user 0` attempt was refused because accounts already exist on the device (main reported: “Not allowed ... already some accounts on device.”). No production owner enrollment or Guard ACTIVATE success was reported. **Managed production hard mode is unavailable through the attempted account-preserving enrollment path on this phone.** Installing Guard, choosing FocusDeck as Home, or ordinary device-admin activation does not provide that authority. No PO retry, account removal, reset, setup-setting workaround or Accessibility change is planned.

**Post-refusal cleanup confirmed by main:** `dpm list-owners` is empty; Guard STATUS is DORMANT with scheduling disabled, `ownerBAL=false` and `exactAllowed=false`; the existing `com.stayfocused` admin is the sole enabled device admin; backup manager is enabled; LockTask is NONE; user 0 is current and the only remaining user; production FocusDeck is the default Home. Guard has no remaining owner/admin authority. These are main's verified observations, not a fresh device check by this agent.

The disposable user-10 QA did demonstrate LOCKED navigation after QA became default Home and independent recovery after QA was force-stopped. That user has since been removed. This does **not** establish production enrollment, supported owner BAL, reliable 23:00 return or an unavoidable lock. Without owner authority, the launcher gate and optional removable screen pinning remain limited modes. **No device mutation was performed by the restrictions agent.**

For plain-language recovery and the current refusal case, see [Восстановление доступа](RECOVERY.md).

## Scope and exact behavior

- Package/namespace: `dev.stanislav.focusdeck.guard`, minimum Android 16 / API 36.
- `android:testOnly="true"` in every build, including release. ADB installation needs `-t`; test-only administrator removal is the independent final escape.
- Native status/setup/recovery activity. No network permission, analytics, service, AccessibilityService, vault access, account API, wipe policy or wipe call. Explicitly ACTIVE Guard has a daily 23:00 alarm and one bounded signed-launcher wake slot; see `SCHEDULING.md` for BAL/precision/locked-screen limits.
- Ordinary device-admin activation is insufficient. Every policy operation checks that Guard is device owner, or a profile owner in a **full user**, not a managed work profile. Android still checks affiliation and other-owner constraints on each DPM call.
- Enrollment never activates Guard policy; disabling the admin attempts schedule cancellation. UI activation requires a local confirmation dialog followed by Android's credential confirmation. Authorized ADB can instead invoke the fixed ACTIVATE operation through the DUMP-protected receiver without another credential dialog. Activity intent extras are ignored. Obscured touches are rejected and application overlays/screenshots are suppressed on the setup screen.
- Activation writes only Lock Task features and packages. Release permits only `dev.stanislav.focusdeck`; debug permits only `dev.stanislav.focusdeck.qa`. The installed target must pass `PackageManager.checkSignatures(guard, target) == SIGNATURE_MATCH` before activation and after policy application. A missing or differently signed target is rejected. Features are exactly `KEYGUARD | GLOBAL_ACTIONS | BLOCK_ACTIVITY_START_IN_TASK` (112). HOME, OVERVIEW and NOTIFICATIONS are omitted. The power menu and lock screen remain available by design.
- Guard does **not** call `startLockTask()`, select a Home app, disable packages, block uninstall, modify clocks, disable debugging or impose safe-mode restrictions. An eligible scheduled trigger can open only its fixed signed launcher while unlocked in the foreground user. The main app must actually enter `LOCK_TASK_MODE_LOCKED`; a DPC allowlist by itself does not close navigation.
- Policy, once explicitly activated, can persist across app/process death and reboot through Android. Guard does not automatically reapply it. It restores future alarm registrations only for a previously ACTIVE schedule with matching current policy. The journal remains available before first unlock using device-protected storage. Neither fact proves that a task will re-enter Lock Task after reboot.

The device credential is an intentional local administrative escape, not a separate accountability secret. The activity is not in the Lock Task allowlist, so its recovery screen may be unreachable while the launcher is locked. ADB recovery does not need that screen. Power controls, authorized ADB, safe mode, physical reset and deliberate administrator recovery remain outside a promise of unavoidable self-binding.

## Build without changing the parent project

This directory is both a standalone Gradle project and a potential parent submodule. No root settings/build files or existing app source were changed. From the repository root on this Mac:

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/stanislav/Library/Android/sdk' \
./gradlew -p guard testDebugUnitTest assembleDebug lintDebug --console=plain
```

Debug output: `guard/build/outputs/apk/debug/FocusDeckGuard-debug.apk` when standalone. Release signing is configured to consume the existing repository `keystore.properties` through Gradle, using the same fields and repository-relative store path as the main app. Secret values are not printed, copied into source, or compiled into the APK. Debug uses the usual separate Android debug key. Release has no fallback to debug signing; prepare it with `assembleRelease` and compare public signing-certificate digests to the main release APK before installation. Debug and release Guard share a package name but use different keys: recover and remove the test-only admin before deliberately replacing variants; never attempt a signature-changing update over an enrolled owner.

Android package code/signing identity is shared across users. Debug and release Guard cannot coexist under this same package name even in different Android users. Complete recovery and deliberate test-variant cleanup before installing release Guard; removing a profile owner alone does not replace the installed APK or its signing identity.

For parent integration, the main agent can add `include(":guard")` to root `settings.gradle.kts`; the parent's Android application plugin already supplies the same AGP version. Then use `:guard:assembleDebug`. No `implementation(project(":guard"))` app dependency is needed: this is a separately installed application. Do not silently remove `testOnly`, change signing keys after enrollment, or enable backup. See `INTEGRATION.md` for the concrete launcher/Guard protocol.

## Saved state, rollback and policy coexistence

Android 14+ public `getLockTaskPackages/getLockTaskFeatures` return the **resolved** policy across administrators, not a complete per-admin history. Writing that result back as a policy owned by Guard could override someone else's administration. Guard therefore refuses activation unless the resolved baseline is empty packages plus the default GLOBAL_ACTIONS feature mask (16), and no task is currently pinned/locked. It does not support taking over a pre-existing kiosk policy. ACTIVATE also requires the exact build-specific MainActivity to resolve as default Home in Guard's Android user, before any journal/policy write and again during/finally after activation. Missing Home, a chooser, another launcher, or a different component produces a refusal; Guard never changes the user's Home. STATUS exposes `defaultHomeMatches`, reports later drift as NEEDS_RECOVERY, and does not remove policy automatically. RECOVER does not require Home resolution.

Before the first DPM write, it atomically saves and reads back a versioned snapshot with phase PREPARED. It applies features, applies packages, checks exact resolved values, records ACTIVE, then arms the schedule. Any application/read-back/journal/schedule-registration failure attempts recovery. Failed or interrupted transactions retain their baseline and prevent another activation from overwriting it. A fresh process can recover from PREPARED without an Activity or vault.

Recovery first disables/cancels both alarm slots, then calls `setLockTaskPackages(admin, emptyArray)` to **remove Guard's own Lock Task policy**. On the inspected Android 16 implementation, that removes both its packages and features. It does not write old feature flags as a new policy, or alter another administrator. It checks the newly resolved values against the saved baseline and marks RESTORED only when they match, journal commit succeeds and schedule cancellation was verified. It never reports that a task has exited merely because policy read-back matches; check `dumpsys activity` separately.

If the baseline is absent/corrupt, recovery still attempts removal of Guard's policy and returns UNVERIFIED. If another administrator now imposes a different policy, it reports NEEDS_RECOVERY and leaves that policy alone. Storage failure does not prevent attempted removal; it can prevent proof of durable restoration. Do not delete app data to repair a pending journal. Keep the record and use the test-only admin removal fallback if needed. Snapshot/restore covers **Guard's two Lock Task settings only**, not all side effects of becoming a device/profile owner (such as enrollment's backup-service behavior).

`GuardPolicyReceiver` records Android's policy-result callbacks as diagnostic codes only. Delayed callbacks cannot reactivate policy or undo a newer recovery. Fresh policy read-back is authoritative for the UI at that instant; runtime task mode and later policy changes still need verification.

## Independent ADB recovery and its security boundary

`AdbRecoveryReceiver` is exported with `android:permission="android.permission.DUMP"`. The OS authenticates the sender before delivery. Android's shell declares DUMP; ordinary third-party applications cannot obtain its signature/privileged/development permission on their own. The receiver exposes exactly ACTIVATE, STATUS and RECOVER. ACTIVATE takes **no parameters**: fixed baseline, build-specific target, certificate checks and feature mask are enforced by the same transaction as the UI. Intent extras cannot override packages, features, user, credentials, journal or paths. This is not a normal-app command channel.

There is **no bearer token** to leak via shell history, process arguments, broadcasts, logs or backups. A system-privileged app, or an app to which the device owner deliberately grants DUMP via ADB, is inside the administrative recovery trust boundary. This is not a defence against the ADB owner. Do not add unprotected intent aliases or use a normal/custom permission that other apps can request. `--receiver-permission` on an ADB command constrains the receiver, not the sender, and is not a replacement for the manifest boundary.

These operations are for the main agent to run deliberately; none were run by this agent. Specify the **user where Guard is enrolled**, especially during secondary-user QA:

```sh
# Set these explicitly for each test. Serial here is the user's Pixel, not a spare device.
GUARD_SERIAL=62051JEA324712
GUARD_USER=0

# Read-only app status. Including stopped packages allows a cold Guard process to receive it.
adb -s "$GUARD_SERIAL" shell am broadcast --user "$GUARD_USER" \
  --receiver-foreground --include-stopped-packages \
  -n dev.stanislav.focusdeck.guard/.AdbRecoveryReceiver \
  -a dev.stanislav.focusdeck.guard.STATUS

# Actual recovery mutation: removes only Guard's Lock Task contribution.
adb -s "$GUARD_SERIAL" shell am broadcast --user "$GUARD_USER" \
  --receiver-foreground --include-stopped-packages \
  -n dev.stanislav.focusdeck.guard/.AdbRecoveryReceiver \
  -a dev.stanislav.focusdeck.guard.RECOVER

# Explicit test/setup activation, AFTER independent recovery is proven.
# DUMP authorization replaces only the UI dialog; all preflight checks still apply.
adb -s "$GUARD_SERIAL" shell am broadcast --user "$GUARD_USER" \
  --receiver-foreground --include-stopped-packages \
  -n dev.stanislav.focusdeck.guard/.AdbRecoveryReceiver \
  -a dev.stanislav.focusdeck.guard.ACTIVATE

# Verify task state independently of receiver result.
adb -s "$GUARD_SERIAL" shell dumpsys activity activities | \
  rg 'mLockTaskModeState|mLockTaskModeTasks|mLockTaskPackages'
```

For RECOVER, ordered-broadcast result `0` plus `RESTORED` means saved policy restoration was verified. For ACTIVATE, `0` plus ACTIVE means policy/certificate read-back matched, not that a task is locked. `1` means incomplete/unverified recovery; `2` means dormant/refused/unsupported (including not being owner). STATUS is diagnostic; read its text/code, not just the shell exit code. `am` exiting successfully merely means the broadcast command ran. A missing receiver, permission denial, stopped-user issue, process crash or timeout is **not** proof of recovery.

If Guard cannot run, its test-only receiver remains removable through Android's system service:

```sh
# Independent fallback; removes owner/admin and the policies belonging to it.
adb -s "$GUARD_SERIAL" shell dpm remove-active-admin --user "$GUARD_USER" \
  dev.stanislav.focusdeck.guard/.GuardAdminReceiver
adb -s "$GUARD_SERIAL" shell dpm list-owners
```

Use normal RECOVER before removal when possible, so restoration is verified. Removal has broader effects than the normal recovery operation: it unenrolls Guard and removes its administration. Validate enrollment/removal side effects in QA before relying on this on the personal user. Do not uninstall first, clear its data, remove accounts, reset the device, or assume a future non-test-only build can be removed this way.

## Reference provisioning and validation sequence (separate test environment only)

The user-0 attempt described above has already been refused. This reference is not an instruction to retry enrollment on the personal user or modify its accounts/settings.

1. Review source, merged manifest and APK signing. Keep ADB authorized and a known working cable/computer available. Stage the same signed Guard build and independent recovery commands. Back up relevant launcher/vault data through the existing process; Guard itself does not touch it.
2. Use a spare Android 16 device/emulator first. Install Guard, confirm dormant status, and send ACTIVATE/STATUS/RECOVER before enrollment: both mutations must refuse without policy changes. From a separate ordinary app UID without DUMP, try all three explicit broadcasts: delivery must be rejected. Merely testing from `adb shell` is not a negative caller-authentication test.
3. On an account-free test environment, deliberately enroll Guard. Then verify `dpm list-owners` and still-dormant status. Test recovery and test-only owner removal **before allowing any task to lock**. Re-enroll the same build for the next phase. No tests here imply that personal accounts satisfy enrollment compatibility.

   ```sh
   # EXAMPLE FOR A SEPARATE TEST DEVICE ONLY; values must be deliberately supplied.
   : "${TEST_SERIAL:?Choose the test device}"
   : "${GUARD_APK:?Choose the verified Guard APK}"
   adb -s "$TEST_SERIAL" install -t "$GUARD_APK"
   adb -s "$TEST_SERIAL" shell dpm set-device-owner --user 0 \
     dev.stanislav.focusdeck.guard/.GuardAdminReceiver
   adb -s "$TEST_SERIAL" shell dpm list-owners
   ```

4. With a device credential configured and no task pinned/locked, open Guard and activate after both confirmations, or explicitly send ACTIVATE from authorized ADB. Check ACTIVE, callback diagnostics, allowlist and feature mask 112. This does not itself lock any task. A live DPC policy conflict must block acceptance even if method calls returned normally. Check that arbitrary extras have no effect on the fixed policy and that repeated activation cannot overwrite the recovery baseline.
5. Enter the QA launcher's mandatory ritual. Verify actual LOCKED (not PINNED), then gesture/three-button Recents, notification shade and Quick Settings. Check Home/Back, assistant, links and same-task activity launches. Verify keyguard, power menu, incoming calls and emergency entry/alerts without placing a real emergency call. The conservative feature mask is a candidate, not evidence of OEM emergency-path correctness.
6. While the ritual is still incomplete, send RECOVER from the independent computer. Verify packages/features return to baseline, task mode leaves LOCKED, the unfinished ritual is not marked complete, and the launcher cannot immediately reacquire managed Lock Task. Repeat with the launcher unavailable, Guard process cold, and a broken vault. If another authority still allowlists the launcher, report that conflict rather than forcefully clearing it.
7. Fault tests: interrupted activation, Guard process death after PREPARED, storage failure, changed policy, reboot/first unlock, and recovery after reboot. A cold process recovery test is not the same as force-stop semantics; test the explicit include-stopped-packages path separately. Do not intentionally corrupt the personal device's journal. Use a spare/test user for corruption and process-kill tests.
8. Only after that proof should main choose a personal-user enrollment attempt. The current Pixel has accounts and no device owner. AOSP permits post-setup ADB enrollment only under its account/user compatibility checks; `testOnly` does not bypass incompatible authenticators. Do not remove accounts, toggle provisioning flags, replace management roles or reset/root the Pixel. Enrollment is not a read-only eligibility test. If rejected, stop and report the actual non-sensitive error.

### Optional disposable full-user QA on this Pixel

This can test non-destructive **profile-owner** Lock Task in a separate full Android user while keeping the primary user's accounts intact. It does not prove primary-user enrollment eligibility or device-owner behavior. It still changes the phone, requires switching users and setting up a disposable credential, and may be blocked by device policy or platform limits. Main must deliberately approve this test-user lifecycle; this agent ran none of it.

If main chooses it, create a normal full secondary user (not a managed work profile and not a hidden “for-testing” enrollment bypass), install Guard and the existing QA launcher for that user only, and use the returned nonzero ID throughout:

```sh
# MUTATING example; main decides whether this QA strategy is appropriate.
adb -s 62051JEA324712 shell pm create-user --user-type android.os.usertype.full.SECONDARY 'FocusDeck Guard QA'
# Set QA_USER to the ID actually returned; never guess or use 0 here.
: "${QA_USER:?Use the newly created disposable user ID}"
test "$QA_USER" -gt 0
: "${GUARD_APK:?Use the verified Guard APK}"
adb -s 62051JEA324712 install --user "$QA_USER" -t "$GUARD_APK"
# Install QA launcher for this same user and complete its setup separately.
adb -s 62051JEA324712 shell dpm set-profile-owner --user "$QA_USER" \
  dev.stanislav.focusdeck.guard/.GuardAdminReceiver
```

Main must start/switch to that user, configure its credential and confirm it is a full user before local activation. Keep it account-free. Scope all recovery broadcasts and owner removal to `QA_USER`. Cleanup: RECOVER, verify task exit, remove Guard's test-only profile owner, switch back to user 0, verify primary Home/restrictions, then remove **only that disposable user** if authorized. Never delete an existing personal user. An extra non-test user can block subsequent device-owner provisioning; account for it before the personal-user decision. Do not treat creating a new user as extending control into the original user.

## Main app integration still required

The parent agent owns all main-app integration. Use the existing `isLockTaskPermitted(packageName)`/`startLockTask()` route only while the validated ritual is required, the activity is resumed, and keyguard/device lock is absent. Observe state after each request and show PINNED honestly if an allowlist revocation races entry. Keep completion, emergency pause and vault repair separate; failed storage is not ritual completion. Use task/session ownership checks when stopping a lock the launcher started.

Do not expose an unprotected broadcast from FocusDeck to change Guard policy. Normal ritual completion should stop the activity's task; Guard's allowlist can remain configured for the next ritual. ADB/local Guard RECOVER is the administrative disarm path and requires explicit reactivation afterwards.

This module now implements the bounded scheduling path in `SCHEDULING.md`, without a service. Supported device-owner/affiliated-owner BAL authority is necessary to bring an unlocked launcher's task forward from another app; the former unaffiliated user-10 profile owner did not qualify. The daily alarm and signed `scheduleWake(epochMillis, reason)` API do not guarantee exact timing, recovery of missed alarms or retry after keyguard unlock. Main still owns the actual ritual/grace decisions and Lock Task entry. Vault repair may need a controlled maintenance exit because BLOCK_ACTIVITY_START_IN_TASK can block DocumentsUI/provider activities; avoid broadly allowlisting Settings or browsers to compensate.

## Source basis

- [Official Lock Task guide](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode): DPC allowlisting, real versus pinned tasks, feature configuration and removing a package to exit.
- [DevicePolicyManager reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager): `set/getLockTaskPackages`, `set/getLockTaskFeatures`, Android 14+ resolved-policy semantics and policy callbacks.
- [Android 16 DevicePolicyManagerService](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java): `setLockTaskPackages` removes this admin's LOCK_TASK policy on an empty list; `getLockTaskFeatures` defaults to GLOBAL_ACTIONS without a policy; owner preconditions and account compatibility.
- [Android 16 permission declarations](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/res/AndroidManifest.xml) and [Shell manifest](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/packages/Shell/AndroidManifest.xml): DUMP protection and shell permission.
- [Receiver permission contract](https://developer.android.com/guide/topics/manifest/receiver-element): sender must hold the receiver's declared permission.
- [Android 16 DPM shell implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerServiceShellCommand.java): enrollment mutations and test-only removal command.

These are supported APIs plus inspected AOSP Android 16 semantics. The installed Pixel implementation still needs the device-level verification above. See `VERIFICATION.md` for local build/test results and what remains untested.

# Mac study component — explicit partial capability

This folder contains a compilable NetworkExtension data provider and an optional
user-session app guard. **There is no protected Mac blocking engine installed or
ready to activate.** The app guard is optional friction: its own process can be
quit, applications can decline graceful termination, and an application can run
briefly between 250 ms polls. It never force-kills applications with unsaved work.
It is not a substitute for the requested protected blocker.

## Reproducible safe checks

```
./macos-study/build.sh
./macos-study/tests/smoke.sh
./macos-study/build/StudyAppGuard --check-strict
```

The last command intentionally returns status **3** and JSON with `protected:false`.
A caller must refuse strict activation on this result. Build uses Swift 6 language
mode. Smoke tests use an empty fictional policy in dry-run mode for 60 ms; they do
not close applications, install software, change network settings or activate a
filter. Tests cover exact/subdomain matching, infrastructure exclusions, invalid
policy rejection, bounded timer events and strict readiness refusal.

For review, `StudyAppGuard config/study.json --minutes 25` runs a dry-run countdown
and prints v1 JSONL to stdout. Use an absolute config path when outside this folder.
`--work` chooses the work category. `--enforce` explicitly requests graceful quit
of listed applications, including already-running applications. **It has not been
run against personal applications in this task.** This legacy CLI installs nothing and has no autostart. The separate root
candidate and explicit installer are described below. The 25-minute maximum uses a
monotonic clock, so moving the wall clock cannot extend the guard. Closing the
process stops it. Interrupted processes do not emit a fabricated completed event.
Session UUID is generated locally; cross-device session handoff is not implemented.

Events follow `../shared/event-v1.schema.json`: timer evidence, partial coverage,
unknown productivity metrics represented by null. `health` records a termination
request and whether macOS accepted it, never proof that the app stayed closed.
Dry-run events explicitly carry `mode:dry-run` and `protected:false`; an importer
must not interpret them as measured productive time.

## Website provider and remaining blockers

`StudyFilter` drops newly observed flows whose supplied hostname or app signing
identifier matches policy. It now installs explicit IPv4 127/8 and IPv6 ::1
loopback matching rules; Apple's SDK states that the default action excludes
loopback. This fixes rule selection, **not missing upstream hostnames behind a
local proxy**. Hostname-less flows intentionally fail open to preserve essential
connectivity. A SOCKS/HTTP proxy may make the upstream flow belong to V2Box, which
is protected. No TLS interception or DNS guessing is performed. Cached/existing
connections and each browser/proxy route require live testing after a signed
installation; compiling the provider proves none of those paths blocked.

The audited machine has no signing identities and no installed FocusDeck filter.
An unsigned app container, entitlement/Info.plist templates and host
activation/configuration/recovery source are now prepared and compile. Actual
signing/provisioning with the appropriate NetworkExtension entitlement and user
approval are still missing. Root access cannot grant these entitlements. Even
with those prerequisites, a hostname coverage strategy compatible with V2Box,
OpenVPN and Codex plus protected app enforcement and bounded recovery remain
required. There is no claim that obtaining a certificate alone finishes the engine.

Policy rejects attempts to list Apple, Codex, known V2Box/OpenVPN/Tunnelblick
identity families as blocked apps. Exact observed V2Box `hossin.asaadi.V2Box` is
covered. Identity checks do not prove every helper identity or altered app binary;
VPN coexistence still requires isolated/live acceptance tests. Protected network
domains remain GitLab, OpenAI and ChatGPT. No VPN profiles, SIP settings, trusted
certificates, hosts file, PF rules or personal restrictions were changed.


## Prepared host orchestration (not executed on the system)

`Sources/HostController.swift` separates Apple preferences from an injected fake.
`Sources/HostMain.swift` provides:

- `StudyHost --plan POLICY.json MINUTES`: validate and print a plan, without loading
  or saving system filter preferences. Allowed duration: over zero through 25 minutes.
- Bundled signed host `--activate-lab POLICY.json MINUTES`: request system extension
  activation, wait for completion, then save socket-filter preferences with frozen
  policy, session UUID/category and absolute expiry. It reports approval/reboot as
  pending/error, rather than claiming protection. This is explicitly a partial lab
  path; `--check-strict` still refuses readiness.
- Bundled signed host `--recover`: reload and remove only a configuration explicitly
  identifying FocusDeck's extension. Foreign or unidentified configurations are
  refused. Removal disables the owned filter; it does not uninstall the extension.

The host removes its own session preferences when the bounded monotonic sleep completes.
A stale host cannot remove a newer session: automatic cleanup also checks the
frozen session UUID; explicit emergency --recover intentionally removes any owned session.
The provider independently checks both the frozen UTC deadline and a monotonic
maximum before every new-flow decision, so killing the host does not indefinitely
continue blocking. A crash/reboot can leave enabled preferences behind: the
provider refuses expired startup policy and recovery removes preferences. The
OS's behavior when an expired provider refuses startup, sleep/reboot, force-quit
and changing the clock still need live tests; they are not declared safe from unit
tests alone. The helper is not installed as a privileged protected service and
cannot enforce app launch restrictions. The NE host has no coordinated pause/resume flow yet; the separate UI timer
has a tested bounded pause.

`./macos-study/package-unsigned.sh TEAM_ID` builds the review .app layout with an
embedded `dev.stanislav.focusdeck.study.filter.systemextension`, a provider class
mapping and Team-prefixed Mach service. The script accepts a real ten-character
Team ID but does not sign anything. Packaging tests used fictional `AAAAAAAAAA`;
that artifact is not deployable. Generated build output is ignored by Git.

For a future deployment, obtain Apple's authorized identifiers, matching team
provisioning/entitlements and a valid signing identity; replace the fictional team,
embed the required profiles, sign the inner extension using Filter.entitlements,
then the outer app using Host.entitlements, enable hardened runtime and complete
notarization. Verify both signatures/entitlements and install the app in
/Applications through the supported deployment flow before a short explicit lab
trial. The NE package has no Xcode signing/provisioning project or automated installer;
these are still delivery prerequisites, and no successful signing is asserted.
Do not disable SIP or system-extension security checks to work around them.

Recovery during a future trial: invoke the same signed host's `--recover`; if it
cannot run, use macOS Network Filters settings to disable FocusDeck's filter.
Deleting the host app uses Apple's system extension removal flow and may require
approval/reboot. Before deleting, remove the owned configuration. None of these
system-mutating steps were run in this task.

Apple references used for the source/package design:
[System Extensions](https://developer.apple.com/documentation/systemextensions/),
[startSystemExtensionMode](https://developer.apple.com/documentation/networkextension/neprovider/startsystemextensionmode%28%29),
[content filter providers](https://developer.apple.com/documentation/NetworkExtension/content-filter-providers).
The current macOS SDK headers also document filter preference save/remove behavior
and the explicit loopback rules required by NEFilterSettings.

## Acceptance evidence (docs/ACCEPTANCE.md)

| Criterion | Written/tested evidence | Device / installed / usefulness |
| --- | --- | --- |
| B, reproducible policy/provider/host | Swift 6 build; 17 policy cases plus validation; fake host expiry/save/recovery/foreign-owner tests | Compile on this Mac only; no live blocking or installation |
| B, browser and app coverage | Explicit missing/partial capability; strict exit 3 | Chrome/Safari/Opera GX/Camoufox/Dolphin, private modes, Telegram/Outlook/Teams/new launches remain untested |
| B, network compatibility | Codex/V2Box/OpenVPN identity exclusions; GitLab/OpenAI domains; no network changes | Live V2Box/OpenVPN/Codex/GitLab and study-resource coexistence untested; QUIC/IP/proxy/cache/existing-flow gaps open |
| B, restart/recovery | Host owns bounded expiry; provider UTC + monotonic checks; fake recovery tests | Sleep, time change, interface restart, reboot, emergency recovery not tested live |
| A, session integration | Synthetic started/completed JSONL validated by shared/validate_events.py | No task selection/handoff or real combined protected session |
| E, pauses and return | UI timer300s pause/explicit return tested; optional worker resumes checking after deadline | Root/NE coordinated pause and protected automatic return remain open |
| H, package/reproduction | Unsigned bundle layout and four plists linted; safe commands documented | Signing/provisioning, supported installation, live rollback and actual useful pilot remain open |

## Double-clickable local Mac UI

`./macos-study/build-app.sh` builds `macos-study/build/FocusDeck.app` with an ad-hoc
signature. This is a real SwiftUI application with a task/work-study choice,
25-minute live timer, stop/completion, one 300-second pause, explicit return,
private persisted context and journal. These are first-scenario parameters,
not experimentally established universal durations. System protection remains
explicitly unavailable. The separate signed NE lab package remains a different
artifact; the local UI grants itself no NE entitlement.

The optional task picker opens only one explicitly selected daily note through
NSOpenPanel, uses the shared read-only Obsidian adapter, lists unchecked tasks,
and carries their existing stable ID. It does not discover or edit a vault.
Manual title edits clear stale task bindings. A task without an ID is not assigned
a fabricated cross-device identity. Task text/next action stay in the private
context file; events carry only the existing taskRef.

Local UI data: `~/Library/Application Support/FocusDeckStudy/session.json` and
`events.jsonl` (directory0700/files0600). Corrupt context blocks further writes
until the user explicitly archives the original file; legacy context without the
new monotonic accumulator is migrated with zero unknown elapsed work. Relaunch
preserves task/remaining time, marks the gap unknown and asks for explicit return.
The live timer exports monotonic measured durations as `focusSeconds` with
`evidence:timer`; they estimate timed activity, never attention. Known pre-suspend
segments are closed separately, and sleep/clock-change gaps are unknown.

The app automatically reads the existing private harness receiver configuration,
sends event batches only to `127.0.0.1:48731`, retries stable IDs and persists its
accepted cursor. It neither writes receiver inbox files nor logs the bearer token.
Redirects are refused and system proxies disabled for these loopback requests.
It shows connection availability and the latest harness report's work/study timer
estimates, with missing/stale data explicit. No measured hour is fabricated from
missing data.

For isolated UI QA use `FOCUSDECK_DATA_DIR=/tmp/your-unique-fixture`. This also
**disables receiver configuration/token reads and transmission**. Only explicit
`FOCUSDECK_RECEIVER_CONFIG=/absolute/fixture-config.json` opts that isolated process
into an integration receiver. Isolated mode does not read the private latest
report. No personal vault is automatically read. The `tests/smoke.sh` tests this
isolation rule and validates actual UI-core JSONL using the shared validator.

## Root app guard candidate: independent of NetworkExtension signing

Root launchd execution is a supported separate mechanism; it does not require an
NE entitlement. `FocusDeckRootGuard` uses libproc and Security signing identifiers,
filters only the configured UID, polls every250ms, then rechecks process UID,
signing identity and birth time immediately before signalling. It sends TERM,
then KILL after2s if the same blocked process remains. **Unsaved work may be lost**
when this candidate is explicitly enabled. Codex, V2Box, OpenVPN/Tunnelblick and
Apple identity families are excluded. Root-owned private configuration limits a
session to25minutes; absent/invalid/expired policy does not globally deny apps.

`RootGuardTests.swift` tests deadlines/PID reuse and terminates only an ad-hoc
signed fixture process that the test itself created; it runs without root.
There has been **no root daemon installation, root process termination or personal
app closure**. Root privilege behavior, launchd restart, reboot and compatibility
remain untested. SIGTERM-resistant/SIGKILL escalation is also tested only on a separately created
test-owned fixture; live acceptance remains open. Signing-ID deny lists do not cover every helper,
renamed/re-signed binary or alternate application; no absolute tamper boundary is
claimed against the owner/root.

`install-root-guard.sh --install-idle` is an explicit administrator installation
candidate (not run). It installs a root-owned executable/admin tool and KeepAlive
LaunchDaemon. No session policy is created by installation. It refuses upgrades
in place. The separate root admin tool publishes one frozen policy:

```
FocusDeckGuardAdmin --plan /absolute/policy.json TARGET_UID MINUTES SESSION_UUID
FocusDeckGuardAdmin --publish /absolute/policy.json TARGET_UID MINUTES SESSION_UUID --acknowledge-force-quit
FocusDeckGuardAdmin --recover
```

The root-only emergency recovery removes policy immediately; the daemon observes
that within a poll and requests no further termination. `install-root-guard.sh
--remove` first recovers, unloads the daemon and removes only its own executables
and plist. This administrator workflow is not yet wired to the low-friction UI:
authenticated IPC, pause/session coordination and installed root acceptance remain
open. A prepared installer/KeepAlive plist is not an installed working guard.

Apple references: [Service Management](https://developer.apple.com/documentation/servicemanagement/)
and [SMAppService](https://developer.apple.com/documentation/servicemanagement/smappservice).
The legacy root installer candidate is explicit; modern bundled service
registration requires administrator approval too. No security checks are disabled.

## Optional browser automation candidate

The UI contains a separate **off-by-default** experimental tab-control switch.
Only a deliberately started session with that switch enabled sends AppleEvents
to already-running Safari, Chrome and Opera GX, subject to macOS Automation
approval. It checks HTTP(S) URL host boundaries in memory, then replaces a matching
tab with about:blank only if the URL still matches the observed URL. URLs are not
persisted/exported. Maximum25minutes, two-second polling. It stops during the
bounded pause and returns to checking after that pause ends, while actual work
still requires explicit Continue. Root enforcement and this user-session worker
are not yet coordinated.

This is partial impulse friction: a user can quit/freeze the worker, tabs can
load briefly before a poll, Automation can be denied, and browser/private-window
scripting dictionaries may differ. Other browsers, embedded Codex/webviews, direct
IP, proxy routes and media already playing are not proven covered. App fallback
for unsupported browsers requires the separately installed root guard. Pure
URL/escaping/default-off tests send **no AppleEvents**. Real tab closure and TCC
approval have not been tested in this task; no personal tabs were read or changed.
Changing an ad-hoc build may require Automation approval again.


Pause defaults now match `shared/session-policy-v1.json`: one300-second pause per
25-minute observed-work session. The work timer excludes that pause. Current root
and browser candidates still have a **25-minute wall-clock protection bound**, so
a session including pause/unknown-return delay may outlast those candidates; the
UI does not claim continuous protection during the remainder. This difference
must be resolved through authenticated lifecycle coordination before protected
session readiness. The upfront root target review/force-quit acknowledgement is
mandatory in the candidate admin tool; no root mode is enabled by clicking Start.


The independent local collection disclosure explains that closing/removing the
.app does not stop separately installed services. User-clicked Stop/Start actions
invoke only the installed private control-runtime scripts; no startup action
silently enables them. These buttons are disabled in isolated QA. They control
review/receiver/passive context, preserve history, and do not change root policies
or the separately user-started session timer. The current-day report is refreshed
by the independent harness, rather than assuming yesterday's report is current.

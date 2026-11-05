#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
outdir=$(mktemp -d /tmp/focusdeck-user-recurrence-qa.XXXXXXXX)
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift)
arch="$(uname -m)-apple-macos15.0"
xcrun swiftc -target "$arch" -swift-version 6 -D USER_GUARD_TEST -D USER_RECURRENCE_TEST -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift -framework Security -o "$outdir/Agent"
cp macos-study/build/v2-fixture/GuardFixture "$outdir/GuardFixture"
codesign --verify --strict "$outdir/GuardFixture"
expected=7e8337f9d80274e8c26a17e31b2f70588abded2cce87b2bea49b77ada3c2a04b
actual=$(shasum -a 256 "$outdir/GuardFixture" | awk '{print $1}')
[[ "$actual" == "$expected" ]] || { echo 'Synthetic fixture hash mismatch' >&2; exit 1; }
xcrun swiftc -target "$arch" -swift-version 6 -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/WindowGuardV2Transport.swift macos-study/advanced-lab/tests/UserRecurrenceAgentTests.swift -framework Security -o "$outdir/AgentTests"
"$outdir/AgentTests" "$outdir/Agent" "$outdir/GuardFixture" "$outdir/agent-data" "$outdir/agent.sock" "$outdir/agent-clock"
xcrun swiftc -target "$arch" -swift-version 6 -D SESSION_MODEL_TEST -D USER_GUARD_MAIN macos-study/Sources/SessionState.swift macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/Sources/ProtectionTarget.swift macos-study/Sources/CalendarServiceControl.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/advanced-lab/Sources/WindowGuardV2Transport.swift macos-study/Sources/ExternalProtection.swift macos-study/Sources/DailyReport.swift macos-study/Sources/LocalReceiver.swift macos-study/Sources/LocalCollectionControl.swift macos-study/Sources/RecoveryControl.swift macos-study/Sources/FocusShortcutsBridge.swift shared/ObsidianTaskCatalog.swift macos-study/Sources/FocusDeckApp.swift macos-study/advanced-lab/tests/UserRecurrenceModelTests.swift -framework SwiftUI -framework AppKit -framework Security -framework ServiceManagement -o "$outdir/ModelTests"
FOCUSDECK_DATA_DIR="$outdir/ui" "$outdir/ModelTests" "$outdir/Agent" "$outdir/model-data" "$outdir/model.sock" "$outdir/model-clock"
printf 'Synthetic test data: %s\n' "$outdir"

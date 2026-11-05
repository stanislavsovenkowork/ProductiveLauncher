#!/bin/sh
set -eu
cd "$(dirname "$0")"
out=build/v2-production-candidate
mkdir -p "$out"
common='Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift'
xcrun swiftc -swift-version 6 -D PRODUCTION_GUARD_CANDIDATE $common advanced-lab/Sources/CandidateDiskState.swift advanced-lab/Sources/ProcessSignatureRequirement.swift advanced-lab/Sources/WindowCandidateRecoveryCore.swift advanced-lab/Sources/WindowCandidateEnrollmentGate.swift advanced-lab/Sources/WindowGuardV2ProductionDaemon.swift -framework Security -o "$out/FocusDeckV2ProductionCandidate"
xcrun swiftc -swift-version 6 $common advanced-lab/Sources/CandidateDiskState.swift advanced-lab/Sources/WindowCandidateRecoveryCore.swift advanced-lab/Sources/WindowCandidateRecover.swift -framework Security -o "$out/FocusDeckV2CandidateRecover"
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/ProcessSignatureRequirement.swift advanced-lab/Sources/WindowCandidateRecoveryCore.swift advanced-lab/Sources/WindowCandidateEnrollmentGate.swift advanced-lab/tests/WindowGuardProductionCandidateTests.swift -framework Security -o "$out/core-tests"
"$out/core-tests"
printf '%s\n' 'Source candidate built; no installation, daemon launch, app termination or network change performed.'
./test-protection-targets.sh
xcrun swiftc -swift-version 6 -D SESSION_MODEL_TEST Sources/SessionState.swift $common Sources/ProtectionTarget.swift Sources/ExternalProtection.swift Sources/DailyReport.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift Sources/RecoveryControl.swift Sources/FocusShortcutsBridge.swift ../shared/ObsidianTaskCatalog.swift Sources/FocusDeckApp.swift advanced-lab/tests/WindowCandidateModelTests.swift -framework SwiftUI -framework AppKit -framework Security -o "$out/model-tests"
codesign --force --sign - --identifier dev.stanislav.focusdeck.candidate-test "$out/model-tests"
test_dir=$(mktemp -d /tmp/focusdeck-candidate.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
FOCUSDECK_DATA_DIR="$test_dir" "$out/model-tests"

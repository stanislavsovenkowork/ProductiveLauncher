#!/bin/sh
set -eu
cd "$(dirname "$0")"
test_dir=$(mktemp -d /tmp/focusdeck-authority-test.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
common='Sources/SessionState.swift Sources/WindowPolicy.swift Sources/WindowGuardV2.swift Sources/ProtectionTarget.swift Sources/ExternalProtection.swift Sources/DailyReport.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift Sources/RecoveryControl.swift Sources/FocusShortcutsBridge.swift Sources/FocusDeckApp.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift ../shared/ObsidianTaskCatalog.swift'
for test in CandidateAuthorityTests CandidateUnknownTests; do
  xcrun swiftc -swift-version 6 -D SESSION_MODEL_TEST $common "advanced-lab/tests/$test.swift" -framework SwiftUI -framework AppKit -framework Security -o "$test_dir/$test"
  mkdir "$test_dir/$test-data"
  FOCUSDECK_DATA_DIR="$test_dir/$test-data" "$test_dir/$test"
done

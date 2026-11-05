#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build-app.sh
out=build/v2-fixture
mkdir -p "$out"
common='Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift'
xcrun swiftc -swift-version 6 $common advanced-lab/Sources/WindowFixtureBounds.swift advanced-lab/Sources/WindowGuardV2Daemon.swift -framework Security -o "$out/FocusDeckV2FixtureGuard"
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift advanced-lab/Sources/WindowFixtureBounds.swift advanced-lab/Sources/RootFixtureSeed.swift -o "$out/RootFixtureSeed"
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/WindowFixtureBounds.swift advanced-lab/tests/WindowFixtureBoundsTests.swift -o "$out/fixture-bounds-tests"
"$out/fixture-bounds-tests"
python3 -m unittest discover -s advanced-lab/tests -p 'test_fixture_socket_identity.py' -v
xcrun swiftc -swift-version 6 $common advanced-lab/Sources/WindowGuardUIFixture.swift -framework Security -o "$out/WindowGuardUIFixture"
xcrun swiftc -parse-as-library -swift-version 6 advanced-lab/tests/GuardFixture.swift -o "$out/GuardFixture"
codesign --force --sign - --identifier dev.stanislav.focusdeck.guard-fixture "$out/GuardFixture"
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/tests/WindowGuardV2Tests.swift -o "$out/core-tests"
xcrun swiftc -swift-version 6 $common advanced-lab/tests/WindowGuardV2SocketTests.swift -framework Security -o "$out/socket-tests"
xcrun swiftc -swift-version 6 -D SESSION_MODEL_TEST Sources/SessionState.swift $common Sources/ProtectionTarget.swift Sources/ExternalProtection.swift Sources/DailyReport.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift Sources/RecoveryControl.swift Sources/FocusShortcutsBridge.swift ../shared/ObsidianTaskCatalog.swift Sources/FocusDeckApp.swift advanced-lab/tests/WindowGuardV2ModelIntegrationTests.swift -framework SwiftUI -framework AppKit -framework Security -o "$out/model-integration-tests"
"$out/core-tests"
"$out/socket-tests"
test_dir=$(mktemp -d /tmp/focusdeck-v2-model.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
FOCUSDECK_DATA_DIR="$test_dir" "$out/model-integration-tests" "$(pwd)/$out/GuardFixture"
"build/window-policy-tests"
printf '%s\n' 'Synthetic V2 fixture built and locally tested; no root daemon installed or started, no network or hosts changed.'

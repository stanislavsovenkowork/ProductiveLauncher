#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/SessionState.swift Sources/WindowPolicy.swift Sources/WindowGuardV2.swift Sources/ProtectionTarget.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift Sources/ExternalProtection.swift Sources/DailyReport.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift Sources/RecoveryControl.swift Sources/FocusShortcutsBridge.swift ../shared/ObsidianTaskCatalog.swift Sources/FocusDeckApp.swift -framework SwiftUI -framework AppKit -framework Security -o build/FocusDeck
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/SessionState.swift tests/SessionTests.swift -o build/session-tests
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/SessionState.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift tests/ReceiverIsolationTests.swift -o build/receiver-isolation-tests
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -D SESSION_MODEL_TEST Sources/SessionState.swift Sources/WindowPolicy.swift Sources/WindowGuardV2.swift Sources/ProtectionTarget.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift Sources/ExternalProtection.swift Sources/DailyReport.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift Sources/RecoveryControl.swift Sources/FocusShortcutsBridge.swift ../shared/ObsidianTaskCatalog.swift Sources/FocusDeckApp.swift tests/SessionModelTests.swift -framework SwiftUI -framework AppKit -framework Security -o build/session-model-tests
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/FocusShortcutsBridge.swift tests/FocusBridgeTests.swift -o build/focus-bridge-tests
printf '%s\n' 'Core UI compiled. Fixture bridge included; no root, NetworkExtension, DNS or browser engine activated.'

xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/DailyReport.swift tests/DailyReportTests.swift -o build/daily-report-tests

xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/WindowPolicy.swift tests/WindowPolicyTests.swift -o build/window-policy-tests
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 Sources/RecoveryControl.swift tests/RecoveryControlTests.swift -o build/recovery-control-tests

#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
outdir=$(mktemp -d /tmp/focusdeck-user-main.XXXXXXXX)
app="$outdir/FocusDeck.app"
qa=${FOCUSDECK_BUILD_SYNTHETIC_QA:-0}
flags=(-D USER_GUARD_MAIN -D SYSTEM_PROTECTION)
agent_flags=(-D USER_GUARD_PRODUCTION)
extra_agent=(macos-study/advanced-lab/Sources/UserProtectionQACleanup.swift macos-study/advanced-lab/Sources/WindowFixtureBounds.swift)
if [[ "$qa" == 1 ]]; then flags+=(-D USER_GUARD_QA); agent_flags=(-D USER_GUARD_QA); fi
mkdir -p "$app/Contents/MacOS" "$app/Contents/Library/HelperTools" "$app/Contents/Library/LaunchAgents" "$app/Contents/Resources"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.mac</string><key>CFBundleExecutable</key><string>FocusDeck</string><key>CFBundleName</key><string>FocusDeck</string><key>CFBundleIconFile</key><string>AppIcon</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleVersion</key><string>11</string><key>CFBundleShortVersionString</key><string>0.9</string><key>LSMinimumSystemVersion</key><string>15.0</string></dict></plist>
PLIST
if [[ "$qa" == 1 ]]; then
  plutil -replace CFBundleIdentifier -string dev.stanislav.focusdeck.userprotection.qa.ui "$app/Contents/Info.plist"
  plutil -insert FixtureDataNamespace -string "focusdeck-calendar-bundle.$(basename "$outdir")" "$app/Contents/Info.plist"
  service_name=dev.stanislav.focusdeck.userprotection.qa
else
  service_name=dev.stanislav.focusdeck.userprotection
fi
cat > "$app/Contents/Library/LaunchAgents/$service_name.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>SERVICE_NAME_PLACEHOLDER</string><key>BundleProgram</key><string>Contents/Library/HelperTools/UserProtectionAgent</string><key>RunAtLoad</key><true/></dict></plist>
PLIST
plutil -replace Label -string "$service_name" "$app/Contents/Library/LaunchAgents/$service_name.plist"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 "${agent_flags[@]}" -parse-as-library macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift "${extra_agent[@]}" -framework Security -o "$app/Contents/Library/HelperTools/UserProtectionAgent"
codesign --force --sign - --identifier dev.stanislav.focusdeck.userprotection.agent "$app/Contents/Library/HelperTools/UserProtectionAgent"
if [[ "$qa" != 1 ]]; then
  macos-study/site-protection/build-into-app.sh "$app"
  macos-study/user-agent-manager/build-into-app.sh "$app"
fi
macos-study/system-protection/build-service.sh "$app/Contents/Library/HelperTools/FocusDeckProtectionService"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 "${flags[@]}" macos-study/Sources/SessionState.swift macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/Sources/ProtectionTarget.swift macos-study/Sources/CalendarServiceControl.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/advanced-lab/Sources/WindowGuardV2Transport.swift macos-study/Sources/ExternalProtection.swift macos-study/Sources/DailyReport.swift macos-study/Sources/LocalReceiver.swift macos-study/Sources/LocalCollectionControl.swift macos-study/Sources/RecoveryControl.swift macos-study/Sources/FocusShortcutsBridge.swift shared/ObsidianTaskCatalog.swift macos-study/Sources/SystemProtectionProtocol.swift macos-study/Sources/SystemProtectionView.swift macos-study/Sources/DeckWorkspaceView.swift macos-study/Sources/CodexAppearance.swift macos-study/Sources/FocusDeckApp.swift -framework SwiftUI -framework AppKit -framework Security -framework ServiceManagement -o "$app/Contents/MacOS/FocusDeck"
cp -R macos-study/Assets/CodexIcons "$app/Contents/Resources/CodexIcons"
cp -R macos-study/Assets/CodexAppearance "$app/Contents/Resources/CodexAppearance"
cp macos-study/Assets/AppIcon/AppIcon.icns "$app/Contents/Resources/AppIcon.icns"
cp shared/session-policy-v1.json "$app/Contents/Resources/session-policy-v1.json"
if [[ "$qa" == 1 ]]; then cp macos-study/build/v2-fixture/GuardFixture "$app/Contents/MacOS/GuardFixture"; fi
recovery_app="$app/Contents/Helpers/FocusDeckRecovery.app"
mkdir -p "$recovery_app/Contents/MacOS"
cat > "$recovery_app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.recoveryhost</string><key>CFBundleExecutable</key><string>RecoveryNotificationHost</string><key>CFBundleName</key><string>FocusDeck Recovery</string><key>CFBundlePackageType</key><string>APPL</string><key>LSUIElement</key><true/></dict></plist>
PLIST
xcrun swiftc -swift-version 6 harness/native/RecoveryNotificationHost.swift -framework AppKit -framework UserNotifications -o "$recovery_app/Contents/MacOS/RecoveryNotificationHost"
codesign --force --sign - "$recovery_app"
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
plutil -lint "$app/Contents/Info.plist" "$app/Contents/Library/LaunchAgents/$service_name.plist"
printf 'Candidate main app: %s\n' "$app"

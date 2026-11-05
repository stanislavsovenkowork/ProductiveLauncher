#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
outdir=$(mktemp -d /tmp/focusdeck-calendar-bundle.XXXXXXXX)
app="$outdir/FocusDeckUserAgentFixture.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Library/HelperTools" "$app/Contents/Library/LaunchAgents" "$app/Contents/Resources"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.usercalendarfixture.ui</string><key>CFBundleExecutable</key><string>FocusDeck</string><key>CFBundleName</key><string>FocusDeck User Agent Fixture</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>1.0</string><key>LSMinimumSystemVersion</key><string>15.0</string></dict></plist>
PLIST
plutil -insert FixtureDataNamespace -string "$(basename "$outdir")" "$app/Contents/Info.plist"
cat > "$app/Contents/Library/LaunchAgents/dev.stanislav.focusdeck.usercalendarfixture.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>dev.stanislav.focusdeck.usercalendarfixture</string><key>BundleProgram</key><string>Contents/Library/HelperTools/UserCalendarFixtureAgent</string><key>RunAtLoad</key><true/></dict></plist>
PLIST
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -parse-as-library macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/advanced-lab/Sources/WindowFixtureBounds.swift macos-study/advanced-lab/Sources/SMCalendarFixtureCleanup.swift macos-study/advanced-lab/Sources/UserCalendarFixtureAgent.swift -framework Security -o "$app/Contents/Library/HelperTools/UserCalendarFixtureAgent"
codesign --force --sign - --identifier dev.stanislav.focusdeck.usercalendarfixture.agent "$app/Contents/Library/HelperTools/UserCalendarFixtureAgent"
cp macos-study/build/v2-fixture/GuardFixture "$app/Contents/MacOS/GuardFixture"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -D FOCUSDECK_SM_FIXTURE -D USER_CALENDAR_FIXTURE macos-study/Sources/SessionState.swift macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/Sources/ProtectionTarget.swift macos-study/Sources/CalendarServiceControl.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/advanced-lab/Sources/WindowGuardV2Transport.swift macos-study/Sources/ExternalProtection.swift macos-study/Sources/DailyReport.swift macos-study/Sources/LocalReceiver.swift macos-study/Sources/LocalCollectionControl.swift macos-study/Sources/RecoveryControl.swift macos-study/Sources/FocusShortcutsBridge.swift shared/ObsidianTaskCatalog.swift macos-study/Sources/FocusDeckApp.swift -framework SwiftUI -framework AppKit -framework Security -framework ServiceManagement -o "$app/Contents/MacOS/FocusDeck"
cp shared/session-policy-v1.json "$app/Contents/Resources/session-policy-v1.json"
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
plutil -lint "$app/Contents/Info.plist" "$app/Contents/Library/LaunchAgents/dev.stanislav.focusdeck.usercalendarfixture.plist"
printf 'User agent fixture: %s\n' "$app"

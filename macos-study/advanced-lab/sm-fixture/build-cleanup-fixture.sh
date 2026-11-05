#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
outdir=$(mktemp -d /tmp/focusdeck-calendar-cleanup.XXXXXXXX)
app="$outdir/FocusDeckCalendarCleanup.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Library/HelperTools" "$app/Contents/Library/LaunchDaemons"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.calendarfixture.cleanup.ui</string><key>CFBundleExecutable</key><string>CleanupController</string><key>CFBundleName</key><string>FocusDeck Calendar Fixture Cleanup</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>1.0</string><key>LSMinimumSystemVersion</key><string>15.0</string></dict></plist>
PLIST
cat > "$app/Contents/Library/LaunchDaemons/dev.stanislav.focusdeck.calendarfixture.cleanup.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>dev.stanislav.focusdeck.calendarfixture.cleanup</string><key>BundleProgram</key><string>Contents/Library/HelperTools/CleanupDaemon</string><key>RunAtLoad</key><true/></dict></plist>
PLIST
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -parse-as-library macos-study/Sources/WindowPolicy.swift macos-study/advanced-lab/Sources/SMCalendarFixtureCleanup.swift macos-study/advanced-lab/Sources/SMCalendarCleanupDaemon.swift -o "$app/Contents/Library/HelperTools/CleanupDaemon"
codesign --force --sign - --identifier dev.stanislav.focusdeck.calendarfixture.cleanup.daemon "$app/Contents/Library/HelperTools/CleanupDaemon"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -parse-as-library macos-study/advanced-lab/sm-fixture/CleanupController.swift -framework SwiftUI -framework ServiceManagement -o "$app/Contents/MacOS/CleanupController"
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
plutil -lint "$app/Contents/Info.plist" "$app/Contents/Library/LaunchDaemons/dev.stanislav.focusdeck.calendarfixture.cleanup.plist"
printf 'Cleanup candidate: %s\n' "$app"

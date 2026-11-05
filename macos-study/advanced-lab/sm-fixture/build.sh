#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/focusdeck-smfixture.XXXXXXXX")
output="$build_dir/FocusDeckSMFixtureCandidate.app"
mkdir -p "$output/Contents/MacOS" "$output/Contents/Library/LaunchDaemons" "$output/Contents/Library/HelperTools"
cat > "$output/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.smfixture.controller</string><key>CFBundleExecutable</key><string>FocusDeckSMFixture</string><key>CFBundleName</key><string>FocusDeck SM Fixture</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>1.0</string><key>LSMinimumSystemVersion</key><string>13.0</string></dict></plist>
PLIST
cat > "$output/Contents/Library/LaunchDaemons/dev.stanislav.focusdeck.smfixture.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>Label</key><string>dev.stanislav.focusdeck.smfixture</string><key>BundleProgram</key><string>Contents/Library/HelperTools/FocusDeckSMFixtureDaemon</string><key>RunAtLoad</key><true/></dict></plist>
PLIST
swiftc -parse-as-library Daemon.swift -o "$output/Contents/Library/HelperTools/FocusDeckSMFixtureDaemon"
codesign --force --sign - --identifier dev.stanislav.focusdeck.smfixture.daemon "$output/Contents/Library/HelperTools/FocusDeckSMFixtureDaemon"
cp ../../build/v2-fixture/GuardFixture "$output/Contents/MacOS/GuardFixture"
swiftc -parse-as-library ServiceModel.swift FixtureClient.swift Controller.swift -framework SwiftUI -framework ServiceManagement -o "$output/Contents/MacOS/FocusDeckSMFixture"
codesign --force --sign - "$output"
codesign --verify --deep --strict "$output"
plutil -lint "$output/Contents/Info.plist" "$output/Contents/Library/LaunchDaemons/dev.stanislav.focusdeck.smfixture.plist"

printf "Candidate: %s\n" "$output"

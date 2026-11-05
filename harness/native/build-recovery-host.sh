#!/bin/sh
# Prepare an ad-hoc signed candidate in a fresh temporary directory; never install or request permission.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
out=$(mktemp -d /tmp/focusdeck-recovery-host.XXXXXXXX)
app="$out/FocusDeckRecovery.app"
mkdir -p "$app/Contents/MacOS"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.recoveryhost</string><key>CFBundleExecutable</key><string>RecoveryNotificationHost</string><key>CFBundleName</key><string>FocusDeck Recovery</string><key>CFBundlePackageType</key><string>APPL</string><key>LSUIElement</key><true/></dict></plist>
PLIST
xcrun swiftc -swift-version 6 "$root/harness/native/RecoveryNotificationHost.swift" -framework AppKit -framework UserNotifications -o "$app/Contents/MacOS/RecoveryNotificationHost"
codesign --force --sign - "$app"
codesign --verify --strict "$app"
printf 'Candidate: %s\n' "$app"
printf '%s\n' 'No permission requested, notification sent, or service installed.'

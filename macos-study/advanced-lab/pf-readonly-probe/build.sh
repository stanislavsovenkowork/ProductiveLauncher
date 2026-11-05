#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
out=$(mktemp -d /tmp/focusdeck-pf-readonly.XXXXXXXX)
app="$out/FocusDeck PF Readonly.app"
mkdir -p "$app/Contents/MacOS"
clang -c Probe.c -o "$out/Probe.o"
swiftc -swift-version 6 -parse-as-library -import-objc-header Probe.h ProbeApp.swift "$out/Probe.o" -framework SwiftUI -framework Security -o "$app/Contents/MacOS/Probe"
cat > "$app/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.pf-readonly</string><key>CFBundleName</key><string>FocusDeck PF Readonly</string><key>CFBundleExecutable</key><string>Probe</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleVersion</key><string>1</string><key>CFBundleShortVersionString</key><string>1</string><key>LSMinimumSystemVersion</key><string>15.0</string></dict></plist>
EOF
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
printf '%s\n' "$app"

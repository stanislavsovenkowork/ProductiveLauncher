#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
bundle=build/FocusDeck.app
# Recreate only this generated UI artifact, removing stale experimental resources.
rm -rf "$bundle"
mkdir -p "$bundle/Contents/MacOS" "$bundle/Contents/Resources"
cp build/FocusDeck "$bundle/Contents/MacOS/FocusDeck"
cp ../shared/session-policy-v1.json "$bundle/Contents/Resources/session-policy-v1.json"
cat > "$bundle/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.mac</string>
<key>CFBundleExecutable</key><string>FocusDeck</string>
<key>CFBundleName</key><string>FocusDeck</string>
<key>CFBundleDisplayName</key><string>FocusDeck</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.3</string>
<key>CFBundleVersion</key><string>3</string>
<key>LSMinimumSystemVersion</key><string>15.0</string>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
plutil -lint "$bundle/Contents/Info.plist"
codesign --force --sign - --options runtime "$bundle"
codesign --verify --deep --strict "$bundle"
printf '%s\n' 'Core FocusDeck.app built; external protection not connected. No privileged components embedded.'

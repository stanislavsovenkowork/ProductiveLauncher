#!/bin/sh
# BLOCKED: the historical controller invokes AppleScript and is not permitted for computer-use authorization.
echo 'Blocked: no verified native authorization controller is available.' >&2
exit 1
set -eu
cd "$(dirname "$0")"
# Do not overwrite a previously built review artifact.
app=build/FocusDeckRootFixtureController.app
[ ! -e "$app" ] || { echo 'Controller artifact already exists; choose a fresh output before rebuilding.' >&2; exit 1; }
./root-fixture-authorization.sh --plan install
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources/fixture-package/advanced-lab" "$app/Contents/Resources/fixture-package/build/v2-fixture"
pkg="$app/Contents/Resources/fixture-package"
cp root-fixture-authorization.sh run-v2-root-ui-fixture.sh run-v2-root-fixture-process.sh "$pkg/"
cp advanced-lab/v2-fixture-admin.py advanced-lab/FixtureAuthorization.applescript "$pkg/advanced-lab/"
cp build/v2-fixture/FocusDeckV2FixtureGuard build/v2-fixture/FocusDeckV2FixtureControl build/v2-fixture/GuardFixture build/v2-fixture/RootFixtureSeed "$pkg/build/v2-fixture/"
ditto build/FocusDeck.app "$pkg/build/FocusDeck.app"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>dev.stanislav.focusdeck.fixture-controller</string>
<key>CFBundleExecutable</key><string>RootFixtureController</string>
<key>CFBundleName</key><string>FocusDeck Fixture Control</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>LSMinimumSystemVersion</key><string>15.0</string>
</dict></plist>
PLIST
xcrun swiftc -parse-as-library -swift-version 6 -target "$(uname -m)-apple-macos15.0" advanced-lab/Sources/RootFixtureControllerApp.swift -framework SwiftUI -framework AppKit -o "$app/Contents/MacOS/RootFixtureController"
codesign --force --sign - --options runtime "$app"
codesign --verify --strict "$app"
codesign --verify --strict "$pkg/build/FocusDeck.app"
inner_hash=$(codesign -dv --verbose=4 "$pkg/build/FocusDeck.app" 2>&1 | sed -n 's/^CDHash=//p')
[ "$inner_hash" = 98bfd572b7663875c8bd7aa98e2c1ef78a917d51 ] || { echo 'Embedded UI CDHash differs; do not run.' >&2; exit 1; }
"$pkg/root-fixture-authorization.sh" --plan install
printf '%s\n' 'Fixture-only controller built; no UI opened and no privileged action performed.'

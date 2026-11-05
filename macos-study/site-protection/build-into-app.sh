#!/bin/bash
# Called after signing the agent, before signing the containing app.
set -euo pipefail
app=${1:?Usage: build-into-app.sh /absolute/path/FocusDeck.app}
case "$app" in /*) ;; *) echo 'App path must be absolute.' >&2; exit 2;; esac
root=$(cd "$(dirname "$0")/../.." && pwd)
agent="$app/Contents/Library/HelperTools/UserProtectionAgent"
hash=$(codesign -dv --verbose=4 "$agent" 2>&1 | sed -n 's/^CDHash=//p')
[[ "$hash" =~ ^[a-fA-F0-9]{40}$ ]] || { echo 'Unable to pin signed agent.' >&2; exit 1; }
tmp=$(mktemp -d /tmp/focusdeck-site-build.XXXXXXXX)
trap 'rm -rf "$tmp"' EXIT
printf 'enum SiteHostBuild { static let agentHash = "%s" }\n' "$hash" > "$tmp/Build.swift"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -parse-as-library \
 "$root/macos-study/Sources/WindowPolicy.swift" "$root/macos-study/Sources/WindowGuardV2.swift" \
 "$root/macos-study/advanced-lab/Sources/StudyPolicy.swift" "$root/macos-study/advanced-lab/Sources/RootCommandCore.swift" \
 "$root/macos-study/advanced-lab/Sources/RootGuardCore.swift" "$root/macos-study/advanced-lab/Sources/RootSocket.swift" \
 "$root/macos-study/site-protection/NativeHost.swift" "$tmp/Build.swift" -framework Security -o "$app/Contents/Library/HelperTools/FocusDeckSiteHost"
codesign --force --sign - --identifier dev.stanislav.focusdeck.sites.host "$app/Contents/Library/HelperTools/FocusDeckSiteHost"
cp -R "$root/macos-study/site-protection/extension" "$app/Contents/Resources/FocusDeckChrome"
cp "$root/macos-study/site-protection/manage-host.sh" "$app/Contents/Resources/FocusDeckChrome/manage-host.command"

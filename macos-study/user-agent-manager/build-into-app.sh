#!/bin/bash
set -euo pipefail
app=${1:?Usage: build-into-app.sh /absolute/path/FocusDeck.app}
case "$app" in /*) ;; *) echo 'Absolute app path required.' >&2; exit 2;; esac
root=$(cd "$(dirname "$0")/../.." && pwd)
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -parse-as-library \
 "$root/macos-study/Sources/WindowPolicy.swift" "$root/macos-study/Sources/WindowGuardV2.swift" \
 "$root/macos-study/advanced-lab/Sources/StudyPolicy.swift" "$root/macos-study/advanced-lab/Sources/RootCommandCore.swift" \
 "$root/macos-study/advanced-lab/Sources/RootGuardCore.swift" "$root/macos-study/advanced-lab/Sources/RootSocket.swift" \
 "$root/macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift" "$root/macos-study/advanced-lab/Sources/UserProtectionLedger.swift" \
 "$root/macos-study/user-agent-manager/ScheduleBoundary.swift" "$root/macos-study/user-agent-manager/Manager.swift" -framework Security -o "$app/Contents/Library/HelperTools/FocusDeckUserAgentManager"
codesign --force --sign - --identifier dev.stanislav.focusdeck.userprotection.manager "$app/Contents/Library/HelperTools/FocusDeckUserAgentManager"

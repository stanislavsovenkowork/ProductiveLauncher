#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
out=$(mktemp -d /tmp/focusdeck-strict-ui-qa.XXXXXXXX)
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/Sources/StrictRootWindow.swift)
xcrun swiftc -swift-version 6 -parse-as-library "${common[@]}" macos-study/Sources/StrictRootIPC.swift macos-study/Sources/StrictRootPresentation.swift macos-study/Sources/StrictRootController.swift macos-study/strict-root/tests/StrictRootQAClient.swift -framework Security -framework Combine -o "$out/StrictRootQAClient"
codesign --force --sign - --identifier dev.stanislav.focusdeck.strict.qa.ui "$out/StrictRootQAClient" >/dev/null 2>&1
codesign --verify --strict "$out/StrictRootQAClient"
sha=$(shasum -a 256 "$out/StrictRootQAClient" | awk '{print $1}')
hash=$(codesign -dv --verbose=4 "$out/StrictRootQAClient" 2>&1 | sed -n 's/^CDHash=//p')
printf 'QA client: %s\nSHA256: %s\nCDHash: %s\n' "$out/StrictRootQAClient" "$sha" "$hash"

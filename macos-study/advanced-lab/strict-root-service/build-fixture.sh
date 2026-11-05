#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
out=$(mktemp -d /tmp/focusdeck-strict-daemon-build.XXXXXXXX)
swiftc -swift-version 6 -parse-as-library -D STRICT_ROOT_FIXTURE \
  macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/Sources/StrictRootWindow.swift \
  macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift \
  macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift \
  macos-study/advanced-lab/strict-root-service/StrictRootStore.swift macos-study/advanced-lab/strict-root-service/StrictRootAuthority.swift \
  macos-study/advanced-lab/strict-root-service/StrictFixtureDaemon.swift -framework Security -o "$out/StrictRootQADaemon"
codesign --force --sign - --options runtime --identifier dev.stanislav.focusdeck.strict.qa.daemon "$out/StrictRootQADaemon"
codesign --verify --strict "$out/StrictRootQADaemon"
shasum -a 256 "$out/StrictRootQADaemon"
codesign -dv --verbose=4 "$out/StrictRootQADaemon" 2>&1 | sed -n '/^CDHash=/p'
printf 'QA fixture: %s\n' "$out/StrictRootQADaemon"

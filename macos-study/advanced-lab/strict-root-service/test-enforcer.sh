#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ ${1:-} != --inner ]]; then
  exec python3 harness/qa_scope.py run --log "$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl" -- "$PWD/macos-study/advanced-lab/strict-root-service/test-enforcer.sh" --inner
fi
out=$(mktemp -d /tmp/focusdeck-strict-app-build.XXXXXXXX)
trap 'rm -rf "$out"' EXIT
swiftc -swift-version 6 -parse-as-library macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/strict-root-service/StrictAppEnforcer.swift macos-study/advanced-lab/strict-root-service/EnforcerTests.swift -framework Security -o "$out/tests"
"$out/tests"

#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ ${1:-} != --inner ]]; then
  exec python3 harness/qa_scope.py run --log "$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl" -- "$PWD/macos-study/advanced-lab/strict-root-service/test-authority.sh" --inner
fi
out=$(mktemp -d /tmp/focusdeck-strict-authority-build.XXXXXXXX)
trap 'rm -rf "$out"' EXIT
swiftc -swift-version 6 -parse-as-library -D STRICT_ROOT_QA macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/Sources/StrictRootWindow.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/strict-root-service/StrictRootStore.swift macos-study/advanced-lab/strict-root-service/StrictRootAuthority.swift macos-study/advanced-lab/strict-root-service/AuthorityTests.swift -framework Security -o "$out/tests"
"$out/tests"

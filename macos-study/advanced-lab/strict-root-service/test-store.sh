#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ ${1:-} != --inner ]]; then
  exec python3 harness/qa_scope.py run --log "$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl" -- "$PWD/macos-study/advanced-lab/strict-root-service/test-store.sh" --inner
fi
out=$(mktemp -d /tmp/focusdeck-strict-root-build.XXXXXXXX)
trap 'rm -rf "$out"' EXIT
swiftc -swift-version 6 -parse-as-library -D STRICT_ROOT_QA macos-study/advanced-lab/strict-root-service/StrictRootStore.swift macos-study/advanced-lab/strict-root-service/StoreTests.swift -o "$out/tests"
"$out/tests"

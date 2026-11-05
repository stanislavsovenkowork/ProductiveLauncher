#!/bin/sh
set -eu
cd "$(dirname "$0")"
test_dir=$(mktemp -d /tmp/focusdeck-recurring-gate.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/CandidateDiskState.swift advanced-lab/Sources/RecurringLedgerCandidate.swift advanced-lab/tests/RecurringIntegrationGateTests.swift -o "$test_dir/tests"
"$test_dir/tests"

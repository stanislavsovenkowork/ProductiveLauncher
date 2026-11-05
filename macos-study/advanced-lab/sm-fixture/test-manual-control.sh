#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
qa_dir=$(mktemp -d /tmp/focusdeck-manual-control-qa.XXXXXXXX)
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -D USER_GUARD_MAIN -parse-as-library macos-study/Sources/CalendarServiceControl.swift macos-study/tests/ManualAgentControlTests.swift -framework SwiftUI -framework ServiceManagement -o "$qa_dir/Tests"
"$qa_dir/Tests"

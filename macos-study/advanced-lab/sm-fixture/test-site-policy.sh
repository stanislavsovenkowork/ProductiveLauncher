#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
qa_dir=$(mktemp -d /tmp/focusdeck-site-policy-qa.XXXXXXXX)
arch="$(uname -m)-apple-macos15.0"
xcrun swiftc -target "$arch" -swift-version 6 -parse-as-library macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/tests/SiteDomainPolicyTests.swift -framework Security -o "$qa_dir/Tests"
"$qa_dir/Tests"

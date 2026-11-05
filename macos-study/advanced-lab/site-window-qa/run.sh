#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
qa_dir=$(mktemp -d /tmp/focusdeck-site-qa.XXXXXXXX)
arch="$(uname -m)-apple-macos15.0"
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift)
xcrun swiftc -target "$arch" -swift-version 6 -D USER_GUARD_TEST -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift -framework Security -o "$qa_dir/Agent"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.qa.agent "$qa_dir/Agent" >/dev/null 2>&1
xcrun swiftc -target "$arch" -swift-version 6 -parse-as-library "${common[@]}" macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift -framework Security -o "$qa_dir/Client"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.qa.client "$qa_dir/Client" >/dev/null 2>&1
agent_hash=$(codesign -dv --verbose=4 "$qa_dir/Agent" 2>&1 | sed -n 's/^CDHash=//p')
[ "${#agent_hash}" -eq 40 ]
node macos-study/advanced-lab/site-window-qa/run.mjs "$qa_dir" "$agent_hash"

#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ "${1:-}" != --qa-inner ]]; then
  exec python3 harness/qa_scope.py run --log "$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl" -- "$PWD/macos-study/advanced-lab/sm-fixture/test-site-agent.sh" --qa-inner
fi
qa_dir=$(mktemp -d /tmp/focusdeck-site-agent-qa.XXXXXXXX)
arch="$(uname -m)-apple-macos15.0"
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift)
xcrun swiftc -target "$arch" -swift-version 6 -D USER_GUARD_TEST -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift -framework Security -o "$qa_dir/Agent"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.qa.agent "$qa_dir/Agent" >/dev/null 2>&1
xcrun swiftc -target "$arch" -swift-version 6 -parse-as-library "${common[@]}" macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift -framework Security -o "$qa_dir/Client"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.qa.client "$qa_dir/Client" >/dev/null 2>&1
agent_hash=$(codesign -dv --verbose=4 "$qa_dir/Agent" 2>&1 | sed -n 's/^CDHash=//p')
FOCUSDECK_USER_TEST_DIR="$qa_dir/data" FOCUSDECK_USER_TEST_SOCKET="$qa_dir/agent.sock" "$qa_dir/Agent" &
qa_pid=$!
cleanup() { kill -TERM "$qa_pid" 2>/dev/null || true; wait "$qa_pid" 2>/dev/null || true; }
trap cleanup EXIT
for ((i=0;i<100;i++)); do [[ -S "$qa_dir/agent.sock" ]] && break; sleep .05; done
[[ -S "$qa_dir/agent.sock" ]]
client() { "$qa_dir/Client" "$qa_dir/agent.sock" "$agent_hash" "$1"; }
[[ $(client publish_site) == '{"ok":true}' ]]
[[ $(client status) == '{"phase":"active"}' ]]
python3 - "$qa_dir/data/protection-ledger.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f: x=json.load(f)
assert x['policy']['siteDomains'] == ['qa.example']
assert x['policy']['targetRefs'] == [] and x['signingRequirements'] == []
assert x['policy']['reliefRemainingSeconds'] == 120
PY
[[ $(client relief) == '{"ok":true}' ]]
[[ $(client status) == '{"phase":"released"}' ]]
[[ $(client return) == '{"ok":true}' ]]
[[ $(client status) == '{"phase":"active"}' ]]
python3 - "$qa_dir/data/protection-ledger.json" <<'PY'
import json, sys
with open(sys.argv[1]) as f: x=json.load(f)
assert x['policy']['siteDomains'] == ['qa.example'] and x['policy']['reliefRemainingSeconds'] == 115
PY
[[ $(client request_exit) == '{"ok":true}' ]]
sleep 1.2
[[ $(client exit) == '{"ok":true}' ]]
[[ $(client status) == '{"phase":"released"}' ]]
echo 'PASS: real site-only user-agent status active/relief/return/exit, durable exact domains and budget; no app target'

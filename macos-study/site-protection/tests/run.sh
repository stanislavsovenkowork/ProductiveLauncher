#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ "${1:-}" != --inside-scope ]]; then
  [[ $# == 0 ]] || { echo 'Unexpected arguments.' >&2; exit 2; }
  exec python3 macos-study/site-protection/tests/run-scoped.py
fi
# Refuse direct entry even before building or launching test processes.
python3 - <<'CHECK'
import json, os, uuid
from pathlib import Path
try:
 ident=str(uuid.UUID(os.environ['FOCUSDECK_SITE_QA_SCOPE_ID']))
 log=Path(os.environ['FOCUSDECK_SITE_QA_SCOPE_LOG'])
 assert log.is_absolute()
 rows=[json.loads(line) for line in log.read_text().splitlines()]
 own=[r for r in rows if r.get('scopeId') == ident]
 assert len(own)==1 and own[0].get('event')=='start'
except Exception:
 raise SystemExit('QA scope is absent or closed; no test launched.')
CHECK
node macos-study/site-protection/tests/domain-policy.test.cjs
node macos-study/site-protection/tests/setup-policy.test.cjs
qa_dir=$(mktemp -d /tmp/focusdeck-site-native-qa.XXXXXXXX)
arch="$(uname -m)-apple-macos15.0"
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift)
xcrun swiftc -target "$arch" -swift-version 6 -D USER_GUARD_TEST -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift -framework Security -o "$qa_dir/Agent"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.native.qa.agent "$qa_dir/Agent" >/dev/null 2>&1
hash=$(codesign -dv --verbose=4 "$qa_dir/Agent" 2>&1 | sed -n 's/^CDHash=//p')
printf 'enum SiteHostBuild { static let agentHash = "%s" }\n' "$hash" > "$qa_dir/Build.swift"
xcrun swiftc -target "$arch" -swift-version 6 -D SITE_HOST_TEST -parse-as-library "${common[@]}" macos-study/site-protection/NativeHost.swift "$qa_dir/Build.swift" -framework Security -o "$qa_dir/Host"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.native.qa.host "$qa_dir/Host" >/dev/null 2>&1
python3 - "$qa_dir/Client.swift" <<'PY'
import sys
s=open('macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift').read()
s=s.replace('case "relief": changed = current.relief(now: now)', 'case "rest": changed = current.rest(now: now)\n   case "relief": changed = current.relief(now: now)')
s=s.replace('p.reliefMaxGrantSeconds = 5;', 'p.reliefMaxGrantSeconds = 8;')
s=s.replace('p.localDate = "2026-11-02";', 'p.siteDomains = ["qa.focusdeck.test"]; if ProcessInfo.processInfo.environment["FOCUSDECK_QA_DOMAIN_MODE"] == "subdomains" { p.siteSubdomains = true }; p.localDate = "2026-11-02";')
open(sys.argv[1],'w').write(s)
PY
xcrun swiftc -target "$arch" -swift-version 6 -parse-as-library "${common[@]}" "$qa_dir/Client.swift" -framework Security -o "$qa_dir/Client"
codesign --force --sign - --identifier dev.stanislav.focusdeck.site.native.qa.client "$qa_dir/Client" >/dev/null 2>&1
for mode in legacy subdomains; do
  mkdir "$qa_dir/$mode"
  cp "$qa_dir/Agent" "$qa_dir/Host" "$qa_dir/Client" "$qa_dir/$mode/"
  FOCUSDECK_QA_DOMAIN_MODE="$mode" node macos-study/site-protection/tests/run.mjs "$qa_dir/$mode" "$hash"
done
printf 'Combined QA artifacts: %s\n' "$qa_dir"

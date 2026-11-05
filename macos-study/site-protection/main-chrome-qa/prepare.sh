#!/bin/bash
# Build synthetic components only. Does not touch Chrome, production host or ledger.
set -euo pipefail
cd "$(dirname "$0")/../../.."
out=$(mktemp -d /tmp/focusdeck-mainchrome-qa.XXXXXXXX)
label=$(basename "$out" | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')
hostname="${label}.localhost"
arch="$(uname -m)-apple-macos15.0"
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift)
xcrun swiftc -target "$arch" -swift-version 6 -D USER_GUARD_TEST -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift -framework Security -o "$out/Agent"
codesign --force --sign - --identifier dev.stanislav.focusdeck.mainchrome.qa.agent "$out/Agent" >/dev/null 2>&1
hash=$(codesign -dv --verbose=4 "$out/Agent" 2>&1 | sed -n 's/^CDHash=//p')
[[ "$hash" =~ ^[a-f0-9]{40}$ ]] || exit 1
printf 'enum SiteHostBuild { static let agentHash = "%s" }\n' "$hash" > "$out/Build.swift"
xcrun swiftc -target "$arch" -swift-version 6 -D SITE_HOST_TEST -parse-as-library "${common[@]}" macos-study/site-protection/NativeHost.swift "$out/Build.swift" -framework Security -o "$out/Host"
codesign --force --sign - --identifier dev.stanislav.focusdeck.mainchrome.qa.host "$out/Host" >/dev/null 2>&1
python3 - "$out/Client.swift" "$hostname" <<'PY'
import sys
s=open('macos-study/advanced-lab/site-window-qa/SiteAgentClient.swift').read()
old='p.targetRefs = []; p.siteDomains = ["qa.example"]'
new='p.targetRefs = []; p.siteDomains = ["'+sys.argv[2]+'"]; p.siteSubdomains = true'
assert s.count(old)==1
assert s.count('p.endsAt = now.addingTimeInterval(300)')==1
open(sys.argv[1],'w').write(s.replace(old,new).replace('p.endsAt = now.addingTimeInterval(300)', 'p.endsAt = now.addingTimeInterval(1200)'))
PY
xcrun swiftc -target "$arch" -swift-version 6 -parse-as-library "${common[@]}" "$out/Client.swift" -framework Security -o "$out/Client"
codesign --force --sign - --identifier dev.stanislav.focusdeck.mainchrome.qa.client "$out/Client" >/dev/null 2>&1
python3 - "$out" "$hostname" "$hash" <<'PY'
from pathlib import Path
import json,shlex,sys
out=Path(sys.argv[1]);socket=out/'agent.sock'
(out/'host-wrapper').write_text('#!/bin/sh\nexport FOCUSDECK_SITE_TEST_SOCKET='+shlex.quote(str(socket))+'\nexec '+shlex.quote(str(out/'Host'))+' "$@"\n')
(out/'host-wrapper').chmod(0o700)
(out/'config.json').write_text(json.dumps({'hostname':sys.argv[2], 'agentCDHash':sys.argv[3], 'socket':str(socket)},indent=2)+'\n')
(out/'config.json').chmod(0o600)
PY
printf 'Synthetic build: %s\nSynthetic hostname: %s\n' "$out" "$hostname"

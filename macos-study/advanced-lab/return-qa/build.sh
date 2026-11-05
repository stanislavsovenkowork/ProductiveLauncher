#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
outdir=$(mktemp -d /tmp/focusdeck-return-qa.XXXXXXXX)
export RETURN_QA_DIR="$outdir"
python3 - <<'PY'
from pathlib import Path
import os
out=Path(os.environ['RETURN_QA_DIR'])
source=Path('macos-study/advanced-lab/tests/GuardFixture.swift').read_text()
assert source.count('Thread.sleep(forTimeInterval: 30)')==1
(out/'GuardFixture.swift').write_text(source.replace('Thread.sleep(forTimeInterval: 30)', 'Thread.sleep(forTimeInterval: 180)'))
PY
xcrun swiftc -parse-as-library -swift-version 6 "$outdir/GuardFixture.swift" -o "$outdir/GuardFixture"
codesign --force --sign - --identifier dev.stanislav.focusdeck.guard-fixture "$outdir/GuardFixture"
codesign --verify --strict "$outdir/GuardFixture"
fixture_hash=$(codesign -dvvv "$outdir/GuardFixture" 2>&1 | awk '/^CDHash=/{print substr($0,8)}')
[[ ${#fixture_hash} == 40 ]]
export RETURN_FIXTURE_HASH="$fixture_hash"
python3 - <<'PY'
from pathlib import Path
import os
out=Path(os.environ['RETURN_QA_DIR']); new=os.environ['RETURN_FIXTURE_HASH']; old='3e481e3d08c534a55081e55313fa2768f44d57bc'
app=Path('macos-study/Sources/FocusDeckApp.swift').read_text()
needle='endsAt: now.addingTimeInterval(150), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: targets'
assert app.count(needle)==1 and app.count(old)==1
app=app.replace(needle,'endsAt: now.addingTimeInterval(590), reliefBudgetSeconds: 120, reliefRemainingSeconds: 120, reliefMaxGrantSeconds: 120, targetRefs: targets').replace(old,new)
assert app.count('Только synthetic QA: окно 150 секунд;')==1
app=app.replace('Только synthetic QA: окно 150 секунд;','Только synthetic QA: окно 590 секунд; послабление 120 секунд;')
(out/'FocusDeckApp.swift').write_text(app)
agent=Path('macos-study/advanced-lab/Sources/UserProtectionAgent.swift').read_text(); assert agent.count(old)==1
(out/'UserProtectionAgent.swift').write_text(agent.replace(old,new))
ledger=Path('macos-study/advanced-lab/Sources/UserProtectionLedger.swift').read_text(); assert ledger.count(old)==1
(out/'UserProtectionLedger.swift').write_text(ledger.replace(old,new))
bounds=Path('macos-study/advanced-lab/Sources/WindowFixtureBounds.swift').read_text(); assert bounds.count('<= 180')==2
bounds=bounds.replace('<= 180','<= 600')
bounds=bounds.replace('policy.targetRefs == [target] &&', 'policy.reliefBudgetSeconds <= 120 && policy.reliefMaxGrantSeconds <= 120 && policy.targetRefs == [target] &&')
(out/'WindowFixtureBounds.swift').write_text(bounds)
build=Path('macos-study/advanced-lab/sm-fixture/build-user-main.sh').read_text()
build=build.replace('cd "$(dirname "$0")/../../.."','cd "' + str(Path.cwd()) + '"')
build=build.replace('outdir=$(mktemp -d /tmp/focusdeck-user-main.XXXXXXXX)','outdir="$RETURN_QA_DIR"')
build=build.replace('qa=${FOCUSDECK_BUILD_SYNTHETIC_QA:-0}','qa=1')
for oldpath,newpath in [('macos-study/advanced-lab/Sources/WindowFixtureBounds.swift','$RETURN_QA_DIR/WindowFixtureBounds.swift'),('macos-study/advanced-lab/Sources/UserProtectionAgent.swift','$RETURN_QA_DIR/UserProtectionAgent.swift'),('macos-study/advanced-lab/Sources/UserProtectionLedger.swift','$RETURN_QA_DIR/UserProtectionLedger.swift'),('macos-study/Sources/FocusDeckApp.swift','$RETURN_QA_DIR/FocusDeckApp.swift'),('macos-study/build/v2-fixture/GuardFixture','$RETURN_QA_DIR/GuardFixture')]:
 assert build.count(oldpath)==1,oldpath
 build=build.replace(oldpath,'"'+newpath+'"')
(out/'build-copy.sh').write_text(build)
PY
bash "$outdir/build-copy.sh"
shasum -a 256 "$outdir/FocusDeck.app/Contents/MacOS/FocusDeck" "$outdir/FocusDeck.app/Contents/Library/HelperTools/UserProtectionAgent" "$outdir/FocusDeck.app/Contents/MacOS/GuardFixture"
for bin in "$outdir/FocusDeck.app/Contents/MacOS/FocusDeck" "$outdir/FocusDeck.app/Contents/Library/HelperTools/UserProtectionAgent" "$outdir/FocusDeck.app/Contents/MacOS/GuardFixture"; do codesign -dvvv "$bin" 2>&1 | awk '/^Identifier=|^CDHash=/{print}'; done

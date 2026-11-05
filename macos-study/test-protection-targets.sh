#!/bin/sh
set -eu
cd "$(dirname "$0")"
test_dir=$(mktemp -d /tmp/focusdeck-app-target.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -parse-as-library -swift-version 6 advanced-lab/tests/GuardFixture.swift -o "$test_dir/GuardFixture"
python3 - "$test_dir" <<'PY'
import pathlib, plistlib, shutil, sys
root = pathlib.Path(sys.argv[1]); binary = root/'GuardFixture'
for name, ident in [('good','dev.stanislav.focusdeck.guard-fixture-two'), ('bad','dev.stanislav.focusdeck.guard-fixture-three'), ('protected','com.openai.codex')]:
    app = root/(name+'.app'); (app/'Contents/MacOS').mkdir(parents=True); shutil.copy(binary, app/'Contents/MacOS/GuardFixture')
    with (app/'Contents/Info.plist').open('wb') as f:
        plistlib.dump({'CFBundleIdentifier':ident, 'CFBundleName':name, 'CFBundleExecutable':'GuardFixture', 'CFBundlePackageType':'APPL'}, f)
PY
for name in good bad protected; do codesign --force --sign - "$test_dir/$name.app"; done
printf modified >> "$test_dir/bad.app/Contents/MacOS/GuardFixture"
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift Sources/ProtectionTarget.swift advanced-lab/Sources/StudyPolicy.swift tests/ProtectionTargetTests.swift -framework Security -o "$test_dir/target-tests"
"$test_dir/target-tests" "$test_dir/good.app" "$test_dir/bad.app" "$test_dir/protected.app"

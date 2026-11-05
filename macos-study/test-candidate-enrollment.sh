#!/bin/sh
set -eu
cd "$(dirname "$0")"
test_dir=$(mktemp -d /tmp/focusdeck-enrollment-test.XXXXXX)
trap 'rm -rf "$test_dir"' EXIT
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/CandidateDiskState.swift advanced-lab/tests/CandidateDiskStateTests.swift -framework Security -o "$test_dir/disk-tests"
"$test_dir/disk-tests"
xcrun swiftc -swift-version 6 Sources/WindowPolicy.swift Sources/WindowGuardV2.swift advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift advanced-lab/Sources/CandidateDiskState.swift advanced-lab/tests/CandidateEnrollmentSocketTests.swift -framework Security -o "$test_dir/socket-tests"
python3 -m unittest discover -s advanced-lab/tests -p 'test_production_candidate_admin.py' -v
python3 - "$test_dir/disk-tests" "$test_dir/socket-tests" <<'PY'
import importlib.util, pathlib, subprocess, sys
spec = importlib.util.spec_from_file_location('candidate_tests', pathlib.Path('advanced-lab/tests/test_production_candidate_admin.py'))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
case = module.AdminFixtureTests(); case.setUp()
try:
    module.admin.install(*case.draft())
    subprocess.run([sys.argv[1], str(module.admin.ROOT)], check=True)
    subprocess.run([sys.argv[2], str(module.admin.ROOT)], check=True)
finally:
    case.tearDown()
PY

#!/bin/bash
# Review-only until the user specifically authorizes this exact root QA action.
# The caller must stage this script root-owned and verify its approved hash
# before execution. Never invoke a writable workspace script directly as root.
set -euo pipefail
PATH=/usr/bin:/bin:/usr/sbin:/sbin
export PATH
umask 077

readonly daemon_source='/tmp/focusdeck-strict-daemon-build.3ppGQVDT/StrictRootQADaemon'
readonly daemon_sha='9c6a04f340cdc751f20aace5d403ac0f50549624171df48e82b91fdbb9fd3b75'
readonly daemon_cd='b82613d50f3dbf7bf24b5395e6b0c5e74d0b16f0'
readonly client='/tmp/focusdeck-strict-ui-qa.VMc4B6gu/StrictRootQAClient'
readonly client_sha='10e11f3f110e788b8e3791dcc4988af9942489cea5d71948d701b588e2163335'
readonly client_cd='90b302184b6c1609b8faa6ae3f6fdabcc8b42cc9'
readonly supervisor_source='/tmp/focusdeck-strict-supervisor.WYiTeudL/FixtureQASupervisor'
readonly supervisor_sha='4293e8c75b20afb9286e73b8f578f165c71169efe8272fbd5454d2b1a93d556e'
readonly supervisor_cd='c600d1135deb71debb023c1c03027cb20b22f3f0'
readonly fixture='/Users/stanislav/.codex/worktrees/ce11/ProductiveLauncher/macos-study/build/v2-fixture/GuardFixture'
readonly fixture_sha='7e8337f9d80274e8c26a17e31b2f70588abded2cce87b2bea49b77ada3c2a04b'
readonly fixture_cd='3e481e3d08c534a55081e55313fa2768f44d57bc'
readonly driver='/Users/stanislav/.codex/worktrees/ce11/ProductiveLauncher/macos-study/advanced-lab/strict-root-service/fixture-qa-driver.py'
readonly driver_sha='0e40d00713c2c284ce961f2ec69aff42b324bd0bdd228997c893765de1852a78'
readonly helper='/Library/PrivilegedHelperTools/FocusDeckStrictQA'
readonly store='/Library/Application Support/FocusDeckStrictQA'
readonly socket='/tmp/focusdeck-strict-root-qa-501.sock'

hash_is() { [[ "$(/sbin/sha256 -q "$1")" == "$2" ]]; }
cdhash_is() { [[ "$(codesign -dv --verbose=4 "$1" 2>&1 | sed -n 's/^CDHash=//p')" == "$2" ]]; }
absent() { [[ ! -e "$1" && ! -L "$1" ]]; }
[[ "$(id -u)" == 0 && -f "$daemon_source" && -f "$client" && -f "$fixture" && -f "$driver" && -f "$supervisor_source" ]]
hash_is "$daemon_source" "$daemon_sha" && hash_is "$client" "$client_sha"
hash_is "$fixture" "$fixture_sha" && hash_is "$driver" "$driver_sha"
hash_is "$supervisor_source" "$supervisor_sha"
codesign --verify --strict "$daemon_source" && cdhash_is "$daemon_source" "$daemon_cd"
codesign --verify --strict "$client" && cdhash_is "$client" "$client_cd"
codesign --verify --strict "$fixture" && cdhash_is "$fixture" "$fixture_cd"
codesign --verify --strict "$supervisor_source" && cdhash_is "$supervisor_source" "$supervisor_cd"
# A pre-existing path is never overwritten or removed. Its prior state is
# recorded as absent; otherwise this QA refuses to start.
absent "$helper" && absent "$store" && absent "$socket"
created=0
cleanup() {
    local rc=$?
    trap - EXIT INT TERM
    if [[ -x "$helper/StrictRootQADaemon" ]]; then
        "$helper/StrictRootQADaemon" --cleanup >/dev/null 2>&1 || true
    fi
    # Early setup failure only: there is no daemon/store/socket to own.
    if [[ "$created" == 1 ]] && absent "$store" && absent "$socket" && [[ -d "$helper" ]]; then
        for name in StrictRootQADaemon GuardFixture config.json fixture-qa-driver.py FixtureQASupervisor; do
            [[ ! -L "$helper/$name" ]] || exit 1
            if [[ -e "$helper/$name" ]]; then
                [[ -f "$helper/$name" && "$(stat -f '%u' "$helper/$name")" == 0 ]] || exit 1
                rm -f "$helper/$name"
            fi
        done
        rmdir "$helper" || exit 1
    fi
    if ! absent "$helper" || ! absent "$store" || ! absent "$socket"; then
        echo 'CLEANUP UNKNOWN: own QA paths remain; inspect before further action' >&2
        exit 1
    fi
    echo 'CLEANUP PASS: own helper, record and socket absent'
    exit "$rc"
}
trap cleanup EXIT INT TERM
trap '' INT TERM
mkdir -m 755 "$helper"
created=1
trap cleanup INT TERM
cp "$daemon_source" "$helper/StrictRootQADaemon"
cp "$fixture" "$helper/GuardFixture"
cp "$driver" "$helper/fixture-qa-driver.py"
cp "$supervisor_source" "$helper/FixtureQASupervisor"
chmod 755 "$helper/StrictRootQADaemon" "$helper/GuardFixture" "$helper/fixture-qa-driver.py" "$helper/FixtureQASupervisor"
hash_is "$helper/StrictRootQADaemon" "$daemon_sha"
hash_is "$helper/GuardFixture" "$fixture_sha"
hash_is "$helper/fixture-qa-driver.py" "$driver_sha"
hash_is "$helper/FixtureQASupervisor" "$supervisor_sha"
printf '{"clientHash":"%s","fixtureHash":"%s"}\n' "$client_cd" "$fixture_cd" > "$helper/config.json"
chmod 600 "$helper/config.json"
echo 'PREFLIGHT PASS: no pre-existing own QA files; no launchd job; TTL 120 seconds'
# The supervisor starts only its own process groups and bounds the wait.
"$helper/FixtureQASupervisor" --run

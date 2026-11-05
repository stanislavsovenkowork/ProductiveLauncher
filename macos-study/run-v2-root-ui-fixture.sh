#!/bin/sh
# Non-root UI side of the dedicated synthetic root-fixture QA. It does not install or stop the helper.
set -eu
cd "$(dirname "$0")"
app="$(pwd)/build/FocusDeck.app"
seed="$(pwd)/build/v2-fixture/RootFixtureSeed"
fixture="$(pwd)/build/v2-fixture/GuardFixture"
expected_cdhash=98bfd572b7663875c8bd7aa98e2c1ef78a917d51
expected_seed=7a6425ea67ad7fd39b372e77fc1df2f60d0ab821d771ba4afd1b938e13c1bbdf
expected_fixture=7e8337f9d80274e8c26a17e31b2f70588abded2cce87b2bea49b77ada3c2a04b
actual_cdhash=$(codesign -dv --verbose=4 "$app" 2>&1 | sed -n 's/^CDHash=//p')
[ "$actual_cdhash" = "$expected_cdhash" ] || { echo 'UI signature changed; inspect and rebind the fixture explicitly.' >&2; exit 1; }
[ "$(shasum -a 256 "$seed" | awk '{print $1}')" = "$expected_seed" ] || { echo 'Seed hash changed.' >&2; exit 1; }
[ "$(shasum -a 256 "$fixture" | awk '{print $1}')" = "$expected_fixture" ] || { echo 'Synthetic target hash changed.' >&2; exit 1; }
python3 - <<'PY'
import os, stat
p='/var/run/dev.stanislav.focusdeck.guard-v2-fixture.sock'
try: s=os.lstat(p)
except OSError: raise SystemExit('Fixture socket unavailable; no UI seed created.')
if s.st_uid != 0 or not stat.S_ISSOCK(s.st_mode): raise SystemExit('Unexpected socket; no UI seed created.')
PY
fixture_dir=$(mktemp -d /tmp/focusdeck-root-ui.XXXXXX)
"$seed" "$fixture_dir"
app_pid=''
cleanup() {
  trap - EXIT INT TERM
  if [ -n "$app_pid" ]; then kill "$app_pid" 2>/dev/null || true; fi
  echo "Synthetic UI data preserved: $fixture_dir"
  echo 'The separate root fixture helper must be stopped and removed through its own approved procedure.'
}
trap cleanup EXIT INT TERM
env -u FOCUSDECK_V2_FIXTURE_SOCKET FOCUSDECK_DATA_DIR="$fixture_dir" FOCUSDECK_V2_FIXTURE_BRIDGE=1 "$app/Contents/MacOS/FocusDeck" &
app_pid=$!
echo "Only the synthetic fixture target is configured; absolute window ends within 150 seconds. UI journal: $fixture_dir/window-events-v2.jsonl"
echo 'Use a separate terminal for run-v2-root-fixture-process.sh; stop this UI script with Ctrl-C.'
wait "$app_pid" || true

#!/bin/sh
# Launches and cleans up only this script's own signed synthetic child.
set -eu
cd "$(dirname "$0")"
fixture="$(pwd)/build/v2-fixture/GuardFixture"
expected=7e8337f9d80274e8c26a17e31b2f70588abded2cce87b2bea49b77ada3c2a04b
[ "$(shasum -a 256 "$fixture" | awk '{print $1}')" = "$expected" ] || { echo 'Synthetic target hash changed.' >&2; exit 1; }
codesign --verify --strict "$fixture"
identifier=$(codesign -dv --verbose=4 "$fixture" 2>&1 | sed -n 's/^Identifier=//p')
[ "$identifier" = 'dev.stanislav.focusdeck.guard-fixture' ] || { echo 'Unexpected signing identity.' >&2; exit 1; }
"$fixture" &
child=$!
cleanup() {
  trap - EXIT INT TERM
  parent=$(ps -o ppid= -p "$child" 2>/dev/null | tr -d ' ' || true)
  if [ "$parent" = "$$" ] && kill -0 "$child" 2>/dev/null; then kill -TERM "$child" 2>/dev/null || true; fi
  wait "$child" 2>/dev/null || true
}
trap cleanup EXIT INT TERM
sleep 3
parent=$(ps -o ppid= -p "$child" 2>/dev/null | tr -d ' ' || true)
if [ "$parent" = "$$" ] && kill -0 "$child" 2>/dev/null; then
  echo 'Synthetic child survived 3 seconds; this script will now stop only its own child.'
else
  status=0; wait "$child" || status=$?
  echo "Synthetic child exited during the 3-second observation (status $status); corroborate with the fixture event log."
fi

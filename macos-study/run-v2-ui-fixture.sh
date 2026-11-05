#!/bin/sh
set -eu
script_path="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$script_path")"
if [ "${1:-}" != "--qa-inner" ]; then
  scope_log=${FOCUSDECK_QA_SCOPE_LOG:-"$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl"}
  exec python3 ../harness/qa_scope.py run --log "$scope_log" -- "$script_path" --qa-inner
fi
fixture_dir=$(mktemp -d /tmp/focusdeck-ui.XXXXXX)
socket_path="$fixture_dir/socket"
data_dir="$fixture_dir/data"
app="$(pwd)/build/FocusDeck.app"
fixture="$(pwd)/build/v2-fixture/GuardFixture"
server="$(pwd)/build/v2-fixture/WindowGuardUIFixture"
[ -x "$app/Contents/MacOS/FocusDeck" ] && [ -x "$fixture" ] && [ -x "$server" ] || { echo 'Build fixture first.' >&2; exit 1; }
app_hash=$(codesign -dv --verbose=4 "$app" 2>&1 | sed -n 's/^CDHash=//p')
[ "${#app_hash}" -eq 40 ] || { echo 'Unable to confirm app signature.' >&2; exit 1; }
"$server" "$socket_path" "$data_dir" "$fixture" "$app_hash" >"$fixture_dir/server.log" 2>&1 &
server_pid=$!
app_pid=''
cleanup() {
  trap - EXIT INT TERM
  if [ -n "$app_pid" ]; then kill "$app_pid" 2>/dev/null || true; wait "$app_pid" 2>/dev/null || true; fi
  if kill -0 "$server_pid" 2>/dev/null; then
    if ! "$server" --stop "$socket_path"; then echo 'Fixture shutdown was not confirmed; inspect its owned child before cleanup.' >&2; kill "$server_pid" 2>/dev/null || true; fi
  fi
  echo "Fixture files preserved: $fixture_dir"
}
trap cleanup EXIT INT TERM
tries=0
while [ ! -S "$socket_path" ]; do
  kill -0 "$server_pid" 2>/dev/null || { cat "$fixture_dir/server.log" >&2; exit 1; }
  tries=$((tries + 1)); [ "$tries" -lt 50 ] || { echo 'Fixture server did not start.' >&2; exit 1; }
  sleep 0.1
done
FOCUSDECK_DATA_DIR="$data_dir" FOCUSDECK_V2_FIXTURE_BRIDGE=1 FOCUSDECK_V2_FIXTURE_SOCKET="$socket_path" "$app/Contents/MacOS/FocusDeck" &
app_pid=$!
echo "Fixture UI running; events: $data_dir/ui-fixture-events.jsonl; journal: $data_dir/window-events-v2.jsonl"
echo 'Close the test window or interrupt this script to stop the fixture service. Synthetic data remains in the printed temporary directory.'
wait "$app_pid" || true

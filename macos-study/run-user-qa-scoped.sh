#!/bin/sh
# Run only the separately bundled synthetic user-mode QA app; no service installation.
set -eu
script_path="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
cd "$(dirname "$script_path")"
if [ "${1:-}" != "--qa-inner" ]; then
  [ "$#" -eq 1 ] || { echo 'Usage: run-user-qa-scoped.sh /absolute/path/to/FocusDeck.app' >&2; exit 2; }
  scope_log=${FOCUSDECK_QA_SCOPE_LOG:-"$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl"}
  exec python3 ../harness/qa_scope.py run --log "$scope_log" -- "$script_path" --qa-inner "$1"
fi
app=${2:-}
case "$app" in /*.app) ;; *) echo 'Absolute QA .app path required' >&2; exit 2;; esac
bundle=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Contents/Info.plist")
[ "$bundle" = 'dev.stanislav.focusdeck.userprotection.qa.ui' ] || { echo 'Only the dedicated synthetic QA bundle is allowed' >&2; exit 1; }
[ -t 0 ] || { echo 'Interactive terminal required to confirm QA cleanup' >&2; exit 1; }
"$app/Contents/MacOS/FocusDeck" &
pid=$!
trap 'kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; exit 75' INT TERM
wait "$pid" || true
printf '%s\n' 'Close/stop all synthetic QA processes and its QA service before continuing. Personal scope remains excluded until you confirm.'
printf '%s' 'Type done to confirm QA has stopped: '
if ! IFS= read -r confirmation; then echo 'Not confirmed; scope remains open.' >&2; exit 75; fi
[ "$confirmation" = done ] || { echo 'Not confirmed; scope remains open.' >&2; exit 75; }

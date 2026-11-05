#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
fixture=$(mktemp -d /tmp/focusdeck-core-smoke.XXXXXX)
trap 'rm -rf "$fixture"' EXIT
./build/session-tests "$fixture/session"
FOCUSDECK_DATA_DIR="$fixture/session" ./build/receiver-isolation-tests "$fixture/session"
python3 ../shared/validate_events.py "$fixture/session/events.jsonl"
FOCUSDECK_DATA_DIR="$fixture/focusdeck-model" ./build/session-model-tests
./build/focus-bridge-tests "$fixture/focus-bridge"
./build/daily-report-tests
printf '%s\n' 'Core smoke passed; no blocking engine, personal app, Focus or installed receiver changed.'

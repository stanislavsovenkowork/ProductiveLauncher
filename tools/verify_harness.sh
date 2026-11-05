#!/bin/sh
# Reproducible safe checks. No installation, root, app control, VPN or personal vault.
set -eu
cd "$(dirname "$0")/.."
python3 -m unittest discover -s harness/tests -v
python3 -m unittest discover -s integration/tests -v
mkdir -p build/verification
xcrun swiftc -parse-as-library shared/ObsidianTaskCatalog.swift integration/swift/ObsidianTaskCatalogTests.swift -o build/verification/catalog-tests
build/verification/catalog-tests
sh macos-study/build-app.sh
sh macos-study/tests/smoke.sh
sh harness/native/build.sh
python3 -m unittest discover -s app/src/test/python -p test_focus_block_migration.py -v
FOCUSDECK_FIXTURE_PATH="$PWD/build/verification/android-session.jsonl" \
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}" \
ANDROID_HOME="${ANDROID_HOME:-/Users/stanislav/Library/Android/sdk}" \
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --rerun-tasks
python3 shared/validate_events.py build/verification/android-session.jsonl

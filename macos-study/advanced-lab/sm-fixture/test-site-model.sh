#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ "${1:-}" != --qa-inner ]]; then
  exec python3 harness/qa_scope.py run --log "$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl" -- "$PWD/macos-study/advanced-lab/sm-fixture/test-site-model.sh" --qa-inner
fi
qa_dir=$(mktemp -d /tmp/focusdeck-site-model-qa.XXXXXXXX)
cleanup() {
  local pid command
  while read -r pid; do
    [[ -n "$pid" ]] || continue
    command=$(ps -p "$pid" -o command= || true)
    [[ "$command" == "$qa_dir/Agent" ]] && kill -TERM "$pid" 2>/dev/null || true
  done < <(pgrep -f "$qa_dir/Agent" || true)
  for _ in {1..40}; do
    if ! pgrep -f "$qa_dir/Agent" >/dev/null; then return; fi
    sleep .05
  done
  echo "Synthetic agent cleanup not confirmed: $qa_dir" >&2
  trap - EXIT; exit 75
}
trap cleanup EXIT
arch="$(uname -m)-apple-macos15.0"
common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift)
xcrun swiftc -target "$arch" -swift-version 6 -D USER_GUARD_TEST -parse-as-library "${common[@]}" macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift macos-study/advanced-lab/Sources/UserProtectionLedger.swift macos-study/advanced-lab/Sources/UserProtectionAgent.swift -framework Security -o "$qa_dir/Agent"
xcrun swiftc -target "$arch" -swift-version 6 -D SESSION_MODEL_TEST -D USER_GUARD_MAIN macos-study/Sources/SessionState.swift "${common[@]}" macos-study/Sources/ProtectionTarget.swift macos-study/Sources/CalendarServiceControl.swift macos-study/advanced-lab/Sources/WindowGuardV2Transport.swift macos-study/Sources/ExternalProtection.swift macos-study/Sources/DailyReport.swift macos-study/Sources/LocalReceiver.swift macos-study/Sources/LocalCollectionControl.swift macos-study/Sources/RecoveryControl.swift macos-study/Sources/FocusShortcutsBridge.swift shared/ObsidianTaskCatalog.swift macos-study/Sources/FocusDeckApp.swift macos-study/advanced-lab/tests/SiteDomainModelTests.swift -framework SwiftUI -framework AppKit -framework Security -framework ServiceManagement -o "$qa_dir/ModelTests"
FOCUSDECK_DATA_DIR="$qa_dir/ui" "$qa_dir/ModelTests" "$qa_dir/Agent" "$qa_dir/agent-data" "$qa_dir/agent.sock" || exit 75

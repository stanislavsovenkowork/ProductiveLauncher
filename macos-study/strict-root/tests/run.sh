#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
out=$(mktemp -d /tmp/focusdeck-strict-model.XXXXXXXX)
xcrun swiftc -swift-version 6 -parse-as-library \
 macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift \
 macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift \
 macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/Sources/StrictRootWindow.swift macos-study/Sources/StrictRootPresentation.swift \
 macos-study/strict-root/tests/StrictRootTests.swift -framework Security -o "$out/StrictRootTests"
"$out/StrictRootTests"

common=(macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift macos-study/Sources/StrictRootWindow.swift)
xcrun swiftc -swift-version 6 -parse-as-library "${common[@]}" macos-study/strict-root/tests/FakeAuthority.swift -framework Security -o "$out/FakeAuthority"
codesign --force --sign - --identifier dev.stanislav.focusdeck.strict.synthetic "$out/FakeAuthority" >/dev/null 2>&1
xcrun swiftc -swift-version 6 -D STRICT_ROOT_IPC_TEST -parse-as-library "${common[@]}" macos-study/Sources/StrictRootIPC.swift macos-study/strict-root/tests/StrictRootIPCTests.swift -framework Security -o "$out/IPCTests"
"$out/IPCTests" "$out/FakeAuthority"

xcrun swiftc -swift-version 6 -D STRICT_ROOT_IPC_TEST -parse-as-library "${common[@]}" macos-study/Sources/StrictRootIPC.swift macos-study/Sources/StrictRootPresentation.swift macos-study/Sources/StrictRootController.swift macos-study/strict-root/tests/StrictRootControllerTests.swift -framework Security -framework Combine -o "$out/ControllerTests"
"$out/ControllerTests"

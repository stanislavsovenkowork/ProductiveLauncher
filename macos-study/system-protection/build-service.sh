#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
destination="$1"
mkdir -p "$(dirname "$destination")"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -parse-as-library \
  macos-study/Sources/WindowPolicy.swift macos-study/Sources/WindowGuardV2.swift \
  macos-study/advanced-lab/Sources/StudyPolicy.swift macos-study/advanced-lab/Sources/RootCommandCore.swift \
  macos-study/advanced-lab/Sources/RootGuardCore.swift macos-study/advanced-lab/Sources/RootSocket.swift \
  macos-study/advanced-lab/Sources/ProcessSignatureRequirement.swift \
  macos-study/advanced-lab/strict-root-service/StrictAppEnforcer.swift \
  macos-study/Sources/SystemProtectionProtocol.swift macos-study/system-protection/DomainResolver.swift \
  macos-study/system-protection/BrowserProxy.swift macos-study/system-protection/BrowserRuntimePolicy.swift macos-study/system-protection/ManagedBrowser.swift macos-study/system-protection/BlockedApplications.swift macos-study/system-protection/Commitment.swift macos-study/system-protection/ServiceCommand.swift macos-study/system-protection/Service.swift \
  -framework Security -o "$destination"
codesign --force --sign - --options runtime --identifier dev.stanislav.focusdeck.protection.service "$destination"
codesign --verify --strict "$destination"

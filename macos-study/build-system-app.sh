#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
bundle="${1:-build/FocusDeck System.app}"
mkdir -p "$bundle/Contents/MacOS" "$bundle/Contents/Resources" "$bundle/Contents/Library/HelperTools"
bundle="$(cd "$bundle" && pwd)"
../macos-study/system-protection/build-service.sh "$bundle/Contents/Library/HelperTools/FocusDeckProtectionService"
xcrun swiftc -target "$(uname -m)-apple-macos15.0" -swift-version 6 -D SYSTEM_PROTECTION \
 Sources/SessionState.swift Sources/WindowPolicy.swift Sources/WindowGuardV2.swift Sources/ProtectionTarget.swift \
 advanced-lab/Sources/StudyPolicy.swift advanced-lab/Sources/RootCommandCore.swift advanced-lab/Sources/RootGuardCore.swift \
 advanced-lab/Sources/RootSocket.swift advanced-lab/Sources/WindowGuardV2Transport.swift Sources/ExternalProtection.swift \
 Sources/DailyReport.swift Sources/LocalReceiver.swift Sources/LocalCollectionControl.swift Sources/RecoveryControl.swift \
 Sources/FocusShortcutsBridge.swift ../shared/ObsidianTaskCatalog.swift Sources/SystemProtectionProtocol.swift \
 Sources/SystemProtectionView.swift Sources/CodexAppearance.swift Sources/DeckWorkspaceView.swift Sources/FocusDeckApp.swift \
 -framework SwiftUI -framework AppKit -framework Security -o "$bundle/Contents/MacOS/FocusDeck"
cp ../shared/session-policy-v1.json "$bundle/Contents/Resources/"
cp -R Assets/CodexIcons "$bundle/Contents/Resources/"
cp Assets/AppIcon/AppIcon.icns "$bundle/Contents/Resources/"
/usr/bin/python3 - "$bundle" <<'PLIST'
import sys, plistlib
from pathlib import Path
p=Path(sys.argv[1])/'Contents/Info.plist'
d={'CFBundleIdentifier':'dev.stanislav.focusdeck.system', 'CFBundleExecutable':'FocusDeck',
'CFBundleName':'FocusDeck System','CFBundleDisplayName':'FocusDeck','CFBundlePackageType':'APPL',
'CFBundleShortVersionString':'0.12.2','CFBundleVersion':'17','LSMinimumSystemVersion':'15.0',
'NSHighResolutionCapable':True,'CFBundleIconFile':'AppIcon'}
p.write_bytes(plistlib.dumps(d))
PLIST
codesign --force --sign - --options runtime --identifier dev.stanislav.focusdeck.system "$bundle"
codesign --verify --deep --strict "$bundle"
printf '%s\n' "$bundle"

#!/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out=${1:-"$base/build"}
mkdir -p "$out"
xcrun swiftc -O "$base/ContextCore.swift" "$base/ObserverMain.swift" -framework AppKit -o "$out/FocusDeckContextObserver"
xcrun swiftc "$base/ContextCore.swift" "$base/ContextTests.swift" -o "$out/ContextTests"
"$out/ContextTests"

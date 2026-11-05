#!/bin/sh
# Explicit fixture actions only. macOS displays the administrator dialog; no password is handled here.
set -eu
cd "$(dirname "$0")"
[ "$(id -u)" -ge 500 ] || { echo 'Run as the signed-in user, not root.' >&2; exit 1; }
plan=0
if [ "${1-}" = '--plan' ]; then plan=1; shift; fi
[ "$#" -eq 1 ] || { echo 'Usage: root-fixture-authorization.sh [--plan] install|run|stop|audit|remove' >&2; exit 1; }
action=$1
case "$action" in install|run|stop|audit|remove) ;; *) echo 'Unknown fixture action.' >&2; exit 1;; esac
root=$(pwd)
admin="$root/advanced-lab/v2-fixture-admin.py"
script="$root/advanced-lab/FixtureAuthorization.applescript"
app="$root/build/FocusDeck.app"
guard="$root/build/v2-fixture/FocusDeckV2FixtureGuard"
fixture="$root/build/v2-fixture/GuardFixture"
control="$root/build/v2-fixture/FocusDeckV2FixtureControl"
admin_sha=60616ba4c6d4b724de0b58b22624665a030fe27fc1c9d572f4c8b8aa6f85bf1a
script_sha=526cbd53b0857f6163e406081e507d55b592088447c2737360beb7624ae882b5
guard_sha=c538385e08bdecb6ac5ecc38f36f534d4972ca6a64ebbbab944d32704e708b0f
fixture_sha=7e8337f9d80274e8c26a17e31b2f70588abded2cce87b2bea49b77ada3c2a04b
control_sha=be5529d6a0c6d2297a590b5ab20b19fa9d652237c8926db2725d00b2fe93eaad
hash_of() { shasum -a 256 "$1" | awk '{print $1}'; }
[ "$(hash_of "$admin")" = "$admin_sha" ] && [ "$(hash_of "$script")" = "$script_sha" ] || { echo 'Fixture authorization code changed; no administrator action performed.' >&2; exit 1; }
if [ "$action" = install ]; then
  [ "$(hash_of "$guard")" = "$guard_sha" ] && [ "$(hash_of "$fixture")" = "$fixture_sha" ] && [ "$(hash_of "$control")" = "$control_sha" ] || { echo 'Fixture binary hash changed; no administrator action performed.' >&2; exit 1; }
  cdhash=$(codesign -dv --verbose=4 "$app" 2>&1 | sed -n 's/^CDHash=//p')
  [ "$cdhash" = 98bfd572b7663875c8bd7aa98e2c1ef78a917d51 ] || { echo 'App CDHash changed; no administrator action performed.' >&2; exit 1; }
fi
if [ "$plan" -eq 1 ]; then
  echo "Review only: fixture action=$action; one synthetic target; no root action or system dialog opened."
  exit 0
fi
if [ "$action" = install ]; then
  exec /usr/bin/osascript "$script" "$action" "$admin" "$(id -u)" "$app" "$guard" "$guard_sha" "$fixture" "$fixture_sha" "$control" "$control_sha"
fi
exec /usr/bin/osascript "$script" "$action" "$admin"

#!/bin/bash
# Explicit per-user registration; no browser profile or network changes.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd -P)
app=$(cd "$here/../../.." && pwd -P)
host="$app/Contents/Library/HelperTools/FocusDeckSiteHost"
mode=${1:-install}
[[ "$mode" == install || "$mode" == uninstall ]] || { echo 'Используйте install или uninstall.' >&2; exit 2; }
"$host" "--$mode"
if [[ "$mode" == uninstall ]]; then
 echo 'Регистрация отключена. Выключите или удалите расширение на chrome://extensions.'
else
 echo "Откройте chrome://extensions, включите режим разработчика и загрузите распакованное расширение из: $here"
 echo 'Проверьте значок расширения; выбор доменов и включение службы находятся в FocusDeck.'
fi

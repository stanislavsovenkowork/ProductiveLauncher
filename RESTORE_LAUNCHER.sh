#!/bin/sh
# Страховка на случай, если FocusDeck перестанет запускаться и телефон
# останется без домашнего экрана. Возвращает тот лаунчер, что стоял раньше.
#
# Запуск с Mac, телефон подключён по USB с включённой отладкой:
#   sh RESTORE_LAUNCHER.sh            — вернуть minimalist phone
#   sh RESTORE_LAUNCHER.sh pixel      — вернуть штатный Pixel Launcher
#
# Если Mac рядом нет: на телефоне работают Настройки → Приложения →
# Приложения по умолчанию → Приложение для главного экрана. Экран настроек
# открывается из шторки уведомлений, домашний экран для этого не нужен.

set -e

ADB="adb"
command -v "$ADB" >/dev/null 2>&1 || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || {
    echo "adb не найден. Установи platform-tools или добавь их в PATH." >&2
    exit 1
}

"$ADB" get-state >/dev/null 2>&1 || {
    echo "Телефон не виден. Подключи по USB и разреши отладку." >&2
    exit 1
}

case "${1:-minimalist}" in
    pixel)
        TARGET="com.google.android.apps.nexuslauncher/.NexusLauncherActivity"
        NAME="Pixel Launcher"
        ;;
    *)
        TARGET="com.qqlabs.minimalistlauncher/com.qqlabs.minimalistlauncher.ui.MainActivity"
        NAME="minimalist phone"
        ;;
esac

"$ADB" shell cmd package set-home-activity "$TARGET"
echo "Домашний экран возвращён: $NAME"
"$ADB" shell cmd shortcut get-default-launcher

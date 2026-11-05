---
name: qa-device-etiquette
description: "How to QA on the user's personal Pixel without disturbing them"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 66f054fa-ffd7-4e48-86a7-1a5ce67c8bf7
  modified: 2026-09-29T07:32:08.795Z
---

<!-- FOCUSDECK-PROJECT-CONTRACT-20261103:BEGIN -->
## ОБЯЗАТЕЛЬНЫЙ КОНТРАКТ ПОЛЬЗОВАТЕЛЯ — ТОЛЬКО PRODUCTIVELAUNCHER / FOCUSDECK

ЭТИ ПРАВИЛА ДЕЙСТВУЮТ ТОЛЬКО ДЛЯ ЭТОГО ПРОЕКТА. ОБЩИЕ НАСТРОЙКИ CODEX И ДРУГИЕ ПРОЕКТЫ НЕ МЕНЯТЬ. ПРИ ПРОТИВОРЕЧИИ СО СТАРЫМИ ПРОЕКТНЫМИ ПЛАНАМИ ПРИМЕНЯТЬ ЭТО ПРЯМОЕ УКАЗАНИЕ ПОЛЬЗОВАТЕЛЯ ОТ 3 НОЯБРЯ 2026.

КАЖДОЕ СООБЩЕНИЕ ПО ПРОЕКТУ, ВКЛЮЧАЯ COMMENTARY, ИТОГИ, ВОПРОСЫ, ПОРУЧЕНИЯ И ОТЧЁТЫ СУБАГЕНТОВ, НАЧИНАТЬ ТРЕМЯ КОРОТКИМИ ПУНКТАМИ КАПСОМ:
1. КОНЕЧНАЯ ЦЕЛЬ — ПОЛЕЗНЫЙ, ГОТОВЫЙ К ИСПОЛЬЗОВАНИЮ FOCUSDECK НА MAC И PIXEL СО СТОЙКОЙ СИСТЕМНОЙ БЛОКИРОВКОЙ. НАЗВАТЬ КОНКРЕТНЫЙ ИНКРЕМЕНТ ТЕКУЩЕГО ШАГА, ЕГО ПОЛЬЗУ И ПРОВЕРЯЕМЫЙ РЕЗУЛЬТАТ: ЧТО ИМЕННО ПОЛЬЗОВАТЕЛЬ ПОЛУЧИТ В ПРОГРАММЕ. ОБЪЯСНИТЬ, ПОЧЕМУ ЭТОТ ШАГ ДАЁТ ПРИКЛАДНОЙ РЕЗУЛЬТАТ, А НЕ ЕЩЁ ЧАС ПОДГОТОВКИ. НЕ ОБЕЩАТЬ НЕПРОВЕРЕННУЮ ГОТОВНОСТЬ; ДЛЯ ШАГА ТОЛЬКО С ИНСТРУКЦИЯМИ ПРЯМО УКАЗАТЬ, ЧТО ФУНКЦИЮ ОН НЕ ДОБАВЛЯЕТ.
2. ПРИОРИТЕТ — ПИСАТЬ ПРИКЛАДНОЙ КОД И ДАВАТЬ ГОТОВЫЙ ПРОДУКТ. ПРОВЕРКИ МИНИМАЛЬНО НЕОБХОДИМЫЕ, ПРЕЖДЕ ВСЕГО В ИТОГОВОМ ПРИЛОЖЕНИИ НА РЕАЛЬНОМ MAC/PIXEL. ПОЛЬЗОВАТЕЛЬ ЯВНО ПРИНИМАЕТ ТЕХНИЧЕСКИЕ РИСКИ И РИСКИ БЕЗОПАСНОСТИ ЭТОЙ РАЗРАБОТКИ. НЕ ПОДМЕНЯТЬ ПРОДУКТ НОВЫМИ ФИКСТУРАМИ, ДЕМОНСТРАЦИОННЫМИ ПРОГРАММАМИ, АУДИТАМИ, ОТЧЁТАМИ И ПЕРЕСТРАХОВОЧНЫМИ ПРОЦЕССАМИ. НЕ ЗАПРАШИВАТЬ ПОВТОРНО ОБЩИЕ РАЗРЕШЕНИЯ. РЕАЛЬНЫЕ ОГРАНИЧЕНИЯ ПЛАТФОРМЫ/ИНСТРУМЕНТА СОБЛЮДАТЬ И НАЗЫВАТЬ ТОЧНО.
3. ТРЕБОВАНИЕ — STOP, QUIT, СМЕНА БРАУЗЕРА И ОБЫЧНОЕ УДАЛЕНИЕ НЕ ДОЛЖНЫ БЫСТРО СНИМАТЬ ЗАПРЕТ; ДОСРОЧНОЕ СНЯТИЕ ДОЛЖНО ТРЕБОВАТЬ СУЩЕСТВЕННОГО ВРЕМЕНИ.

ТРЕТИЙ ПУНКТ ПОВТОРЯТЬ ДОСЛОВНО. НЕ ЗАМЕНЯТЬ ЕГО ФРАЗАМИ ОБ ОТСУТСТВИИ КНОПКИ ИЛИ О ЗАКРЫТИИ ИНТЕРФЕЙСА. СТОЙКОСТЬ К ОБХОДАМ, ОТКЛЮЧЕНИЮ И УДАЛЕНИЮ — ОБЯЗАТЕЛЬНЫЙ КРИТЕРИЙ ПРИЁМКИ, А НЕ УТВЕРЖДЕНИЕ О НАЛИЧИИ КОДА. ОРИЕНТИР ПОЛЬЗОВАТЕЛЯ ДЛЯ СУЩЕСТВЕННОГО ВРЕМЕНИ — ОКОЛО ЧАСА. ЧЕСТНО РАЗДЕЛЯТЬ НАПИСАНО, УСТАНОВЛЕНО И ПРОВЕРЕНО.

ЭТИ ТРИ ПУНКТА ПЕРЕДАВАТЬ ДОСЛОВНО В КАЖДОМ НОВОМ ПОРУЧЕНИИ ИСПОЛНИТЕЛЮ И СОХРАНЯТЬ ПРИ КОМПАКЦИИ/ПЕРЕДАЧЕ КОНТЕКСТА. ПО ПОСЛЕДНЕМУ ПРЯМОМУ УКАЗАНИЮ ПОЛЬЗОВАТЕЛЯ ROOT САМ ПИШЕТ ПРИКЛАДНОЙ КОД; ПРЕЖНЕЕ ОГРАНИЧЕНИЕ ТОЛЬКО КООРДИНАЦИЕЙ ОТМЕНЕНО. COMPUTER USE ДЕЛЕГИРОВАТЬ ТОЛЬКО GPT-6.1-TERRA XHIGH, БЕЗ ЗАПРОСА FAST. ТЕКУЩИЙ ЭТАП ТОЛЬКО MAC, PIXEL НЕ ТРОГАТЬ. ПАРАЛЛЕЛИТЬ ТОЛЬКО НЕЗАВИСИМЫЕ ПРИКЛАДНЫЕ ИНКРЕМЕНТЫ С ВЛАДЕЛЬЦЕМ И КРИТЕРИЕМ ГОТОВНОСТИ. КАЖДАЯ ИТЕРАЦИЯ ДОЛЖНА ЗАКАНЧИВАТЬСЯ ИЗМЕНЕНИЕМ В РЕАЛЬНОЙ ПРОГРАММЕ И КОРОТКОЙ ПРОВЕРКОЙ; ЕСЛИ ЭТО НЕДОСТИЖИМО, СРАЗУ НАЗВАТЬ ПРЕПЯТСТВИЕ И НЕ ПРОДОЛЖАТЬ БЕЗРЕЗУЛЬТАТНЫЙ РАСХОД.

ПРИНЯТИЕ РИСКОВ НЕ ОТМЕНЯЕТ ПРЯМОГО ТРЕБОВАНИЯ СОХРАНЯТЬ ДАННЫЕ И СВЯЗЬ: MAC ПОЛУЧАЕТ ИНТЕРНЕТ ЧЕРЕЗ PIXEL. НЕ ПЕРЕЗАГРУЖАТЬ ТЕЛЕФОН И НЕ МЕНЯТЬ РАЗДАЧУ, USB, VPN И МАРШРУТЫ. СОХРАНЯТЬ ПРОЗРАЧНЫЙ КОНЕЧНЫЙ ВЫХОД; НЕ ОБЕЩАТЬ НЕДОКАЗАННУЮ АБСОЛЮТНУЮ ЗАЩИТУ ОТ ВЛАДЕЛЬЦА С ROOT. НЕ ВЫДАВАТЬ ЗАПИСАННЫЕ ТРЕБОВАНИЯ, СБОРКУ ИЛИ ЧИСЛО ТЕСТОВ ЗА УСТАНОВЛЕННУЮ ЗАЩИТУ И ДОКАЗАННУЮ ПОЛЬЗУ.
<!-- FOCUSDECK-PROJECT-CONTRACT-20261103:END -->

The Pixel 10a is the user's daily phone and is often in use (PiP video, Telegram) during sessions. QA goes into the `.qa` debug package, which has its own SAF grant to a fictional vault in `/sdcard/Documents/FocusDeckQA-Life`, never the personal DB1-Sync. `adb input text` only types Latin text. Tap centres can land under a PiP window, so check the coordinates first.

**Why:** On 2026-09-29 the user switched to Telegram mid-QA, and a scripted Back landed in their chat.

In the same session the user cut a long device-QA run short to test it themselves. Keep device QA to the main paths, then hand over.

**How to apply:** Before each scripted burst, check the foreground app with `dumpsys activity activities | grep topResumedActivity`. If it isn't `dev.stanislav.focusdeck.qa`, stop and tell the user. Once a fix is QA'd and tests pass, install the release build right away without asking: on 2026-09-29 the user answered "ставь сразу" when asked. Use `adb install -r`, never uninstall, so personal data stays. Related: [[ui-taste-no-filler]]

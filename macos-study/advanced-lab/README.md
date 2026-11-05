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

> 2026-10-28 update: development of selected app/site protection was resumed. The historical notes below describe an earlier cancellation and do not describe current scope. The separate [V2 fixture candidate and live-QA plan](V2-FIXTURE-QA.md) currently cover only one synthetic process; they do not establish real app, browser or website protection. No V2 root daemon has been installed or live-tested.

# Advanced lab — paused, OFF, not a product dependency

On 2026-10-28 the user cancelled development of our own website/browser blocker in favour of external software. NE, DNS/hosts and browser experiments are preserved here; no further development or network/root QA is scheduled. Core FocusDeck.app does not build, embed, observe, install or depend on these engines. Archived shell scripts are deliberately non-executable records, not onboarding steps.

Root app-gating remains an optional isolated experiment, OFF. RootFixture v2 actually passed on this Mac: a test-owned signed child survived one 0.5s pause, then received TERM; authenticated kernel-root peer/current policy was observed; repeat pause was rejected; expiry was observed. The helper was removed afterward. No personal app or DNS restriction was activated in that trial. Global/strict protection was never established.

The frozen artifact under ../build/FocusDeckRootFixture.app is for evidence only; it is not embedded in the normal app. Do not run/reinstall experiments without an explicit renewed task. Swift sources, templates, tests and archived build commands are retained for review.

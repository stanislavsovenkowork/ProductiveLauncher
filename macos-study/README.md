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

# FocusDeck for Mac — core app

The normal app supports one work/study action, an existing Obsidian task picker,
a live timer, one bounded break, saved context and local evidence-based summaries.
It starts without root access, a NetworkExtension or a website blocker.

The separate user-mode protection candidate can restrict selected signed apps;
it has not passed system acceptance on personal apps and does not filter individual
websites. The core build below does not include that service, and its external
protection adapter remains **not connected**. Sessions do not require protection.
For setup, limitations and controls, see the [user-mode candidate instructions](advanced-lab/USER-PROTECTION-CANDIDATE.md). Cold Turkey is excluded.

## Build and safe checks

From the repository root:

```
./macos-study/build-app.sh
./macos-study/tests/smoke.sh
```

Output: `macos-study/build/FocusDeck.app`, a double-clickable SwiftUI app for macOS15+
with a local ad-hoc hardened-runtime signature. Build requires the Mac's existing
Xcode command-line tools. Core scripts do not compile, embed, install, observe or
activate root/NE/DNS/browser engines. Tests use fictional temporary context and
fake Focus runners; they do not alter personal apps, Focus or installed collectors.

## Session behavior

- Choose Work or Study and one immediate action, or explicitly select one daily
  note and an unchecked task from its `## Задачи` section. The shared adapter reads
  only that file, keeps the existing stable task ID and never edits the vault.
- 25minutes is a **nominal target**, not a hard cutoff or a proven universal dose.
  After it ends, `+overrun` and actual timer accounting continue until explicit Stop
  or completion. A completed interval does not mark the Obsidian task done.
- One300-second break preserves task/context. Its expiry offers explicit Continue;
  it does not fabricate resumed work. These initial parameters match the shared
  session policy and remain engineering hypotheses.
- Timer intervals use live monotonic measurements. Known pauses and unknown gaps
  are excluded. Closed60-second heartbeats make ongoing work available to reports.
  Sleep, suspension, clock changes and relaunch close the known segment, retain
  context and mark the gap unknown. A heartbeat absence is not an uninstall event.
- Relaunch recovers the action, remaining/overrun time and next-step note. Legacy
  context is migrated; corrupt context blocks writes until the user explicitly
  archives the original. Existing history is retained.

## Local data, summaries and voluntary controls

Private app data:

- `~/Library/Application Support/FocusDeckStudy/session.json`
- `~/Library/Application Support/FocusDeckStudy/events.jsonl`

Directory0700/files0600. Event JSONL follows `../shared/event-v1.schema.json`.
`focusSeconds` means measured timer duration with `evidence:timer`, not proven
attention or useful output. Unmeasured metrics stay null. Task text/next-action
notes remain in private context; events contain only the existing taskRef.

The app reads the explicitly installed harness configuration at
`~/Library/Application Support/FocusDeckHarness/receiver/receiver-config.json` and
sends stable-ID batches only to authenticated loopback. It keeps a persisted
accepted cursor and retries; no token is embedded or logged. Timer and receiver
loops are independent, so a slow receiver does not pause accounting.

The summary card reads `FocusDeckHarness/reports/current-day.json`, separates Work
and Study, shows timer estimates separately from observed foreground context, and
labels missing, partial, stale or clock-skewed data. Missing data is never shown
as zero work. Summary/receiver/context services are installed separately and can
continue after the app closes or is removed. The in-app controls invoke only the
installed user-level Stop/Start scripts; no implicit restart occurs. Stopping them
preserves history and tasks. Context retention is7days; reports90days.

## Optional safe notification setup

The app does not assume that notifications are silenced. Its optional public
Shortcuts bridge is configured only after the owned commands are created and
checked. Configuration lives privately at `FocusDeckStudy/focus-shortcuts.json`:

```
{"schemaVersion":1,"enterStudy":"FocusDeck Study Quiet","probe":"FocusDeck Focus Probe","verifiedOnThisMac":false}
```

The Enter command receives a JSON file with `schemaVersion`, `sessionId` and
`requestedSeconds` (1…1800). It must read Current Focus first: preserve every
pre-existing Focus and return `preserved_existing`; only when none exists may it
set the dedicated `FocusDeck Study` until a bounded date, returning
`applied_until|<ISO8601 with timezone>`. Read failure must stop without switching.
The read-only Probe returns `dedicated`, `none`, `other` or `unknown`; private mode
names are not exported. Native bridge uses `/usr/bin/shortcuts run`, never private
DND files/APIs. Stop never runs Focus Off or attempts a blind restoration. A bounded
mode can remain until the shown time; the user can change it in Control Center.
Existing system Focus synchronization settings are preserved.

The actual Probe UI returned `other` while preserving the existing DND mode, but
the CLI run hung and Enter was not created. Integration therefore remains OFF.
`verifiedOnThisMac` must stay false until real CLI/setup acceptance succeeds.

Actual command/profile setup and behavior are an explicit separate acceptance
step. Missing setup does not block sessions, and fake-runner tests do not prove
notification suppression. Existing unrelated Focus must not be disabled for QA.

## Isolated UI QA

`FOCUSDECK_DATA_DIR=/tmp/your-unique-fixture` redirects all context/journal state and
disables receiver access/transmission, Focus changes and installed-service control.
It does not read the global private report. An explicit `FOCUSDECK_REPORT_FILE`
can select a synthetic report; the UI labels it a test summary. Only explicit
`FOCUSDECK_RECEIVER_CONFIG` opts a fixture into a chosen test receiver.

Do not write synthetic sessions to the personal receiver or production vault.
Mac→phone immutable-file publishing is a separate in-progress integration; until
configured and tested, the core app retains its local journal and does not claim
bidirectional delivery.

## Archived experiments

Website/browser/NE/DNS experiments and optional root app-gating sources are isolated
in `advanced-lab/` and are not core dependencies. The separate user-mode candidate
is also built from sources there; it is not yet accepted for personal use. The safe
RootFixture v2 child-only proof is recorded there; the helper was removed afterward.
No personal app/site blocking was validated.
See `../docs/ACCEPTANCE.md` for the current whole-product acceptance scope.

## QA scope and personal observations

`run-v2-ui-fixture.sh` records a local QA start and end in the review's private
`reports/qa-scopes.jsonl`; the review excludes overlapping observations rather
than counting them as personal activity. Before the first scope marker, QA
coverage is unknown and passive context is not attributed to personal activity.
If the wrapper is killed before it can close the scope, `qaSeparation` reports
`incomplete_open_scope` and overlapping data remain excluded until explicitly
resolved. The metadata is separate from the observer journal and does not alter
historical events.

The dedicated synthetic user-mode app can be launched with
`run-user-qa-scoped.sh /absolute/path/to/FocusDeck.app`; it keeps the scope open
until the operator confirms all QA processes and its service have stopped. Other
launch paths, including `run-v2-root-ui-fixture.sh`, `prepare-review-ui-fixture.sh`
and Android QA, do not automatically create this scope. Wrap any such run explicitly with `python3 harness/qa_scope.py run --log
'/absolute/path/to/FocusDeckHarness/reports/qa-scopes.jsonl' -- <QA command>`;
a prepared fixture alone is not a running scope. Check the `qaSeparation` field
in the report. A crashed scope must be closed only after confirming the QA process
has stopped: `python3 harness/qa_scope.py end --log '/absolute/path/to/qa-scopes.jsonl'
--id <scope-uuid>`; this records the actual resolution time, preserving uncertainty
up to that time. This mechanism does not prove that all QA was marked, and does
not retroactively distinguish historical QA.

For a multi-process root-fixture session or Android QA, keep this command running
in its own terminal **before** starting QA from another terminal:

```sh
python3 harness/qa_scope.py run --log "$HOME/Library/Application Support/FocusDeckHarness/reports/qa-scopes.jsonl" -- /bin/sh -c 'printf "%s\n" "QA scope open. Stop all QA work and helpers, then type done:"; IFS= read -r answer && [ "$answer" = done ] || exit 75'
```

For root-fixture QA, type `done` only after its UI, synthetic target and separate
helper have all stopped using their existing approved cleanup procedure. For
Android QA, type it only after the QA session and any synthetic production/export
activity have stopped. The command does not start or stop any service or device.
EOF, interruption, a wrong response, or a killed wrapper leaves the scope open;
resolve it explicitly after confirming cleanup. Direct runs without a live scope
are not covered. This scopes the Mac observer's time, but does not assert that
unrelated Android events outside the marked period are QA-free.

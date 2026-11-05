# Ritual UI acceptance

Status: **source review only; every device check below is pending**. No build, ADB, phone interaction, or new rendered screenshot was used for this pass. Main agent owns execution and screenshot feedback. Scope: `RitualScreen.kt`; callbacks, engine, shared controls, and theme remain unchanged.

## Review changes

- Keep the journal inside a content-sized card: 5–8 visible lines normally, 3–6 with keyboard/compact layout. More text scrolls inside the editor; no 12-line empty canvas.
- Measure button labels and provide a minimum height to the existing `FocusButton`; allow wrapping without shrinking type. Reflow cramped label/value pairs and tabs.
- Keep the primary action pinned on ordinary portrait screens; include it in the form's scroll area when the keyboard is visible, usable height is below 480dp, or font scale is at least 1.5. This avoids a footer consuming the editing viewport.
- Preserve editable-field semantics, omit duplicate placeholder announcements, label tabs/priority selection/disclosures, and expose plan coverage as one accessible progress status. Clear focus before collapsing the recovery editor.
- Keep rules, system restriction explanations, and emergency details in sheets. Error recovery stays contextual. No new palette, outlines, libraries, or shared-theme changes.

## Test matrix and evidence

Use morning and evening states, preview and mandatory modes. Check 320–360dp-wide portrait and landscape at default, 1.3×, and 2× font scale; also check enlarged display size. Use a Russian-capable keyboard and TalkBack. These sizes are QA targets, not a claim of tested support.

Capture: empty evening journal, long journal with IME, confirmed food, incomplete/complete plan, help sheet, and 2×-font journal/plan. Record device, width, font/display settings, state, and result beside each screenshot. Compare actual renders with Home/Journal and `life-system-ux.md`; the original HTML mockup was unavailable.

## Screens and gestures

- [ ] Open empty morning/evening ritual. Black background, opaque green-tonal cards, restrained warm accents; one clear main action. No large blank journal canvas, readiness bar above the journal, or system-help paragraphs in the default view.
- [ ] Enlarge font/display size and rotate. Labels, long task names, calories, and button text remain readable without overlap/cropping. Tabs reflow when their complete labels plus checks cannot fit. All choices remain reachable by scrolling.
- [ ] Tap the journal; type multiple paragraphs using Return. Return inserts a newline, never submits/navigates. Paste 150+ words, move the caret to start/end, select/copy/paste, and edit near the final line: caret stays reachable above the IME and editor scroll is usable.
- [ ] With the IME open, swipe on the card margin to scroll the form to its action; swipe inside a long entry to scroll text. Both gestures work without trapping the page. Hide/show IME twice: exactly one primary action, no text loss, no zero-height editing area.
- [ ] Expand “Если отвлекусь…”, enter several lines, collapse while typing, then reopen. Keyboard closes on collapse and content survives. Switch tabs and return: same draft, disclosure state, and per-step scroll position.
- [ ] From a scrolled plan, open the schedule and return. Selected ritual step, draft text, and scroll position survive. Repeat via nutrition and vault settings, then rotate/recreate the activity; do not count an unchanged activity as a restoration test.
- [ ] Open “Объём записи”. All three live counts are readable: 100 words, 600 letters/digits, 20 distinct words. Dismiss by close button, scrim tap, swipe down, and Back separately: same ritual/draft, no accidental completion.
- [ ] Check journal boundaries separately: 99 words, fewer than 600 letters/digits, fewer than 20 distinct words, then all three satisfied. Completion is unavailable for any missing condition; whitespace/punctuation do not falsely satisfy it.
- [ ] Expand unfinished tasks and tap “На завтра”. Feedback becomes “Перенесено”; repeated taps do not repeat the UI action. Long titles wrap without hiding the action.
- [ ] On food, empty/invalid entries route to editing. Valid entries enable explicit “Всё съеденное внесено”. Confirm, edit one name/calorie value or add/remove a meal, and return: confirmation resets and requires another explicit action. Wrong-date entries must not become confirmable.
- [ ] On plan, check 539 then 540 valid minutes within 08:00–23:00, overlapping entries, an invalid entry, and missing priority. Meter describes actual day coverage; its goal remains 60%. Finish requires sufficient coverage, valid entries, and a selected main task.
- [ ] Select a long-titled main task, then another. Exactly one row is selected; full-row tapping works and time remains readable. Finish with an earlier step incomplete returns to that step; finish only invokes completion when every required step is ready.
- [ ] Simulate disconnected vault/save failure. Concise error/recovery card is readable; “Подключить/Проверить” opens settings. A failed save never displays successful completion. Full error details remain available in Help.
- [ ] In mandatory mode, open “Помощь и срочный доступ”: emergency action is near the top, system prose is inside the sheet. Dismissing does not grant access. Explicit access invokes the existing 15-minute policy; used allowance shows unavailable. Preview has “Продолжить позже”; mandatory mode does not.
- [ ] While saving, repeatedly tap the main action and confirmation: no repeated enabled action. Busy/disabled labels remain readable at 2× font size.

## TalkBack and alternate input

- [ ] Swipe through header → tabs → active content → action. Fields announce meaningful labels and remain editable; decorative icons/placeholders add no duplicate focus stops. Headings are discoverable with heading navigation.
- [ ] Tabs announce selected state independently of ready/not-ready. Priority rows announce radio selection; disclosures announce expanded/collapsed. Buttons announce their action and disabled state, once each.
- [ ] Read plan coverage: one progress status with current percent, 60% goal, and 08:00–23:00 window. Word counters do not interrupt every keystroke; new save errors are announced politely.
- [ ] Open/dismiss each sheet with TalkBack and keyboard Back/Escape. Background controls cannot activate through the sheet; focus returns to a useful ritual control. External-keyboard Tab/Shift+Tab reaches all enabled actions and text fields; Enter/Space activates controls without losing drafts.
- [ ] Tap at the edges of icons, tabs, disclosures, and actions: targets are at least 48dp; adjacent targets do not overlap. Check status without relying solely on color.

## Proposed reusable visual roles (no shared-theme edit)

| Role | Existing token / behavior to reuse across the life-system UI |
|---|---|
| Canvas / cards | `background` black / opaque `surfaceContainer`; no borders or new palette |
| Selected / primary action | `secondaryContainer` / existing filled `FocusButton` |
| Warm attention | `LocalFocusDeckColors.attention` and `SectionTone.Attention`; reserve error color for actual failures |
| Hierarchy | `headlineSmall` 24sp screen/summary; `titleMedium`/`bodyLarge` 16sp content; `bodyMedium`/`labelLarge` 14sp support/actions |
| Spacing / shape | 24dp section gaps, 16dp card padding, 8dp related gaps; 4/12dp compact subgroups; existing `FocusRadius.card/field/pill` |
| Accessible controls | Minimum 48dp targets; content-measured button height, reflow instead of reduced type, scrollable actions under IME |

These are proposed mappings to the current design language, not new global tokens. Runtime risks still needing evidence: nested editor/page scrolling, IME inset changes, sheet focus return, nonlinear system font scaling, and activity/navigation restoration.

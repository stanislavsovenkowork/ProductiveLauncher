# Journal: conflict resolution QA

Checkpoint: 2026-09-29. Device results below are reported by main; this documentation pass did not run Gradle, instrumentation, or device interactions.

## Actual UI on seeded fixtures

Environment: isolated Android user11, synthetic Documents/SAF vault. Fixtures and external edits were prepared through the test probe; conflict comparison and version selection were exercised through the actual app UI. This verifies UI handling of prepared conflicts, not creation of every fixture through the app. No personal journal text is reproduced here.

| Scenario | Result and evidence |
|---|---|
| Same-field conflict → “Мой текст” | **PASS, main device QA.** Selected local version verified in the persisted file. |
| Same-field conflict → “Из Obsidian” | **PASS, main device QA.** Selected remote version verified in the persisted file. |
| Preserve unrelated content | **PASS, main device QA.** Neighbouring “День” field and task links/metadata remained intact after both choices. |
| Display remote A → delayed instrumentation writes B → tap “Мой текст” | **PASS, main device QA.** App rejected the stale choice with “Текст в Obsidian снова изменился…”; B was not overwritten. This was an actual concurrent file update while the comparison UI was open. |
| Refresh that stale comparison → choose “Из Obsidian” | **PASS, main device QA.** After the stale local-choice rejection, main refreshed the comparison and selected the remote version; the editor displayed version B. Both choices and the complete concurrent-update recovery flow have now passed through the actual UI. |

Screenshots supplied by main:

- Conflict comparison — локальный QA-снимок `journal-conflict-final.png`, не включён в репозиторий; см. [границы состава](functional/REPOSITORY.md)
- Stale comparison rejected — локальный QA-снимок `journal-stale-comparison.png`, не включён в репозиторий; см. [границы состава](functional/REPOSITORY.md)

These are local `/tmp` artifacts. Screenshots document visible state; file-content assertions above come from main's separate persisted-file checks.

The [main worklog](life-system-worklog.md) additionally records acknowledged-choice draft cleanup and retention of a private recovery copy. That is distinct from proving every crash boundary.

## Pure regression tests

These exercise parser/merge/authorization logic; they do not execute Compose gestures, SharedPreferences durability, SAF races, or process death.

| Suite | Cases | Coverage |
|---|---:|---|
| `JournalWriteSafetyTest` | 15 | Malformed/duplicate markup, unsafe input, exact multiline roundtrip, preservation of unrelated content. |
| `JournalEditorSafetyTest` | 8 | Field-only merge, same-field conflict, newer text versus older acknowledgement, root/date/field isolation, idempotent recovery model. |
| `JournalConflictResolutionTest` | 9 | Both explicit choices; changed local/remote versions; wrong root/date/field; empty remote; continued editing; malformed remote cannot appear as an empty version. |

Main's worklog reports pass16: **193 unit tests, zero failures**, plus QA app and test-only SAF probe builds. This is an aggregate build result, separate from the device scenarios above.

## Remaining targeted checks

- [x] Confirm the refreshed-comparison → remote-choice continuation: main verified version B in the editor. The separate persisted-file checks for both original choice scenarios are recorded above; no additional file-read claim is inferred from this final UI confirmation.
- [ ] Kill at private-copy commit / vault-write / selected-draft persistence / cleanup boundaries; verify both versions and recovery behaviour after restart. The successful normal-flow cleanup check does not establish atomicity across vault and preferences.
- [ ] Change vault while the comparison is open; neither the newly selected vault nor another date/field may receive the pending choice.
- [ ] Fail private-copy persistence: replacement/discard must remain blocked and both displayed versions must stay recoverable.
- [ ] Check long versions with large font, TalkBack, selection/copy, scrolling and sheet dismissal; dismissal must retain the draft.

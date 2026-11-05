package dev.stanislav.focusdeck.ui.journal

import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.VaultJournal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JournalConflictResolutionTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val root = "content://test/vault-a"
    private val target = JournalDraftTarget(day, root, JournalField.EVENING)
    private val local = JournalTextDraft(target, "Мой текст\nВторая строка \n", "Старая база", 7)
    private val fresh = VaultJournal("Новое утро", "Новый день", "Внешний вечер", "Новые выводы")
    private val comparison = JournalComparison(local, fresh.evening)

    private fun rejected(block: () -> Unit) {
        try {
            block()
            fail("A stale comparison must not authorize a destructive choice")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test fun explicitKeepMineResolvesConflictAndPreservesFreshOtherFields() {
        rejected { local.mergeInto(fresh) }

        val chosen = comparison.resolve(local, root, fresh, keepLocal = true)

        assertEquals(fresh.copy(evening = local.normalized), chosen)
        val note = DailyNoteParser.parse(day, "test.md", DailyNoteParser.template(day))
        val written = DailyNoteParser.writeJournal(note, chosen)
        assertEquals(chosen, DailyNoteParser.parse(day, "test.md", written).journal)
        assertEquals("Мой текст\nВторая строка \n", comparison.local.text)
    }

    @Test fun takeObsidianKeepsTheRemoteJournalAndRetainsBothComparisonVersions() {
        assertEquals(fresh, comparison.resolve(local, root, fresh, keepLocal = false))
        assertEquals(local, comparison.local)
        assertEquals("Внешний вечер", comparison.remote)
    }

    @Test fun changingRemoteAfterComparisonBlocksBothChoices() {
        val changed = fresh.copy(evening = "Ещё более новая запись")
        listOf(true, false).forEach { keepLocal ->
            rejected { comparison.resolve(local, root, changed, keepLocal) }
        }
    }

    @Test fun unrelatedRemoteChangesRemainIntactForEitherChoice() {
        val changed = fresh.copy(morning = "Утро изменилось после сравнения", conclusions = "Новый вывод")
        assertEquals(changed.copy(evening = local.normalized), comparison.resolve(local, root, changed, true))
        assertEquals(changed, comparison.resolve(local, root, changed, false))
    }

    @Test fun newerLocalTextRevisionOrBaselineRequiresAnotherComparison() {
        listOf(
            local.copy(text = "Более новый ввод"),
            local.copy(revision = local.revision + 1),
            local.copy(base = "Новая база"),
        ).forEach { newer ->
            listOf(true, false).forEach { keepLocal ->
                rejected { comparison.resolve(newer, root, fresh, keepLocal) }
            }
        }
    }

    @Test fun differentRootDateOrFieldNeverUsesThisAuthorization() {
        listOf(true, false).forEach { keepLocal ->
            rejected { comparison.resolve(local, "content://test/vault-b", fresh, keepLocal) }
            rejected { comparison.resolve(local, null, fresh, keepLocal) }
            rejected { comparison.resolve(null, root, fresh, keepLocal) }
            listOf(
                target.copy(root = "content://test/vault-b"),
                target.copy(date = day.plusDays(1)),
                target.copy(field = JournalField.MORNING),
            ).forEach { other ->
                rejected { comparison.resolve(local.copy(target = other), root, fresh, keepLocal) }
            }
        }
    }

    @Test fun emptyRemoteIsAValidExplicitChoiceRatherThanMissingComparisonData() {
        val empty = fresh.copy(evening = "")
        val shown = JournalComparison(local, "")
        assertEquals(empty, shown.resolve(local, root, empty, false))
        assertEquals(empty.copy(evening = local.normalized), shown.resolve(local, root, empty, true))
    }

    @Test fun eitherAcceptedVersionAllowsSubsequentNormalEditingWithoutOldConflict() {
        listOf(true, false).forEach { keepLocal ->
            val chosen = comparison.resolve(local, root, fresh, keepLocal)
            val accepted = local.copy(text = chosen.evening, base = chosen.evening, revision = 8)
            val next = accepted.copy(text = chosen.evening + "\nПродолжение", revision = 9)
            assertEquals(chosen.copy(evening = next.normalized), next.mergeInto(chosen))
        }
    }

    @Test fun brokenRemoteWrapperCannotBeOfferedAsAnEmptyVersion() {
        val content = "<details class=\"daily-journal\">\n- Вечер: Existing writing\n"
        val note = DailyNoteParser.parse(day, "test.md", content)
        assertEquals("", note.journal.evening)
        rejected { journalForComparison(note) }
        assertEquals(content, note.lines.joinToString("\n"))
    }
}

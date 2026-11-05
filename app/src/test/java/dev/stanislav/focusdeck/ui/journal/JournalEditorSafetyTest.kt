package dev.stanislav.focusdeck.ui.journal

import dev.stanislav.focusdeck.data.vault.DailyNoteParser
import dev.stanislav.focusdeck.data.vault.VaultJournal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JournalEditorSafetyTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val target = JournalDraftTarget(day, "content://test/vault-a", JournalField.EVENING)
    private val original = VaultJournal("Morning", "Day", "Evening", "Conclusions")

    @Test fun eachFieldMergesIntoFreshJournalWithoutRestoringOtherStaleFields() {
        JournalField.entries.forEach { edited ->
            val captured = JournalTextDraft(target.copy(field = edited), "New text\nSecond line", edited.read(original), 1)
            var fresh = original
            JournalField.entries.filter { it != edited }.forEach { other ->
                fresh = other.replace(fresh, "External ${other.name}")
            }

            val merged = captured.mergeInto(fresh)
            val note = DailyNoteParser.parse(day, "test.md", DailyNoteParser.template(day))
            val written = DailyNoteParser.writeJournal(note, merged)
            val readBack = DailyNoteParser.parse(day, "test.md", written).journal

            assertEquals("New text\nSecond line", edited.read(readBack))
            JournalField.entries.filter { it != edited }.forEach { other ->
                assertEquals(other.read(fresh), other.read(readBack))
            }
        }
    }

    @Test fun externalChangeToTheSameFieldBlocksStaleAutosaveAndKeepsRawDraft() {
        val draft = JournalTextDraft(target, "Local text \n\n", original.evening, 2)
        try {
            draft.mergeInto(original.copy(evening = "External change"))
            fail("Must not overwrite a changed field")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Obsidian"))
        }
        assertEquals("Local text \n\n", draft.text)
        assertEquals(original.evening, draft.base)
    }

    @Test fun restartAfterVaultWriteBeforeDraftCleanupIsIdempotent() {
        val pending = JournalTextDraft(target, "Saved text\nNext line\n", original.evening, 1)
        val alreadyWritten = original.copy(evening = pending.normalized, day = "New external day")

        assertEquals(alreadyWritten, pending.mergeInto(alreadyWritten))
    }

    @Test fun olderAcknowledgementNeverReplacesNewerTyping() {
        val saved = JournalTextDraft(target, "First version", original.evening, 1)
        val typedDuringWrite = saved.copy(text = "First version plus new words \n", revision = 2)

        val rebased = typedDuringWrite.afterAcknowledgement(saved)

        assertEquals(typedDuringWrite.text, rebased.text)
        assertEquals(typedDuringWrite.revision, rebased.revision)
        assertEquals(saved.normalized, rebased.base)
        assertEquals(typedDuringWrite.normalized,
            rebased.mergeInto(original.copy(evening = saved.normalized)).evening)
    }

    @Test fun acknowledgementsFromAnotherDateFieldOrRootCannotRebaseThisDraft() {
        val draft = JournalTextDraft(target, "Keep this text", "Keep this baseline", 5)
        listOf(
            target.copy(date = day.plusDays(1)),
            target.copy(field = JournalField.MORNING),
            target.copy(root = "content://test/vault-b"),
        ).forEach { other ->
            assertEquals(draft, draft.afterAcknowledgement(JournalTextDraft(other, "Other write", null, 3)))
            assertNotEquals(target.key, other.key)
        }
        assertEquals(target.key, target.copy().key)
    }

    @Test fun recoveryWithoutBaselineCannotOverwriteAnExistingDifferentField() {
        val draft = JournalTextDraft(target, "Recovered", null, 1)
        try {
            draft.mergeInto(original)
            fail("Unknown baseline must not authorize replacing existing text")
        } catch (_: IllegalStateException) {
            assertEquals("Recovered", draft.text)
        }
        assertEquals(original.copy(evening = "Recovered"), draft.mergeInto(original.copy(evening = "Recovered")))
    }

    @Test fun clearingAFieldIsAnEditAndPreservesOtherFreshFields() {
        val draft = JournalTextDraft(target, "", original.evening, 1)
        val fresh = original.copy(day = "External day")

        assertEquals(fresh.copy(evening = ""), draft.mergeInto(fresh))
    }

    @Test fun rejectedMarkupLeavesTheRecoverableDraftUnchanged() {
        val draft = JournalTextDraft(target, "Reflection\n</details>\n## Unexpected section", original.evening, 1)
        val note = DailyNoteParser.parse(day, "test.md", DailyNoteParser.template(day))
        try {
            DailyNoteParser.writeJournal(note, draft.mergeInto(original))
            fail("Unsafe markup must not produce a successful write")
        } catch (_: IllegalArgumentException) {
            assertEquals("Reflection\n</details>\n## Unexpected section", draft.text)
            assertEquals(original.evening, draft.base)
        }
    }
}

package dev.stanislav.focusdeck.ui.ritual

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RitualDraftTest {
    private val day = LocalDate.of(2026, 9, 29)
    private val evening = RitualWindow(RitualPhase.EVENING, day, day.plusDays(1))

    @Test fun pendingEveningPlanKeepsItsOriginalTargetAfterMidnight() {
        val draft = RitualDraft.parse("draft_EVENING_2026-09-29_ifthen", "If distracted\nreturn", "")!!
        assertEquals(day.plusDays(1), draft.day)
        assertEquals(evening, draft.window)
        assertEquals("If distracted return", draft.normalized)
        assertEquals("If distracted\nreturn", draft.text)
    }

    @Test fun pendingJournalRetainsWhitespaceForEditorButNormalizesForNote() {
        val draft = RitualDraft(evening, "journal", "My reflection \n\n", "Old reflection")
        assertEquals(day, draft.day)
        assertEquals("My reflection", draft.normalized)
        assertTrue(draft.text.endsWith("\n\n"))
        assertTrue(draft.canApplyTo("Old reflection"))
        assertTrue(draft.canApplyTo("My reflection"))
        assertFalse(draft.canApplyTo("An external Obsidian edit"))
    }

    @Test fun oldUnknownBaselineCannotSilentlyReplaceExistingWriting() {
        val draft = RitualDraft(evening, "journal", "Recovered local draft", null)
        assertTrue(draft.canApplyTo(""))
        assertFalse(draft.canApplyTo("Writing from another device"))
        assertTrue(draft.canApplyTo("Writing from another device", explicitLegacySave = true))
    }

    @Test fun aLaterEditCanUseAcknowledgedEarlierWriteAsItsBaseline() {
        val first = RitualDraft(evening, "journal", "First local edit", "Original")
        val later = first.copy(text = "First local edit with more words", base = first.normalized)
        assertTrue(later.canApplyTo(first.normalized))
        assertFalse(later.canApplyTo("Someone changed the note after first write"))
    }

    @Test fun sameDayDraftsInDifferentVaultsHaveDifferentPersistentKeys() {
        val first = RitualDraft(evening, "journal", "A", "", "content://vault/A")
        val second = first.copy(sourceRoot = "content://vault/B")
        assertNotEquals(first.key, second.key)
        val restored = RitualDraft.parse(first.key, first.text, first.base, first.sourceRoot)!!
        assertEquals(first.key, restored.key)
        assertEquals(first.sourceRoot, restored.sourceRoot)
        assertEquals(first.window, restored.window)
        assertNotEquals(first.key, RitualDraft.key(evening, "journal"))
    }

    @Test fun outboxIgnoresUnrelatedOrMalformedPreferenceKeys() {
        listOf("base_draft_EVENING_2026-09-29_journal", "draft_EVENING_2026-02-30_journal",
            "draft_NIGHT_2026-09-29_journal", "draft_MORNING_2026-09-29_unknown").forEach {
            assertNull(RitualDraft.parse(it, "text", null))
        }
    }
}

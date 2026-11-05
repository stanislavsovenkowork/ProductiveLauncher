package dev.stanislav.focusdeck.ui.life

import dev.stanislav.focusdeck.data.life.LifeEntry
import dev.stanislav.focusdeck.data.life.LifeField
import dev.stanislav.focusdeck.data.life.LifeKind
import dev.stanislav.focusdeck.data.life.LifeRules
import dev.stanislav.focusdeck.data.life.LifeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LifeEditorValidationTest {
    @Test fun completedEditorMatchesRepositoryRequirementsAfterDeletingResult() {
        val results = mapOf(
            LifeKind.GOAL to LifeField.EVIDENCE,
            LifeKind.DECISION to LifeField.OUTCOME,
            LifeKind.REVIEW to LifeField.CHANGE,
        )
        for ((kind, field) in results) {
            val completed = LifeEntry.draft(kind, "IT").copy(
                status = LifeStatus.DONE, fields = mapOf(field to "Подтверждено"),
            )
            LifeRules.validate(completed)
            assertNull(missingLifeCompletionField(completed))
            for (erased in listOf("", "  ", "---", "…")) {
                val edited = completed.copy(fields = mapOf(field to erased))
                assertEquals(field, missingLifeCompletionField(edited))
                assertThrows(IllegalArgumentException::class.java) { LifeRules.validate(edited) }
            }
        }
    }

    @Test fun incompleteDraftsCanStillBeSavedWithoutClaimingCompletion() {
        for (kind in LifeKind.entries) {
            val draft = LifeEntry.draft(kind, "Я").copy(status = LifeStatus.DRAFT)
            LifeRules.validate(draft)
            assertNull(missingLifeCompletionField(draft))
        }
    }

    @Test fun completingProjectDoesNotInventAnEvidenceRequirement() {
        val project = LifeEntry.draft(LifeKind.PROJECT, "C#").copy(status = LifeStatus.DONE)
        LifeRules.validate(project)
        assertNull(missingLifeCompletionField(project))
    }
}

package dev.stanislav.focusdeck.data.life

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class LifeCompletionMergeTest {
    private val now = LocalDateTime.of(2026, 9, 29, 12, 0)
    private val kinds = listOf(LifeKind.GOAL, LifeKind.PROJECT)

    private fun parsed(entry: LifeEntry): LifeEntry =
        LifeNoteCodec.parse(entry.relativePath, LifeNoteCodec.create(entry))!!

    private fun base(kind: LifeKind): LifeEntry = parsed(
        LifeEntry.draft(kind, "Подготовить рассказы", now.toLocalDate()).copy(
            status = LifeStatus.ACTIVE,
            fields = mapOf(
                LifeField.WHY to "Подготовиться к интервью",
                LifeField.SUCCESS to "Три рассказа",
                LifeField.NEXT_ACTION to "Записать рассказ",
                LifeField.EVIDENCE to "Три рассказа записаны",
                LifeField.OBSTACLE to "Мало времени",
            ),
        ),
    )

    private fun changed(entry: LifeEntry, field: LifeField, value: String): LifeEntry =
        entry.copy(fields = entry.fields + (field to value))

    private fun complete(entry: LifeEntry): LifeEntry =
        LifeRules.prepare(entry, entry.copy(status = LifeStatus.DONE), now)

    private fun merged(base: LifeEntry, local: LifeEntry, remote: LifeEntry): LifeEntry =
        LifeNoteCodec.parse(base.path, LifeRules.merge(base, local, remote, now.toLocalDate()))!!

    @Test fun completionRejectsExternallyChangedCriterionWithoutMutatingDraft() {
        for (kind in kinds) {
            val base = base(kind)
            val local = complete(base)
            val remote = parsed(changed(base, LifeField.SUCCESS, "Десять рассказов"))
            val draftBefore = local.copy(fields = local.fields.toMap())
            val error = assertThrows(LifeConflictException::class.java) { merged(base, local, remote) }
            assertTrue(error.message.orEmpty().contains("проверь результат заново"))
            assertEquals(draftBefore, local)
            assertEquals("Три рассказа записаны", local.text(LifeField.EVIDENCE))
        }
    }

    @Test fun matchingLocalAndRemoteNewCriterionStillRequiresFreshCompletionReview() {
        for (kind in kinds) {
            val base = base(kind)
            val changed = changed(base, LifeField.SUCCESS, "Десять рассказов")
            assertThrows(LifeConflictException::class.java) {
                merged(base, complete(changed), parsed(changed))
            }
        }
    }

    @Test fun localCriterionAndExternalEvidenceCannotBeSilentlyPaired() {
        for (kind in kinds) for (status in listOf(LifeStatus.ACTIVE, LifeStatus.DONE)) {
            val base = base(kind)
            val local = LifeRules.prepare(base,
                changed(base, LifeField.SUCCESS, "Десять рассказов").copy(status = status), now)
            val remote = parsed(changed(base, LifeField.EVIDENCE, "Три рассказа проверены"))
            val error = assertThrows(LifeConflictException::class.java) { merged(base, local, remote) }
            assertTrue(error.message.orEmpty().contains("сверь его с новым критерием"))
        }
    }

    @Test fun localEvidenceAndExternalCriterionRequireReviewBeforeCompletionToo() {
        for (kind in kinds) {
            val base = base(kind)
            val local = changed(base, LifeField.EVIDENCE, "Три рассказа проверены")
            val remote = parsed(changed(base, LifeField.SUCCESS, "Десять рассказов"))
            assertThrows(LifeConflictException::class.java) { merged(base, local, remote) }
        }
    }

    @Test fun completionPreservesUnrelatedExternalTextDatesAndUnknownSections() {
        for (kind in kinds) {
            val base = base(kind)
            val remoteEntry = changed(base, LifeField.OBSTACLE, "Новая помеха")
                .copy(title = "Новое название", dueDate = now.toLocalDate().plusDays(5))
            val remote = LifeNoteCodec.parse(base.path,
                LifeNoteCodec.create(remoteEntry) + "\n## Мои материалы\n\nВнешняя заметка\n")!!
            val saved = merged(base, complete(base), remote)
            assertEquals(LifeStatus.DONE, saved.status)
            assertEquals(remoteEntry.title, saved.title)
            assertEquals(remoteEntry.dueDate, saved.dueDate)
            assertEquals("Новая помеха", saved.text(LifeField.OBSTACLE))
            assertTrue(saved.raw.contains("Внешняя заметка"))
        }
    }

    @Test fun localCriterionChangeAllowsUnrelatedExternalEditAndArchiving() {
        for (kind in kinds) {
            val base = base(kind)
            val local = changed(base, LifeField.SUCCESS, "Десять рассказов")
            val remote = parsed(changed(base, LifeField.OBSTACLE, "Новая помеха"))
            val saved = merged(base, local, remote)
            assertEquals("Десять рассказов", saved.text(LifeField.SUCCESS))
            assertEquals("Новая помеха", saved.text(LifeField.OBSTACLE))
            val archived = merged(base, base.copy(status = LifeStatus.ARCHIVED), parsed(local))
            assertEquals(LifeStatus.ARCHIVED, archived.status)
            assertEquals("Десять рассказов", archived.text(LifeField.SUCCESS))
        }
    }

    @Test fun completionWorksAfterReopeningFreshCriterionAndReviewingEvidence() {
        for (kind in kinds) {
            val fresh = parsed(changed(base(kind), LifeField.SUCCESS, "Десять рассказов"))
            val local = LifeRules.prepare(fresh,
                changed(fresh, LifeField.EVIDENCE, "Десять рассказов записаны").copy(status = LifeStatus.DONE), now)
            val saved = merged(fresh, local, fresh)
            assertEquals(LifeStatus.DONE, saved.status)
            assertEquals("Десять рассказов записаны", saved.text(LifeField.EVIDENCE))
        }
    }

    @Test fun editingAlreadyCompletedNoteDoesNotCountAsAnotherCompletion() {
        for (kind in kinds) {
            val base = parsed(complete(base(kind)))
            val local = base.copy(title = "Новое название")
            val remote = parsed(changed(base, LifeField.SUCCESS, "Уточнённый критерий"))
            val saved = merged(base, local, remote)
            assertEquals(LifeStatus.DONE, saved.status)
            assertEquals(local.title, saved.title)
            assertEquals("Уточнённый критерий", saved.text(LifeField.SUCCESS))
        }
    }
}

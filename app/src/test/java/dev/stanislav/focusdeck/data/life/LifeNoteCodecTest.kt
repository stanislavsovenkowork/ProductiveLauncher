package dev.stanislav.focusdeck.data.life

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LifeNoteCodecTest {
    private val day = LocalDate.of(2026, 9, 29)
    private fun draft() = LifeEntry.draft(LifeKind.GOAL, "Прочитать \"Архитектуру\": осень", day)
        .copy(reviewDate = day.plusDays(7), fields = mapOf(LifeField.WHY to "Хочу понимать решения\nи обсуждать их с командой", LifeField.SUCCESS to "Разобрать десять глав"))
    private fun parsed(entry: LifeEntry) = LifeNoteCodec.parse(entry.relativePath, LifeNoteCodec.create(entry))!!

    @Test fun russianQuotesMultilineAndDatesRoundTrip() {
        val original = draft().copy(areaId = "area-123456", dueDate = day.plusDays(60))
        val read = parsed(original)
        assertEquals(original.title, read.title)
        assertEquals(original.areaId, read.areaId)
        assertEquals(original.dueDate, read.dueDate)
        assertEquals(original.fields, read.fields.filterValues { it.isNotBlank() })
        assertTrue(read.problems.isEmpty())
    }

    @Test fun editsPreserveUnknownPropertiesOtherSectionsAndCodeFences() {
        val original = parsed(draft())
        val extra = original.raw.replace("status:", "custom_property: [one, two]\nstatus:") +
            "\n## Чужая секция\n\nТекст плагина\n```md\n## Почему это важно\nНе поле\n```\n"
        val fresh = LifeNoteCodec.parse(original.path, extra)!!
        val edited = original.copy(fields = original.fields + (LifeField.SUCCESS to "Закончить десять глав и конспект"))
        val merged = LifeNoteCodec.merge(original, edited, fresh, day.plusDays(1))
        assertTrue(merged.contains("custom_property: [one, two]"))
        assertTrue(merged.endsWith("## Чужая секция\n\nТекст плагина\n```md\n## Почему это важно\nНе поле\n```\n"))
        val read = LifeNoteCodec.parse(original.path, merged)!!
        assertEquals(original.text(LifeField.WHY), read.text(LifeField.WHY))
        assertEquals(edited.text(LifeField.SUCCESS), read.text(LifeField.SUCCESS))
        assertEquals(day.plusDays(1), read.updated)
    }

    @Test fun externalEditOfDifferentFieldIsMerged() {
        val base = parsed(draft())
        val external = base.copy(fields = base.fields + (LifeField.WHY to "Новая причина с Mac"))
        val fresh = LifeNoteCodec.parse(base.path, LifeNoteCodec.merge(base, external, base, day))!!
        val edited = base.copy(title = "Другое название")
        val read = LifeNoteCodec.parse(base.path, LifeNoteCodec.merge(base, edited, fresh, day))!!
        assertEquals("Другое название", read.title)
        assertEquals("Новая причина с Mac", read.text(LifeField.WHY))
    }

    @Test(expected = LifeConflictException::class) fun sameFieldExternalConflictDoesNotOverwrite() {
        val base = parsed(draft())
        val external = base.copy(fields = base.fields + (LifeField.WHY to "Из Mac"))
        val fresh = LifeNoteCodec.parse(base.path, LifeNoteCodec.merge(base, external, base, day))!!
        val local = base.copy(fields = base.fields + (LifeField.WHY to "Из телефона"))
        LifeNoteCodec.merge(base, local, fresh, day)
    }

    @Test fun noOpDoesNotTouchExternallyChangedText() {
        val base = parsed(draft())
        val fresh = LifeNoteCodec.parse(base.path, base.raw + "\nЛичная заметка\n")!!
        assertEquals(fresh.raw, LifeNoteCodec.merge(base, base, fresh, day))
    }

    @Test fun duplicateManagedSectionIsFlaggedInsteadOfDiscarded() {
        val base = parsed(draft())
        val bad = LifeNoteCodec.parse(base.path, base.raw + "\n## Почему это важно\nДругая причина\n")!!
        assertTrue(bad.problems.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { LifeNoteCodec.merge(base, base.copy(title="Новое"), bad, day) }
    }

    @Test fun unsupportedYamlAndInvalidDatesAreVisibleProblems() {
        val base = parsed(draft())
        val bad = LifeNoteCodec.parse(base.path, base.raw.replace("review: \"2026-10-06\"", "review: tomorrow").replace("title: ", "title: | # "))!!
        assertTrue(bad.problems.size >= 2)
    }

    @Test fun decisionsNeedRecordedExpectationAndReviewDateBeforeCommitment() {
        val decision = LifeEntry.draft(LifeKind.DECISION, "Выбрать курс", day)
        assertTrue(decision.activationProblems().isNotEmpty())
        assertTrue(decision.copy(fields=mapOf(LifeField.OPTIONS to "Самостоятельно или курс", LifeField.CHOICE to "Сначала самостоятельно", LifeField.PREDICTION to "За неделю решу три задания")).activationProblems().isEmpty())
    }
    @Test fun quotedYamlCommentsAndSingleQuoteEscapesAreReadable() {
        val original = parsed(draft())
        val raw = original.raw.replace("status: \"draft\"", "status: \"draft\" # my comment")
            .replace("review: \"2026-10-06\"", "review: '2026-10-06' # comment")
        val read = LifeNoteCodec.parse(original.path, raw)!!
        assertTrue(read.problems.isEmpty())
        assertEquals(day.plusDays(7), read.reviewDate)
    }

    @Test fun yamlListsInManagedScalarAreNotSilentlyLost() {
        val original = parsed(draft())
        val raw = original.raw.replace("area_id: \"\"", "area_id:\n  - first\n  - second")
        val read = LifeNoteCodec.parse(original.path, raw)!!
        assertTrue(read.problems.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { LifeNoteCodec.merge(original, original.copy(title="Новое название"), read, day) }
    }

    @Test fun windowsLineEndingsSurviveFieldEdit() {
        val original = parsed(draft())
        val base = LifeNoteCodec.parse(original.path, original.raw.replace("\n", "\r\n"))!!
        val merged = LifeNoteCodec.merge(base, base.copy(title="Другая цель"), base, day)
        assertTrue(merged.contains("\r\n"))
        assertFalse(merged.replace("\r\n", "").contains("\n"))
    }

}

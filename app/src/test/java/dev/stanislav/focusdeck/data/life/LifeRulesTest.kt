package dev.stanislav.focusdeck.data.life

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class LifeRulesTest {
    @Test fun shortMeaningfulNamesAreValidButPunctuationIsNot() {
        for (title in listOf("IT", "C#", "Я")) {
            val entry = LifeEntry.draft(LifeKind.AREA, title).copy(status = LifeStatus.ACTIVE)
            LifeRules.validate(entry)
            assertTrue(entry.activationProblems().isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LifeRules.validate(LifeEntry.draft(LifeKind.AREA, "..."))
        }
    }
    @Test fun titleAnchorsDoNotTurnObsidianFileLinksIntoHeadingLinks() {
        val entry = LifeEntry.draft(LifeKind.PROJECT, "C# и блоки ^code")
        assertFalse(entry.fileName.contains('#'))
        assertFalse(entry.fileName.contains('^'))
        assertEquals("C# и блоки ^code", entry.title)
        assertTrue(entry.wikiLink.endsWith("|C# и блоки ^code]]"))
    }
    private val now = LocalDateTime.of(2026,9,29,10,30)
    private fun decision() = LifeEntry.draft(LifeKind.DECISION,"Как учиться",now.toLocalDate()).copy(fields = mapOf(
        LifeField.OPTIONS to "Книга или курс", LifeField.CHOICE to "Книга",
        LifeField.PREDICTION to "Три занятия", LifeField.CRITERIA to "Самостоятельный темп"))
    private fun accept(): LifeEntry { val draft = decision(); return LifeRules.prepare(draft, draft.copy(status=LifeStatus.ACTIVE), now) }
    private fun parse(entry: LifeEntry) = LifeNoteCodec.parse(entry.relativePath,LifeNoteCodec.create(entry))!!

    @Test fun simultaneousResultAndChangedExpectationProduceDistinctTruthfulEvents() {
        val draft=decision()
        val accepted=LifeRules.prepare(draft,draft.copy(status=LifeStatus.ACTIVE),now)
        val next=LifeRules.prepare(accepted,accepted.copy(fields=accepted.fields + mapOf(
            LifeField.PREDICTION to "Одно занятие", LifeField.OUTCOME to "Два занятия",
            LifeField.CHOICE to "Курс")),now.plusDays(7))
        val history=next.text(LifeField.HISTORY)
        assertEquals(1,Regex("· Решение принято").findAll(history).count())
        assertTrue(history.contains("· Проверка результата"))
        assertTrue(history.contains("· Решение пересмотрено"))
        val observation=history.substringAfter("· Проверка результата").substringBefore("· Решение пересмотрено")
        assertTrue(observation.contains("Три занятия"))
        assertTrue(observation.contains("Два занятия"))
        assertFalse(observation.contains("Одно занятие"))
        assertTrue(history.substringAfter("· Решение пересмотрено").contains("Одно занятие"))
        assertEquals(next,LifeRules.prepare(next,next,now.plusDays(8)))
    }

    @Test fun retrospectiveDecisionDoesNotPretendToBePrediction() {
        val draft=decision()
        val saved=LifeRules.prepare(draft,draft.copy(status=LifeStatus.ACTIVE,
            fields=draft.fields+(LifeField.OUTCOME to "Уже два занятия")),now)
        assertTrue(saved.text(LifeField.HISTORY).contains("Записано после результата"))
        assertTrue(saved.text(LifeField.HISTORY).contains("Проверка результата"))
        assertFalse(saved.text(LifeField.HISTORY).contains("· Решение принято"))
    }

    @Test fun reminderPostponementIsNotAReview() {
        val saved=accept()
        val postponed=LifeRules.prepare(saved,saved.copy(reviewDate=now.toLocalDate().plusDays(30)),now)
        assertNull(postponed.reviewedOn)
        assertTrue(postponed.text(LifeField.HISTORY).contains("Перенос даты не означает состоявшийся обзор"))
    }

    @Test fun resumeIsNotAnotherAcceptance() {
        val saved=accept()
        val paused=LifeRules.prepare(saved,saved.copy(status=LifeStatus.PAUSED),now)
        val resumed=LifeRules.prepare(paused,paused.copy(status=LifeStatus.ACTIVE),now.plusDays(1))
        assertEquals(saved.text(LifeField.HISTORY),resumed.text(LifeField.HISTORY))
    }

    @Test fun externalPredictionAndLocalOutcomeCannotBeSilentlyPaired() {
        val base=parse(accept())
        val external=parse(base.copy(fields=base.fields+(LifeField.PREDICTION to "Иной прогноз")))
        val local=LifeRules.prepare(base,base.copy(fields=base.fields+(LifeField.OUTCOME to "Получилось иначе")),now)
        assertThrows(LifeConflictException::class.java) { LifeRules.merge(base,local,external,now.toLocalDate()) }
    }

    @Test fun validationRunsAfterIndependentFieldMerge() {
        val base=parse(accept())
        val external=parse(base.copy(fields=base.fields+(LifeField.PREDICTION to "")))
        assertThrows(IllegalStateException::class.java) { LifeRules.merge(base,base.copy(title="Название новое"),external,now.toLocalDate()) }
    }

    @Test fun exactDraftSurvivesHeadingsAndLargeUnicodeText() {
        val entry=decision().copy(title="Черновик\nс переносом",fields=mapOf(LifeField.PREDICTION to
            "До результата\n## Условия\nТочное содержание\n## Почему это важно\n${"Продолжение ".repeat(10000)}"))
        val decoded=LifeDraftCodec.decode(LifeDraftCodec.encode(entry))!!
        assertEquals(entry.title,decoded.title)
        assertEquals(entry.fields,decoded.fields)
        assertThrows(IllegalArgumentException::class.java) { LifeRules.validate(entry) }
    }

    @Test fun ordinaryDraftsDoNotCreateAutomaticReviewDebt() {
        for (kind in LifeKind.entries) {
            val draft=LifeEntry.draft(kind,"Идея",now.toLocalDate().minusYears(1))
            assertFalse(draft.needsReview)
            if(kind!=LifeKind.DECISION) assertNull(draft.reviewDate)
        }
    }
    @Test fun interruptedCreateCanRecoverOnlyMatchingFileInSameVault() {
        val draft=decision().copy(sourceRoot="vault-one")
        val saved=parse(draft).copy(sourceRoot="vault-one")
        assertTrue(LifeRules.sameDraftContent(draft,saved))
        assertFalse(LifeRules.sameDraftContent(draft,saved.copy(title="Другая правка")))
        assertFalse(LifeRules.sameDraftContent(draft,saved.copy(sourceRoot="vault-two")))
        assertFalse(LifeRules.sameDraftContent(draft,saved.copy(fields=saved.fields+(LifeField.PREDICTION to "Иной прогноз"))))
    }

    @Test fun unclosedFencesAreRejectedBeforeTheyCanSwallowFollowingFields() {
        for (fence in listOf("```text", "~~~text")) {
            val draft=decision().copy(fields=decision().fields+(LifeField.PREDICTION to "$fence\nUnclosed text"))
            assertThrows(IllegalArgumentException::class.java) { LifeRules.validate(draft) }
        }
        val valid=decision().copy(fields=decision().fields+(LifeField.PREDICTION to "```text\n## Inside code\n```"))
        LifeRules.validate(valid)
        assertEquals(valid.text(LifeField.PREDICTION),parse(valid).text(LifeField.PREDICTION))
    }

    @Test fun completedStatesCannotLoseTheirEvidenceOnLaterEdits() {
        for ((kind,field) in listOf(LifeKind.GOAL to LifeField.EVIDENCE, LifeKind.DECISION to LifeField.OUTCOME, LifeKind.REVIEW to LifeField.CHANGE)) {
            val done=LifeEntry.draft(kind,"Сохранённый результат").copy(status=LifeStatus.DONE,fields=mapOf(field to "Подтверждённый факт"))
            LifeRules.validate(done)
            assertThrows(IllegalArgumentException::class.java) { LifeRules.validate(done.copy(fields=emptyMap())) }
        }
    }

    @Test fun newDecisionCycleNeverInheritsThePreviousObservation() {
        val accepted=accept()
        val reviewed=LifeRules.prepare(accepted,accepted.copy(fields=accepted.fields+(LifeField.OUTCOME to "Два занятия")),now.plusDays(7),recordReview=true)
        val revised=LifeRules.prepare(reviewed,reviewed.copy(fields=reviewed.fields+(LifeField.PREDICTION to "Одно занятие")),now.plusDays(8))
        assertTrue(revised.text(LifeField.OUTCOME).isEmpty())
        assertNull(revised.reviewedOn)
        assertTrue(revised.text(LifeField.HISTORY).contains("Два занятия"))
        assertThrows(IllegalStateException::class.java) { LifeRules.prepare(revised,revised.copy(status=LifeStatus.DONE),now.plusDays(14)) }
        val sameFact=LifeRules.prepare(revised,revised.copy(fields=revised.fields+(LifeField.OUTCOME to "Два занятия")),now.plusDays(14),recordReview=true)
        assertEquals(2,Regex("· Проверка результата").findAll(sameFact.text(LifeField.HISTORY)).count())
        assertEquals(now.plusDays(14).toLocalDate(),sameFact.reviewedOn)
    }

    @Test fun oversizedDraftIsRejectedBeforePersistingUndecodableData() {
        val draft=decision().copy(fields=mapOf(LifeField.WHY to "a".repeat(9*1024*1024)))
        assertThrows(IllegalArgumentException::class.java) { LifeDraftCodec.encode(draft) }
        assertNull(LifeDraftCodec.decode("fd-draft-1:broken"))
    }

    @Test fun explicitLifeReviewKeepsObservationsWithoutInventingCompletion() {
        val project = LifeEntry.draft(LifeKind.PROJECT,"Рабочий проект",now.toLocalDate()).copy(
            status=LifeStatus.ACTIVE, reviewDate=now.toLocalDate(), fields=mapOf(
                LifeField.SUCCESS to "Готовый прототип", LifeField.NEXT_ACTION to "Собрать экран"))
        val reviewed=LifeRules.prepare(project,project.copy(reviewDate=null,fields=project.fields+
            (LifeField.REVIEW_NOTE to "Приоритет прежний, уменьшу объём первого шага")),now,recordLifeReview=true)
        assertEquals(LifeStatus.ACTIVE,reviewed.status)
        assertEquals(now.toLocalDate(),reviewed.reviewedOn)
        assertTrue(reviewed.text(LifeField.REVIEW_HISTORY).contains("Приоритет прежний"))
        assertEquals(reviewed.text(LifeField.REVIEW_HISTORY),parse(reviewed).text(LifeField.REVIEW_HISTORY))
        val postponed=LifeRules.prepare(reviewed,reviewed.copy(reviewDate=now.toLocalDate().plusDays(7)),now.plusDays(1))
        assertEquals(reviewed.reviewedOn,postponed.reviewedOn)
        assertEquals(reviewed.text(LifeField.REVIEW_HISTORY),postponed.text(LifeField.REVIEW_HISTORY))
    }

    @Test fun lifeReviewRequiresReflectionAndExplicitFutureOrNoReminder() {
        val area=LifeEntry.draft(LifeKind.AREA,"Здоровье").copy(status=LifeStatus.ACTIVE)
        assertThrows(IllegalArgumentException::class.java) { LifeRules.prepare(area,area,now,recordLifeReview=true) }
        val noted=area.copy(fields=mapOf(LifeField.REVIEW_NOTE to "Продолжаю прежний режим"))
        assertThrows(IllegalArgumentException::class.java) { LifeRules.prepare(area,noted.copy(reviewDate=now.toLocalDate()),now,recordLifeReview=true) }
        assertEquals(LifeStatus.PAUSED,LifeRules.prepare(area,noted.copy(status=LifeStatus.PAUSED),now,recordLifeReview=true).status)
        assertThrows(IllegalArgumentException::class.java) { LifeRules.prepare(area,noted.copy(id="changed"),now) }
    }

    @Test fun concurrentProgressChangeCannotBeSilentlyAttachedToOldReview() {
        val base=parse(LifeEntry.draft(LifeKind.AREA,"Здоровье").copy(status=LifeStatus.ACTIVE,
            fields=mapOf(LifeField.EVIDENCE to "Два занятия")))
        val local=LifeRules.prepare(base,base.copy(fields=base.fields+(LifeField.REVIEW_NOTE to "Сохраню этот темп")),now,recordLifeReview=true)
        val fresh=parse(base.copy(fields=base.fields+(LifeField.EVIDENCE to "Занятия отменены")))
        assertThrows(LifeConflictException::class.java) { LifeRules.merge(base,local,fresh,now.toLocalDate()) }
    }

    @Test fun punctuationIsNotAResultOrReason() {
        val goal=LifeEntry.draft(LifeKind.GOAL,"Освоить язык").copy(status=LifeStatus.ACTIVE,
            fields=mapOf(LifeField.WHY to "...",LifeField.SUCCESS to "!!!"))
        assertEquals(2,goal.activationProblems().size)
        assertThrows(IllegalArgumentException::class.java) { LifeRules.validate(goal.copy(status=LifeStatus.DONE,fields=mapOf(LifeField.EVIDENCE to "..."))) }
    }

    @Test fun externalUnclosedFenceCannotSwallowAppendedHistory() {
        for (fence in listOf("```", "~~~")) {
            val entry=accept().copy(fields=accept().fields-LifeField.HISTORY)
            val raw=LifeNoteCodec.create(entry)+"\n## Personal notes\n\n${fence}text\nUnclosed code\n"
            val base=LifeNoteCodec.parse(entry.relativePath,raw)!!
            val proposal=LifeRules.prepare(base,base.copy(fields=base.fields+(LifeField.OUTCOME to "Два занятия")),now.plusDays(7),recordReview=true)
            assertThrows(IllegalStateException::class.java) { LifeRules.merge(base,proposal,base,now.toLocalDate()) }
            val closed=LifeNoteCodec.parse(entry.relativePath,raw+"$fence\n")!!
            val merged=LifeRules.merge(base,proposal,closed,now.toLocalDate())
            val parsed=LifeNoteCodec.parse(entry.relativePath,merged)!!
            assertTrue(parsed.text(LifeField.HISTORY).contains("Проверка результата"))
            assertTrue(merged.contains("Unclosed code"))
        }
    }

    @Test fun explicitDecisionReviewRequiresAnObservationEvenWhenContinuing() {
        val accepted=accept()
        for (value in listOf("", " ", "---")) {
            assertThrows(IllegalArgumentException::class.java) {
                LifeRules.prepare(accepted,accepted.copy(fields=accepted.fields+(LifeField.OUTCOME to value)),now.plusDays(7),recordReview=true)
            }
        }
        val reviewed=LifeRules.prepare(accepted,accepted.copy(fields=accepted.fields+(LifeField.OUTCOME to "Результата пока нет")),now.plusDays(7),recordReview=true)
        assertEquals(now.toLocalDate().plusDays(7),reviewed.reviewedOn)
    }

}

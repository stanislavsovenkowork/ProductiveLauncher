package dev.stanislav.focusdeck.data.life

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeEvidenceTest {
    private val today = LocalDate.of(2026, 9, 29)

    @Test fun tickingStampsDateReadableByObsidianTasks() {
        val ticked = Milestones.toggle("- [ ] Дизайн", 0, true, today)
        assertEquals("- [x] Дизайн ✅ 2026-09-29", ticked)
        val parsed = Milestones.parse(ticked).single()
        assertEquals("Дизайн", parsed.title)
        assertEquals(today, parsed.doneOn)
        assertEquals("- [ ] Дизайн", Milestones.toggle(ticked, 0, false, today))
    }

    @Test fun framingSwitchesFromDoneToRemainingAtHalfway() {
        assertEquals("пройдено 1 из 5", LifeProgress(1, 5, 0.2f, LifeProgress.ProgressUnit.MILESTONES).framing())
        assertEquals("осталось 2 этапа", LifeProgress(3, 5, 0.6f, LifeProgress.ProgressUnit.MILESTONES).framing())
        assertEquals("осталось 1 этап", LifeProgress(4, 5, 0.8f, LifeProgress.ProgressUnit.MILESTONES).framing())
        assertNull(LifeProgress(1, 1, 1f, LifeProgress.ProgressUnit.FINISHED).framing())
    }

    @Test fun metricTracksUpAndDownGoals() {
        val run = Metrics.parse(Metrics.record(Metrics.record(Metrics.create(21.0, "км", 5.0), today.minusDays(14), 9.0), today, 13.0))!!
        assertEquals(13.0, run.current!!, 0.0)
        assertEquals(0.5f, run.fraction!!, 0.001f)
        val weight = Metrics.parse("Цель: 80 кг\n- 2026-09-01: 90\n- 2026-09-29: 85")!!
        assertEquals(0.5f, weight.fraction!!, 0.001f)
        val replaced = Metrics.parse(Metrics.record("Цель: 10\n- 2026-09-29: 3", today, 4.5))!!
        assertEquals(listOf(4.5), replaced.points.map { it.second })
        assertEquals(1.5, Metrics.number("1,5")!!, 0.0)
    }

    @Test fun paceNeedsEvidenceAndFlagsLateFinish() {
        val project = LifeEntry.draft(LifeKind.PROJECT, "П", today.minusDays(28)).copy(status = LifeStatus.ACTIVE, dueDate = today.plusDays(7),
            fields = mapOf(LifeField.MILESTONES to "- [x] 1 ✅ 2026-09-10\n- [x] 2 ✅ 2026-09-24\n- [ ] 3\n- [ ] 4"))
        val pace = LifePace.milestones(project, today)!!
        assertEquals(0.5, pace.perWeek, 0.01)
        assertEquals(today.plusDays(28), pace.eta)
        assertTrue(pace.late)
        val undated = project.copy(fields = mapOf(LifeField.MILESTONES to "- [x] 1\n- [x] 2\n- [ ] 3"))
        assertNull(LifePace.milestones(undated, today))
        val goal = LifeEntry.draft(LifeKind.GOAL, "Ц").copy(status = LifeStatus.ACTIVE,
            fields = mapOf(LifeField.METRIC to "Цель: 21\n- 2026-09-15: 7\n- 2026-09-29: 14"))
        val metricPace = LifePace.metric(goal, today)!!
        assertEquals(today.plusDays(14), metricPace.eta)
        assertFalse(metricPace.late)
    }

    @Test fun goalProgressIncludesMetric() {
        val goal = LifeEntry.draft(LifeKind.GOAL, "Ц").copy(status = LifeStatus.ACTIVE,
            fields = mapOf(LifeField.METRIC to "Цель: 20\n- 2026-09-29: 5"))
        val progress = LifeProgressRules.of(goal, listOf(goal))!!
        assertEquals(LifeProgress.ProgressUnit.METRIC, progress.unit)
        assertEquals(0.25f, progress.fraction, 0.001f)
        val withMilestones = goal.copy(fields = goal.fields + (LifeField.MILESTONES to "- [x] a\n- [x] b"))
        assertEquals(0.625f, LifeProgressRules.of(withMilestones, listOf(withMilestones))!!.fraction, 0.001f)
    }

    @Test fun motiveAndCalibrationLinesRoundTrip() {
        val why = GoalMotive.with("Хочу быть сильным", GoalMotive.GUILT)
        assertEquals(GoalMotive.GUILT, GoalMotive.of(why))
        assertEquals("Хочу быть сильным", GoalMotive.strip(why))
        assertEquals("Хочу быть сильным", GoalMotive.with(why, null))
        val prediction = Calibration.withConfidence("Доход выше", 70)
        assertEquals(70, Calibration.confidence(prediction))
        assertEquals("Доход выше", Calibration.stripConfidence(prediction))
        fun decision(p: Int, v: Calibration.Verdict) = LifeEntry.draft(LifeKind.DECISION, "Р").copy(fields = mapOf(
            LifeField.PREDICTION to Calibration.withConfidence("x", p), LifeField.OUTCOME to Calibration.withVerdict("факт", v)))
        val summary = Calibration.summary(listOf(decision(80, Calibration.Verdict.YES), decision(60, Calibration.Verdict.NO)))!!
        assertEquals(2, summary.count)
        assertEquals(0.7, summary.meanConfidence, 0.001)
        assertEquals(0.5, summary.hitRate, 0.001)
        assertEquals((0.04 + 0.36) / 2, summary.brier, 0.001)
    }

    @Test fun metricSurvivesNoteRoundTrip() {
        val goal = LifeEntry.draft(LifeKind.GOAL, "Ц").copy(fields = mapOf(LifeField.METRIC to "Цель: 21 км\nСтарт: 5\n- 2026-09-29: 12"))
        val parsed = LifeNoteCodec.parse(goal.relativePath, LifeNoteCodec.create(goal))!!
        assertEquals(goal.text(LifeField.METRIC), parsed.text(LifeField.METRIC))
    }
}

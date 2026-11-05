import Foundation
@main enum DailyReportTests {
    static func main() throws {
        var data: [String: Any] = ["schema": 1, "day": "2026-10-28", "categories": ["work": ["focusHoursByEvidence": ["timer": 1.25]], "study": ["focusHoursByEvidence": ["timer": NSNull()]]], "dailyCheck": ["state": "observed", "sourcesNeedingAttention": 1]]
        let known = try DailyReportSnapshot.parse(JSONSerialization.data(withJSONObject: data))
        precondition(known.workTimerHours == 1.25 && known.studyTimerHours == nil && known.quality.contains("частичная"))
        precondition(DailyReportSnapshot.hoursText(nil) == "Нет данных" && DailyReportSnapshot.number(true) == nil)
        data["dailyCheck"] = ["state": "clock_skew"]
        let skew = try DailyReportSnapshot.parse(JSONSerialization.data(withJSONObject: data), fixture: true)
        precondition(skew.isFixture && skew.quality.contains("не согласованы"))
        data["dailyCheck"] = ["state": "no_data"]
        let missing = try DailyReportSnapshot.parse(JSONSerialization.data(withJSONObject: data))
        precondition(missing.workTimerHours == nil)
        let revision = UUID()
        let weekly: [String: Any] = ["schemaVersion": 1, "nextReviewDate": "2026-11-16", "protectionReview": ["state": "observed_incomplete", "counts": ["relief": 1, "return": 2, "explicitExit": 0], "sourceStates": ["observed", "unknown"], "committedPolicyRevisionIds": [revision.uuidString]]]
        let recovery: [String: Any] = ["schemaVersion": 1, "state": "source_unavailable", "context": ["taskRef": "task_123", "sessionState": "paused"]]
        let review = ReturnReviewSnapshot.parse(recovery: try JSONSerialization.data(withJSONObject: recovery), weekly: try JSONSerialization.data(withJSONObject: weekly))
        precondition(review.recoveryState == "source_unavailable" && review.committedRevisions.contains(revision))
        precondition(review.weeklyText.contains("1 из 2") && review.weeklyText.contains("2026-11-16"))
        precondition(review.weeklyText.contains("Источник 1: события получены") && review.weeklyText.contains("Источник 2: нет подтверждённых данных"))
        precondition(review.weeklyText.contains("Время обновления обзора неизвестно"))
        var unavailableWeekly = weekly
        unavailableWeekly["protectionReview"] = ["state": "unknown", "counts": NSNull(), "sourceStates": ["unknown", "unknown"]]
        let unavailableSources = ReturnReviewSnapshot.parse(recovery: nil, weekly: try JSONSerialization.data(withJSONObject: unavailableWeekly))
        precondition(unavailableSources.weeklyText.contains("Источник 1: нет подтверждённых данных") && unavailableSources.weeklyText.contains("Источник 2: нет подтверждённых данных"))
        precondition(!unavailableSources.weeklyText.contains("послаблений 0"))
        var staleWeekly = weekly; staleWeekly["evaluatedAtUTC"] = "2026-11-01T00:00:00Z"
        let stale = ReturnReviewSnapshot.parse(recovery: nil, weekly: try JSONSerialization.data(withJSONObject: staleWeekly), now: ISO8601DateFormatter().date(from: "2026-11-10T00:00:00Z")!)
        precondition(stale.weeklyText.contains("Обзор устарел"))
        precondition(review.nextStep(taskRef: "task_123", taskTitle: "Тест", note: "").contains("Сохранённый контекст"))
        precondition(review.nextStep(taskRef: "other", taskTitle: "Не та", note: "").contains("не сопоставлена"))
        precondition(review.nextStep(taskRef: "", taskTitle: "", note: "Шаг").contains("указан"))
        var oldRecovery = recovery; oldRecovery["evaluatedAtUTC"] = "2026-11-01T00:00:00.123456Z"
        let old = ReturnReviewSnapshot.parse(recovery: try JSONSerialization.data(withJSONObject: oldRecovery), weekly: nil, now: ISO8601DateFormatter().date(from: "2026-11-03T00:00:00Z")!)
        precondition(old.recoveryFreshness == "stale")
        precondition(review.recoveryFreshness == "unknown")
        let absent = ReturnReviewSnapshot.parse(recovery: nil, weekly: nil)
        precondition(absent.committedRevisions.isEmpty && absent.weeklyText.contains("неизвестны"))
        print("Daily summary known/missing/partial/clock-skew/fixture/boolean-rejection checks passed; no private report read.")
    }
}

import Foundation
import CoreFoundation

struct DailyReportSnapshot: Equatable {
    let day: String?
    let workTimerHours: Double?
    let studyTimerHours: Double?
    let workContextHours: Double?
    let studyContextHours: Double?
    let quality: String
    let isFixture: Bool
    static let unavailable = DailyReportSnapshot(day: nil, workTimerHours: nil, studyTimerHours: nil,
        workContextHours: nil, studyContextHours: nil, quality: "Сводка ещё недоступна. Отсутствие данных не означает нулевую работу.", isFixture: false)
    static func number(_ value: Any?) -> Double? {
        guard let value = value as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID(), value.doubleValue.isFinite,
              value.doubleValue >= 0 else { return nil }
        return value.doubleValue
    }
    static func parse(_ data: Data, fixture: Bool = false) throws -> DailyReportSnapshot {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any], number(root["schema"]) == 1,
              let day = root["day"] as? String, day.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil,
              let categories = root["categories"] as? [String: [String: Any]] else { throw Failure.invalidReport }
        let check = root["dailyCheck"] as? [String: Any]
        let state = check?["state"] as? String
        func hours(_ category: String) -> Double? {
            guard state != "no_data", let evidence = categories[category]?["focusHoursByEvidence"] as? [String: Any] else { return nil }
            return number(evidence["timer"])
        }
        let context = root["contextObservation"] as? [String: Any]
        let seconds = context?["secondsByCategory"] as? [String: Any]
        let attention = number(check?["sourcesNeedingAttention"])
        let quality: String
        switch state {
        case "clock_skew": quality = "Часы источников не согласованы; оценка неполна. Будущие записи исключены."
        case "stale": quality = "Часть источников устарела; это не подтверждает отсутствие работы."
        case "no_data": quality = "Наблюдений пока нет. Время неизвестно."
        case "observed": quality = (attention ?? 0) > 0 ? "Оценка частичная: есть источники без свежих сигналов." : "Таймер — оценка времени, а не измеренное внимание или полезный результат."
        default: quality = "Полнота источников пока неизвестна."
        }
        return DailyReportSnapshot(day: day, workTimerHours: hours("work"), studyTimerHours: hours("study"),
            workContextHours: number(seconds?["work"]).map { $0 / 3600 },
            studyContextHours: number(seconds?["study"]).map { $0 / 3600 }, quality: quality, isFixture: fixture)
    }
    static func hoursText(_ hours: Double?) -> String {
        guard let hours else { return "Нет данных" }
        if hours > 0 && hours < 0.005 { return "< 0,01 ч" }
        return String(format: "%.2f ч", locale: Locale(identifier: "ru_RU"), hours)
    }
    enum Failure: Error { case invalidReport }
}

// Review and recovery are read-only local observations, not a guard status.
struct ReturnReviewSnapshot: Equatable {
    let recoveryState: String
    let recoveryTaskRef: String?
    let recoverySessionState: String?
    let recoveryFreshness: String
    let weeklyText: String
    let committedRevisions: Set<UUID>
    static let unavailable = ReturnReviewSnapshot(recoveryState: "unknown", recoveryTaskRef: nil,
        recoverySessionState: nil, recoveryFreshness: "unknown", weeklyText: "Недельный обзор недоступен; источники и история неизвестны.", committedRevisions: [])
    static func parse(recovery: Data?, weekly: Data?, now: Date = Date()) -> ReturnReviewSnapshot {
        func parseInstant(_ value: Any?) -> Date? {
            guard let value = value as? String else { return nil }
            let fractional = ISO8601DateFormatter(); fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
        }
        var state = "unknown", task: String?, sessionState: String?, freshness = "unknown"
        if let recovery, let root = (try? JSONSerialization.jsonObject(with: recovery)) as? [String: Any],
           root["schemaVersion"] as? Int == 1, let value = root["state"] as? String,
           ["available", "source_unavailable", "unknown", "disabled"].contains(value) {
            state = value
            if let stamp = parseInstant(root["evaluatedAtUTC"]), stamp <= now {
                freshness = now.timeIntervalSince(stamp) > 36 * 3600 ? "stale" : "recent"
            }
            if let context = root["context"] as? [String: Any] {
                if let ref = context["taskRef"] as? String, ref.range(of: #"^[A-Za-z0-9_-]{1,128}$"#, options: .regularExpression) != nil { task = ref }
                if let value = context["sessionState"] as? String,
                   ["started", "paused", "resumed", "completed", "stopped", "missed", "disabled", "uninstalled", "unknown"].contains(value) { sessionState = value }
            }
        }
        var text = unavailable.weeklyText, revisions = Set<UUID>()
        if let weekly, let root = (try? JSONSerialization.jsonObject(with: weekly)) as? [String: Any],
           root["schemaVersion"] as? Int == 1, let review = root["protectionReview"] as? [String: Any],
           let status = review["state"] as? String {
            let sourceStates = (review["sourceStates"] as? [String]).flatMap { states in
                !states.isEmpty && states.allSatisfy({ ["observed", "partial", "unknown", "empty"].contains($0) }) ? states : nil
            }
            let next = (root["nextReviewDate"] as? String).flatMap { $0.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil ? $0 : nil }
            if status == "observed_incomplete", let counts = review["counts"] as? [String: Any],
               let relief = DailyReportSnapshot.number(counts["relief"]), let returns = DailyReportSnapshot.number(counts["return"]),
               let exits = DailyReportSnapshot.number(counts["explicitExit"]),
               relief.rounded() == relief, returns.rounded() == returns, exits.rounded() == exits,
               relief <= 1_000_000, returns <= 1_000_000, exits <= 1_000_000,
               let sources = sourceStates {
                let unknown = sources.filter { $0 != "observed" }.count
                text = "За неделю: послаблений \(Int(relief)), возвратов к политике \(Int(returns)), выходов \(Int(exits)). Источников с пробелами: \(unknown) из \(sources.count); полнота неизвестна."
                if let ids = review["committedPolicyRevisionIds"] as? [String] { revisions = Set(ids.compactMap(UUID.init(uuidString:))) }
            } else if status == "not_enabled" { text = "Обзор ограничений не включён; данных о реакции нет." }
            else { text = "События ограничений недоступны; полнота источников неизвестна." }
            if let sources = sourceStates {
                let descriptions = sources.enumerated().map { index, status in
                    let label = status == "observed" ? "события получены" : (status == "partial" ? "частично" : "нет подтверждённых данных")
                    return "Источник \(index + 1): \(label)"
                }
                text += " " + descriptions.joined(separator: "; ") + ". Имена источников отчёт не передаёт; их текущая связь и полнота неизвестны."
            }
            let stamp = parseInstant(root["evaluatedAtUTC"])
            if let stamp, stamp <= now { if now.timeIntervalSince(stamp) > 7 * 86400 { text += " Обзор устарел: обновлений более 7 дней." } }
            else { text += " Время обновления обзора неизвестно." }
            if let next { text += " Следующий обзор: \(next)." }
        }
        return ReturnReviewSnapshot(recoveryState: state, recoveryTaskRef: task, recoverySessionState: sessionState, recoveryFreshness: freshness, weeklyText: text, committedRevisions: revisions)
    }
    func nextStep(taskRef: String, taskTitle: String, note: String) -> String {
        if !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "Следующий шаг указан ниже." }
        if !taskRef.isEmpty, taskRef == recoveryTaskRef, !taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Сохранённый контекст совпадает с задачей «\(taskTitle)». Запишите один следующий шаг."
        }
        if recoveryTaskRef != nil { return "Найден сохранённый контекст Android, но задача не сопоставлена. Выберите существующую задачу и запишите следующий шаг." }
        return "Выберите задачу и запишите один следующий шаг, чтобы было проще вернуться."
    }
}

import Foundation

@main struct WindowPolicyTests {
    static func main() throws {
        let formatter = ISO8601DateFormatter()
        let start = formatter.date(from: "2026-10-29T12:00:00Z")!
        let end = start.addingTimeInterval(3600)
        var policy = try WindowPolicy.create(start: start, end: end, targets: ["synthetic.fixture"], budget: 300, maxGrant: 300, now: start.addingTimeInterval(-1))
        precondition(policy.reconcile(now: start) == "window_started")
        precondition(policy.relief(now: start.addingTimeInterval(5)))
        precondition(policy.reliefRemainingSeconds == 0 && policy.endsAt == end)
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = WindowPolicyStore(directory: folder)
        try store.save(policy, event: "relief_started", now: start)
        var loaded = try store.load()!
        precondition(loaded == policy)
        precondition(loaded.returnToWindow(now: start.addingTimeInterval(10)))
        precondition(!loaded.relief(now: start.addingTimeInterval(11)))
        precondition(loaded.requestExit(now: start.addingTimeInterval(12), uptime: 100, boot: "boot"))
        try store.save(loaded, event: "exit_requested")
        var pending = try store.load()!
        precondition(!pending.exitWindow(now: start.addingTimeInterval(20), uptime: 108, boot: "boot"))
        precondition(!pending.exitWindow(now: start.addingTimeInterval(300), uptime: 101, boot: "boot"))
        precondition(!pending.exitWindow(now: start.addingTimeInterval(300), uptime: 300, boot: "other-boot"))
        precondition(pending.exitWindow(now: start.addingTimeInterval(27), uptime: 115, boot: "boot"))
        precondition(!loaded.exitWindow(now: start.addingTimeInterval(20), uptime: 108, boot: "boot"))
        precondition(loaded.cancelExit(now: start.addingTimeInterval(20)))
        precondition(loaded.rest(now: start.addingTimeInterval(21)))
        precondition(loaded.reconcile(now: end) == "window_expired")
        precondition(!loaded.returnToWindow(now: end))
        var pendingRelief = try WindowPolicy.create(start: start, end: end, targets: ["synthetic.fixture"], budget: 300, maxGrant: 300, now: start.addingTimeInterval(-1))
        precondition(pendingRelief.relief(now: start))
        precondition(pendingRelief.requestExit(now: start.addingTimeInterval(1)))
        precondition(pendingRelief.reconcile(now: start.addingTimeInterval(300)) == "relief_ended")
        precondition(pendingRelief.state == .exitPending && pendingRelief.stateBeforeExitPending == .active)
        precondition(pendingRelief.reconcile(now: end) == "window_expired")
        precondition(!pendingRelief.exitWindow(now: end))
        let schedule = WindowSchedule(weekdays: [4], startMinute: 900, endMinute: 960, targetRefs: ["synthetic.fixture"], reliefBudgetSeconds: 300, reliefMaxGrantSeconds: 300)
        var scheduled = schedule; scheduled.policyRevisionId = policy.policyRevisionId
        try store.saveConfiguration(policy, schedule: scheduled, now: start)
        let reloadedPolicy = try store.load()!
        let restoredSchedule = try store.loadSchedule()!
        precondition(reloadedPolicy == policy && restoredSchedule.policyRevisionId == policy.policyRevisionId)
        precondition(restoredSchedule.window(on: start.addingTimeInterval(10))?.policyRevisionId == policy.policyRevisionId)
        let configuredLog = try String(contentsOf: store.eventURL, encoding: .utf8)
        precondition(configuredLog.contains("policyRevisionId") && configuredLog.contains("committed"))
        precondition(restoredSchedule.window(on: start.addingTimeInterval(5))?.endsAt == end)
        let lateWindow = restoredSchedule.window(on: start.addingTimeInterval(3300))!
        precondition(lateWindow.startsAt == start && lateWindow.endsAt == end)
        var resumedLate = lateWindow; precondition(resumedLate.reconcile(now: start.addingTimeInterval(3300)) == "window_started")
        precondition(restoredSchedule.window(on: end) == nil)
        print("Window policy persistence and absolute deadline passed")
    }
}

import Foundation
@main enum RecurringIntegrationGateTests {
    static func main() throws {
        let firstStart = ISO8601DateFormatter().date(from: "2026-10-29T12:00:00Z")!
        let target = "dev.stanislav.focusdeck.guard-fixture-two", digest = String(repeating: "a", count: 64)
        let approved = try WindowPolicy.create(start: firstStart, end: firstStart.addingTimeInterval(3600), targets: [target], budget: 300, maxGrant: 300, now: firstStart.addingTimeInterval(-60))
        let schedule = WindowSchedule(policyRevisionId: approved.policyRevisionId, weekdays: [4, 5], startMinute: 900, endMinute: 960, targetRefs: [target], reliefBudgetSeconds: 300, reliefMaxGrantSeconds: 300)
        let tomorrow = firstStart.addingTimeInterval(86400)
        let next = schedule.window(on: tomorrow)!
        let nextDisk = CandidateDiskState(policy: next, bootID: "fake", uptimeDeadline: 100, reliefUptimeDeadline: nil, trustDigest: digest)
        precondition(!nextDisk.matches(approved: approved, trustDigest: digest)) // deployed single-window binding rejects the next day
        var terminal = approved; terminal.state = .exit; terminal.reliefRemainingSeconds = 0
        var reset = approved; reset.windowId = UUID(); reset.state = .active
        let guardCore = WindowGuardV2(allowedTargets: [target], trustedUID: 501, trustedHash: "fake", requireCalendar: true)
        let (_, sameDay) = guardCore.apply(WindowGuardRequest(policy: reset, command: "publish"), current: terminal, peerUID: 501, peerHash: "fake", now: firstStart)
        precondition(!sameDay.accepted) // exit and spent budget cannot be silently replaced today
        var prototype = RecurringLedgerCandidate(schedule: schedule, trustDigest: digest, anchor: .init(wall: firstStart, uptime: 100, bootID: "A"))
        let reboot = try prototype.reconcile(now: firstStart.addingTimeInterval(60), uptime: 10, bootID: "B", trustDigest: digest)
        precondition(reboot == .clockUnknown) // only the disconnected prototype has this conservative behaviour
        print("Recurrence integration gate: next day rejected by single-window binding; same-day reset rejected; fake reboot unknown. Production recurrence remains OFF.")
    }
}

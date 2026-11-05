import Foundation
@main enum WindowFixtureBoundsTests {
    static func main() throws {
        let now = ISO8601DateFormatter().date(from: "2026-10-29T05:00:00Z")! // 08:00 Minsk
        var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(150), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: [WindowFixtureBounds.target], state: .active)
        precondition(WindowFixtureBounds.permits(p, now: now))
        let fixture = WindowGuardV2(allowedTargets: [WindowFixtureBounds.target], trustedUID: 501, trustedHash: "fake")
        let production = WindowGuardV2(allowedTargets: [WindowFixtureBounds.target], trustedUID: 501, trustedHash: "fake", requireCalendar: true)
        let request = WindowGuardRequest(policy: p, command: "publish")
        precondition(fixture.apply(request, current: nil, peerUID: 501, peerHash: "fake", now: now).1.accepted)
        precondition(!production.apply(request, current: nil, peerUID: 501, peerHash: "fake", now: now).1.accepted)
        p.endsAt = now.addingTimeInterval(181); precondition(!WindowFixtureBounds.permits(p, now: now))
        p.endsAt = now.addingTimeInterval(150); p.targetRefs = ["dev.personal.app"]; precondition(!WindowFixtureBounds.permits(p, now: now))
        print("Fixture-only short window before 15:00 accepted; production calendar, duration limit and synthetic-only target remained enforced (fake peer).")
    }
}

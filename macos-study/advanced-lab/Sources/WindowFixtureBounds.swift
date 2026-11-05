import Foundation
// Fixture service only. No production calendar rule is modified.
enum WindowFixtureBounds {
    static let target = "dev.stanislav.focusdeck.guard-fixture"
    static func permits(_ policy: WindowPolicy, now: Date) -> Bool {
        policy.targetRefs == [target] && policy.startsAt < policy.endsAt && policy.endsAt > now &&
        policy.endsAt.timeIntervalSince(policy.startsAt) <= 180 && policy.endsAt.timeIntervalSince(now) <= 180
    }
}

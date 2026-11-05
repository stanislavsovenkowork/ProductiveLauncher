import Foundation
import Darwin
@main enum RecurringLedgerCandidateTests {
    static func main() throws {
        let base = ISO8601DateFormatter().date(from: "2026-10-29T12:00:00Z")! // Thu 15:00 Minsk
        let digest = String(repeating: "a", count: 64), revision = UUID()
        var schedule = WindowSchedule(policyRevisionId: revision, weekdays: [4, 5], startMinute: 900, endMinute: 960,
                                      targetRefs: ["dev.synthetic.app"], reliefBudgetSeconds: 300, reliefMaxGrantSeconds: 300)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("focusdeck-ledger-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = RecurringLedgerStore(directory: dir, expectedOwner: getuid())
        var state = RecurringLedgerCandidate(schedule: schedule, trustDigest: digest, anchor: .init(wall: base.addingTimeInterval(-60), uptime: 100, bootID: "boot-A"))
        try store.save(state)
        guard case let .window(first) = try store.transition(now: base, uptime: 160, bootID: "boot-A", trustDigest: digest) else { fatalError("window not issued") }
        guard case let .window(reloaded) = try store.transition(now: base.addingTimeInterval(1), uptime: 161, bootID: "boot-A", trustDigest: digest) else { fatalError("reload failed") }
        precondition(first.windowId == reloaded.windowId && first.reliefRemainingSeconds == 300)
        state = try store.load(); precondition(state.policy!.relief(now: base.addingTimeInterval(2))); try store.save(state)
        guard case let .window(spent) = try store.transition(now: base.addingTimeInterval(3), uptime: 163, bootID: "boot-A", trustDigest: digest) else { fatalError("spent state lost") }
        precondition(spent.reliefRemainingSeconds == 0 && spent.endsAt == first.endsAt)
        schedule.policyRevisionId = UUID()
        state = try store.load(); do { try state.replaceSchedule(schedule); fatalError("active schedule replaced") } catch RecurringLedgerCandidate.Failure.activePreviousWindow { }
        state.policy!.state = .exit; state.policy!.reliefUntil = nil
        var swapped = schedule; swapped.targetRefs = ["dev.other.app"]
        do { try state.replaceSchedule(swapped); fatalError("trusted target set changed") } catch RecurringLedgerCandidate.Failure.invalid { }
        try state.replaceSchedule(schedule); try store.save(state)
        guard case let .window(exited) = try store.transition(now: base.addingTimeInterval(4), uptime: 164, bootID: "boot-A", trustDigest: digest) else { fatalError("exit not retained") }
        precondition(exited.state == .exit && exited.reliefRemainingSeconds == 0 && exited.windowId == first.windowId)
        let tomorrow = base.addingTimeInterval(86400)
        guard case let .window(next) = try store.transition(now: tomorrow, uptime: 86560, bootID: "boot-A", trustDigest: digest) else { fatalError("next day missing") }
        precondition(next.localDate != first.localDate && next.windowId != first.windowId && next.reliefRemainingSeconds == 300)
        let reboot = try store.transition(now: tomorrow.addingTimeInterval(1), uptime: 1, bootID: "boot-B", trustDigest: digest)
        let afterReboot = try store.load(); precondition(reboot == .clockUnknown && afterReboot.clockUnknown)
        var wallJump = RecurringLedgerCandidate(schedule: schedule, trustDigest: digest, anchor: .init(wall: base, uptime: 1, bootID: "boot"))
        let jumpResult = try wallJump.reconcile(now: base.addingTimeInterval(3600), uptime: 2, bootID: "boot", trustDigest: digest); precondition(jumpResult == .clockUnknown)
        var invalid = schedule; invalid.startMinute = 899; precondition(!RecurringLedgerCandidate.valid(invalid, trustDigest: digest))
        var protected = schedule; protected.targetRefs = ["com.openai.codex"]; precondition(!RecurringLedgerCandidate.valid(protected, trustDigest: digest))
        do { _ = try store.transition(now: tomorrow, uptime: 86560, bootID: "boot-A", trustDigest: String(repeating: "b", count: 64)); fatalError("changed trust accepted") } catch RecurringLedgerCandidate.Failure.invalid { }
        let original = try Data(contentsOf: store.file); try Data("corrupt".utf8).write(to: store.file)
        do { _ = try store.transition(now: tomorrow, uptime: 2, bootID: "boot-B", trustDigest: digest); fatalError("corrupt data accepted") } catch { }
        let corrupted = try Data(contentsOf: store.file); precondition(corrupted == Data("corrupt".utf8)); try original.write(to: store.file)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path) }
        do { _ = try store.transition(now: tomorrow, uptime: 2, bootID: "boot-B", trustDigest: digest); fatalError("write failure accepted") } catch { }
        print("Prototype recurrence: atomic snapshot, duplicate/restart, spent budget, terminal exit, next day, changed revision, clock/reboot uncertainty, corrupt/write failure passed. Not wired to daemon/UI.")
    }
}

import Foundation
@main struct WindowGuardV2Tests {
    static func main() throws {
        let start = ISO8601DateFormatter().date(from: "2026-10-29T12:00:00Z")!
        let target = "dev.stanislav.focusdeck.guard-fixture"
        var ui = try WindowPolicy.create(start: start, end: start.addingTimeInterval(3600), targets: [target], budget: 300, maxGrant: 300, now: start.addingTimeInterval(-1))
        _ = ui.reconcile(now: start)
        let guardCore = WindowGuardV2(allowedTargets: [target], trustedUID: 501, trustedHash: "fixture-hash")
        func send(_ command: String, _ policy: WindowPolicy, _ current: WindowPolicy?, _ time: Date) -> (WindowPolicy?, WindowGuardReply) {
            guardCore.apply(WindowGuardRequest(policy: policy, command: command), current: current, peerUID: 501, peerHash: "fixture-hash", now: time, uptime: 115, boot: "fixture")
        }
        let rejected = guardCore.apply(WindowGuardRequest(policy: ui, command: "publish"), current: nil, peerUID: 502, peerHash: "fixture-hash", now: start)
        precondition(!rejected.1.accepted)
        var saved = send("publish", ui, nil, start).0!
        var killed: [String] = []
        func fakeTermination(_ id: String) -> Bool { killed.append(id); return true }
        precondition(guardCore.attempt(target, policy: saved, now: start, terminate: fakeTermination))
        precondition(!guardCore.attempt("com.openai.codex", policy: saved, now: start, terminate: fakeTermination))
        precondition(ui.relief(now: start.addingTimeInterval(1)))
        saved = send("update", ui, saved, start.addingTimeInterval(1)).0!
        precondition(!guardCore.attempt(target, policy: saved, now: start.addingTimeInterval(2), terminate: fakeTermination))
        precondition(guardCore.attempt(target, policy: saved, now: start.addingTimeInterval(302), terminate: fakeTermination))
        var reset = ui; reset.reliefRemainingSeconds = 300
        precondition(!send("update", reset, saved, start.addingTimeInterval(2)).1.accepted)
        precondition(ui.returnToWindow(now: start.addingTimeInterval(3)))
        saved = send("update", ui, saved, start.addingTimeInterval(3)).0!
        precondition(ui.requestExit(now: start.addingTimeInterval(3), uptime: 100, boot: "fixture"))
        saved = send("update", ui, saved, start.addingTimeInterval(3)).0!
        precondition(ui.exitWindow(now: start.addingTimeInterval(18), uptime: 115, boot: "fixture"))
        saved = send("update", ui, saved, start.addingTimeInterval(18)).0!
        precondition(!guardCore.attempt(target, policy: saved, now: start.addingTimeInterval(19), terminate: fakeTermination))
        precondition(send("publish", ui, saved, start.addingTimeInterval(19)).1.accepted)
        var changedTerminal = ui; changedTerminal.state = .active
        precondition(!send("publish", changedTerminal, saved, start.addingTimeInterval(19)).1.accepted)
        precondition(killed.count == 2)
        print("V2 fixture policy model → authenticated bridge core → injected fake process → relief → return → exit passed; no root/network/apps touched.")
    }
}

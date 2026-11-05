import Foundation
@main enum RootCommandTests {
    static func main() throws {
        let now = Date()
        let trust = RootTrust(schemaVersion: 1, allowedUID: 501, uiCDHash: String(repeating: "a", count: 40), blockedSigningIDs: ["dev.fixture"], blockedDomains: ["example.test"], protectedDomains: ["openai.com"])
        let id = UUID()
        let request = RootRequest(schemaVersion: 1, command: "start", sessionId: id, seconds: 1500, hostsEnabled: true)
        let (started, reply) = try RootCommandCore.apply(request, trust: trust, peerUID: 501, peerHash: trust.uiCDHash, current: nil, now: now)
        precondition(reply.accepted && started!.blockedDomains == trust.blockedDomains && started!.targetUID == 501)
        for (uid, hash) in [(UInt32(502), trust.uiCDHash), (UInt32(501), "spoof")] {
            let (changed, response) = try RootCommandCore.apply(request, trust: trust, peerUID: uid, peerHash: hash, current: nil, now: now)
            precondition(changed == nil && !response.accepted)
        }
        let (_, reset) = try RootCommandCore.apply(request, trust: trust, peerUID: 501, peerHash: trust.uiCDHash, current: started, now: now.addingTimeInterval(1))
        precondition(!reset.accepted)
        let pause = RootRequest(schemaVersion: 1, command: "pause", sessionId: id, seconds: 300, hostsEnabled: nil)
        let (paused, pausedReply) = try RootCommandCore.apply(pause, trust: trust, peerUID: 501, peerHash: trust.uiCDHash, current: started, now: now.addingTimeInterval(1))
        precondition(pausedReply.accepted && paused!.pauseUsed && paused!.expiresAt == started!.expiresAt.addingTimeInterval(300))
        let (_, secondPause) = try RootCommandCore.apply(pause, trust: trust, peerUID: 501, peerHash: trust.uiCDHash, current: paused, now: now.addingTimeInterval(2))
        precondition(!secondPause.accepted)
        let (_, wrongSession) = try RootCommandCore.apply(RootRequest(schemaVersion: 1, command: "pause", sessionId: UUID(), seconds: 300, hostsEnabled: nil), trust: trust, peerUID: 501, peerHash: trust.uiCDHash, current: started, now: now)
        precondition(!wrongSession.accepted)
        precondition(paused!.expiresAt.timeIntervalSince(paused!.createdAt) == 1800)
        let bounded = try RootGuardCore(session: paused!, now: now.addingTimeInterval(1), uptime: 101, maximumDeadline: 1600 + paused!.pauseGrantSeconds)
        precondition(bounded.deadline == 1900)
        let engine = try RootGuardCore(session: paused!, now: now.addingTimeInterval(1), uptime: 100)
        precondition(!engine.enforcing(now: now.addingTimeInterval(2), uptime: 101))
        precondition(engine.enforcing(now: now.addingTimeInterval(302), uptime: 401))
        precondition(engine.enforcing(now: now.addingTimeInterval(2), uptime: 401)) // rollback cannot extend pause beyond uptime bound
        var scans = 0
        _ = engine.targets(supplying: { scans += 1; return [] }, now: now.addingTimeInterval(2), uptime: 101)
        _ = engine.targets(supplying: { scans += 1; return [] }, now: now.addingTimeInterval(1900), uptime: 2000)
        precondition(scans == 0)
        let rebuilt = try RootGuardCore(session: started!, now: now.addingTimeInterval(1), uptime: 1000, maximumDeadline: 1500)
        precondition(rebuilt.deadline == 1500) // retained tombstone caps restart/reload budget
        let protected = UUID()
        var ledger = RootDeadlineLedger(service: "dev.stanislav.focusdeck.guard", bootID: "fixture", deadlines: [protected.uuidString: 10])
        for index in 0..<5000 { ledger.deadlines[UUID().uuidString] = Double(index * 1500) }
        ledger.prune(uptime: Double(5001 * 1500), protecting: protected)
        precondition(ledger.deadlines.count <= 257 && ledger.deadlines[protected.uuidString] == 10)
        print("Pinned-UID/hash session start/no-reset/one-pause/foreign-session/clock-bound core tests passed. No root socket or policy modified.")
    }
}

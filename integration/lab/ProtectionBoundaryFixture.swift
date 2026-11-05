import Foundation

@main enum ProtectionBoundaryFixture {
    static func main() throws {
        let start = Date(timeIntervalSince1970: 1_793_174_400)
        let id = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
        let trust = RootTrust(schemaVersion: 1, allowedUID: 501, uiCDHash: String(repeating: "a", count: 40),
                              blockedSigningIDs: ["dev.focusdeck.synthetic"], blockedDomains: [], protectedDomains: [])
        func request(_ command: String, _ seconds: Double?) -> RootRequest {
            RootRequest(schemaVersion: 1, command: command, sessionId: id, seconds: seconds, hostsEnabled: false)
        }
        let (initial, accepted) = try RootCommandCore.apply(request("start", 1500), trust: trust,
            peerUID: 501, peerHash: trust.uiCDHash, current: nil, now: start)
        precondition(accepted.accepted)
        let (paused, grant) = try RootCommandCore.apply(request("pause", 300), trust: trust,
            peerUID: 501, peerHash: trust.uiCDHash, current: initial, now: start.addingTimeInterval(600))
        precondition(grant.accepted)
        let (_, repeated) = try RootCommandCore.apply(request("pause", 300), trust: trust,
            peerUID: 501, peerHash: trust.uiCDHash, current: paused, now: start.addingTimeInterval(601))
        let firstEngine = try RootGuardCore(session: initial!, now: start, uptime: 100)
        let pausedEngine = try RootGuardCore(session: paused!, now: start.addingTimeInterval(600), uptime: 700)
        var states: [String: Bool] = [:]
        for offset in [599,600,899,900,1500,1799,1800] {
            let engine = offset < 600 ? firstEngine : pausedEngine
            states[String(offset)] = engine.enforcing(now: start.addingTimeInterval(Double(offset)), uptime: 100 + Double(offset))
        }
        var work = SessionState()
        work.taskTitle = "Synthetic work, no personal file"
        work.start(now: start)
        for second in 1...600 { _ = work.tick(elapsed: 1, now: start.addingTimeInterval(Double(second))) }
        let first = work.pause(now: start.addingTimeInterval(600))!
        for second in 601...900 { _ = work.tick(elapsed: 1, now: start.addingTimeInterval(Double(second))) }
        work.resume(now: start.addingTimeInterval(900))
        for second in 901...1800 { _ = work.tick(elapsed: 1, now: start.addingTimeInterval(Double(second))) }
        let last = work.closeSegment(now: start.addingTimeInterval(1800))!
        let recorded = first.1.timeIntervalSince(first.0) + last.1.timeIntervalSince(last.0)
        let result: [String: Any] = ["schemaVersion": 1, "baseWorkSeconds": 1500, "pauseSeconds": 300,
            "maxProtectionSeconds": 1800, "expiryFromStartSeconds": paused!.expiresAt.timeIntervalSince(start),
            "secondPauseAccepted": repeated.accepted, "restrictedAt": states,
            "observedWorkSeconds": recorded, "remainingWorkSeconds": work.remainingSeconds]
        precondition(recorded == 1500 && work.remainingSeconds == 0)
        let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted,.sortedKeys])
        FileHandle.standardOutput.write(data); FileHandle.standardOutput.write(Data("\n".utf8))
    }
}

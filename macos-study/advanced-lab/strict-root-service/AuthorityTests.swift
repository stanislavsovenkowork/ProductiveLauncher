import Foundation
import Darwin

@main enum AuthorityTests {
    static func main() throws {
        var template = Array("/tmp/focusdeck-strict-root-qa.XXXXXXXX\0".utf8CString)
        guard let path = mkdtemp(&template) else { fatalError("mkdtemp") }
        let dir = String(cString: path); defer { try? FileManager.default.removeItem(atPath: dir) }
        guard chmod(dir, 0o700) == 0 else { fatalError("chmod") }
        let hash = String(repeating: "a", count: 40)
        let wall = Date(timeIntervalSince1970: 1_700_000_000)
        let plan = StrictRootPlan(schemaVersion: 1, revisionId: UUID(), windowId: UUID(), startsAt: wall, endsAt: wall.addingTimeInterval(7200), appRequirements: [], siteDomains: ["qa.example"], siteSubdomains: true)
        let initial = StrictRootClock(wall: wall, continuous: 100, bootID: "qa-boot")
        let discover = StrictRootEnvelope(schemaVersion: 1, kind: .discover, enrollment: nil, request: nil)
        let enroll = StrictRootEnvelope(schemaVersion: 1, kind: .enroll, enrollment: .init(schemaVersion: 1, plan: plan), request: nil)
        func command(_ type: StrictRootCommand) -> StrictRootEnvelope {
            .init(schemaVersion: 1, kind: .command, enrollment: nil, request: .init(schemaVersion: 1, revisionId: plan.revisionId, windowId: plan.windowId, command: type))
        }
        do {
            let store = try StrictRootStore(directory: dir, owner: getuid())
            let engine = try StrictRootAuthority(store: store, allowedUID: 501, allowedHash: hash, requirement: { _ in false })
            do { _ = try engine.handle(discover, peerUID: 502, peerHash: hash, at: initial); fatalError("foreign UID accepted") } catch StrictRootAuthority.Failure.invalidTrust {}
            do { _ = try engine.handle(discover, peerUID: 501, peerHash: String(repeating: "0", count: 40), at: initial); fatalError("foreign hash accepted") } catch StrictRootAuthority.Failure.invalidTrust {}
            let empty = try engine.handle(discover, peerUID: 501, peerHash: hash, at: initial)
            precondition(empty.accepted && empty.reason == "not_enrolled" && empty.snapshot == nil)
            let first = try engine.handle(enroll, peerUID: 501, peerHash: hash, at: initial)
            precondition(first.accepted && first.snapshot?.enforcementRequested == true && first.snapshot?.coverage.sites == .unknown)
            let duplicate = try engine.handle(enroll, peerUID: 501, peerHash: hash, at: initial); precondition(duplicate.accepted && duplicate.reason == "already_enrolled")
            let rest = try engine.handle(command(.rest), peerUID: 501, peerHash: hash, at: .init(wall: wall, continuous: 101, bootID: "qa-boot"))
            precondition(rest.accepted && rest.snapshot?.enforcementRequested == true)
            let request = try engine.handle(command(.requestExit), peerUID: 501, peerHash: hash, at: .init(wall: wall, continuous: 102, bootID: "qa-boot"))
            precondition(request.accepted && request.snapshot?.exitRemainingSeconds == 3600)
            let early = try engine.handle(command(.confirmExit), peerUID: 501, peerHash: hash, at: .init(wall: wall.addingTimeInterval(99999), continuous: 103, bootID: "qa-boot"))
            precondition(!early.accepted && early.snapshot?.enforcementRequested == true)
            let changed = try engine.tick(at: .init(wall: wall, continuous: 3702, bootID: "qa-boot")); precondition(changed?.phase == .exitPending)
        }
        do {
            let store = try StrictRootStore(directory: dir, owner: getuid())
            let engine = try StrictRootAuthority(store: store, allowedUID: 501, allowedHash: hash, requirement: { _ in false })
            let result = try engine.handle(command(.confirmExit), peerUID: 501, peerHash: hash, at: .init(wall: wall, continuous: 3702, bootID: "qa-boot"))
            precondition(result.accepted && result.snapshot?.phase == .exit && result.snapshot?.enforcementRequested == false)
            let other = StrictRootPlan(schemaVersion: 1, revisionId: UUID(), windowId: UUID(), startsAt: wall, endsAt: wall.addingTimeInterval(7200), appRequirements: [], siteDomains: ["qa.example"], siteSubdomains: true)
            let refuse = try engine.handle(.init(schemaVersion: 1, kind: .enroll, enrollment: .init(schemaVersion: 1, plan: other), request: nil), peerUID: 501, peerHash: hash, at: .init(wall: wall, continuous: 3703, bootID: "qa-boot"))
            precondition(!refuse.accepted && refuse.reason == "existing_window")
            try Data("external change".utf8).write(to: URL(fileURLWithPath: dir + "/record.json"))
            do { _ = try engine.handle(discover, peerUID: 501, peerHash: hash, at: initial); fatalError("modified ledger accepted") } catch StrictRootAuthority.Failure.stateUnknown {}
        }
        print("PASS: authenticated mock, durable enrollment/transition, restart, exit delay, no reset, changed ledger refused")
    }
}

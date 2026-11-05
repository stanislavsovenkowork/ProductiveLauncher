import Foundation
@main enum WindowGuardProductionCandidateTests {
    static func main() throws {
        for _ in ["never enrolled", "lost ledger", "after recovery"] {
            precondition(!WindowCandidateEnrollmentGate.permits(existing: nil, command: "publish"))
            precondition(!WindowCandidateEnrollmentGate.permits(existing: nil, command: "update"))
        }
        precondition(WindowCandidateEnrollmentGate.permits(existing: nil, command: "status"))
        precondition(WindowCandidateRecoveryCore.authorized(uid: 0, payload: WindowCandidateRecoveryCore.command))
        precondition(!WindowCandidateRecoveryCore.authorized(uid: 501, payload: WindowCandidateRecoveryCore.command))
        precondition(!WindowCandidateRecoveryCore.authorized(uid: 0, payload: Data("other".utf8)))
        let expected = TrustedSigningRequirement(signingID: "dev.stanislav.focusdeck.guard-fixture-two", teamID: "ABCD123456", requirement: "identifier \"dev.stanislav.focusdeck.guard-fixture-two\"")
        precondition(ProcessSignatureRequirement.valid(expected))
        precondition(ProcessSignatureRequirement.permits(expected, signingID: expected.signingID, teamID: expected.teamID, requirementMatches: true))
        precondition(!ProcessSignatureRequirement.permits(expected, signingID: expected.signingID, teamID: "OTHER12345", requirementMatches: true))
        precondition(!ProcessSignatureRequirement.permits(expected, signingID: expected.signingID, teamID: expected.teamID, requirementMatches: false))
        precondition(!ProcessSignatureRequirement.permits(expected, signingID: expected.signingID, teamID: nil, requirementMatches: true))
        let adhoc = TrustedSigningRequirement(signingID: expected.signingID, teamID: "", requirement: expected.requirement)
        precondition(!ProcessSignatureRequirement.valid(adhoc))
        let start = ISO8601DateFormatter().date(from: "2026-10-29T12:00:00Z")!
        let first = "dev.stanislav.focusdeck.guard-fixture", second = "dev.stanislav.focusdeck.guard-fixture-two"
        var policy = try WindowPolicy.create(start: start, end: start.addingTimeInterval(3600), targets: [first, second], budget: 300, maxGrant: 300, now: start.addingTimeInterval(-1))
        _ = policy.reconcile(now: start)
        let core = WindowGuardV2(allowedTargets: [first, second], trustedUID: 501, trustedHash: "test", requireCalendar: true)
        func apply(_ p: WindowPolicy, _ current: WindowPolicy?, uid: UInt32 = 501, hash: String = "test") -> (WindowPolicy?, WindowGuardReply) {
            core.apply(WindowGuardRequest(policy: p, command: "publish"), current: current, peerUID: uid, peerHash: hash, now: start)
        }
        precondition(!apply(policy, nil, uid: 502).1.accepted && !apply(policy, nil, hash: "wrong").1.accepted)
        let missingStatus = core.apply(WindowGuardRequest(policy: policy, command: "status"), current: nil, peerUID: 501, peerHash: "test", now: start).1
        precondition(missingStatus.accepted && missingStatus.policy == nil && missingStatus.windowId == nil)
        let proposed = apply(policy, nil)
        precondition(proposed.1.accepted && !WindowCandidateEnrollmentGate.permits(existing: nil, command: "publish"))
        let stored = proposed.0!
        precondition(WindowCandidateEnrollmentGate.permits(existing: stored, command: "update"))
        let observed = core.apply(WindowGuardRequest(policy: policy, command: "status"), current: stored, peerUID: 501, peerHash: "test", now: start).1
        precondition(observed.accepted && observed.policy?.windowId == stored.windowId)
        let unauthorized = core.apply(WindowGuardRequest(policy: policy, command: "status"), current: stored, peerUID: 502, peerHash: "test", now: start).1
        precondition(!unauthorized.accepted && unauthorized.policy == nil && unauthorized.windowId == nil)
        var calls: [String] = []
        precondition(core.attempt(first, policy: stored, now: start) { calls.append($0); return true })
        precondition(core.attempt(second, policy: stored, now: start) { calls.append($0); return true })
        precondition(!core.attempt("dev.unselected.app", policy: stored, now: start) { calls.append($0); return true })
        for id in ["com.apple.Safari", "com.openai.codex", "net.openvpn.client", "org.wireguard.mac", "com.vendor.corporatevpn", "com.gitlab.agent", "com.vendor.tailscale"] {
            precondition(WindowGuardV2.isProtected(id))
            let unsafe = WindowGuardV2(allowedTargets: [id], trustedUID: 501, trustedHash: "test")
            var modified = policy; modified.targetRefs = [id]
            precondition(!unsafe.apply(WindowGuardRequest(policy: modified, command: "publish"), current: nil, peerUID: 501, peerHash: "test", now: start).1.accepted)
        }
        var invalidCalendar = policy; invalidCalendar.startsAt = start.addingTimeInterval(-3600); precondition(!apply(invalidCalendar, nil).1.accepted)
        var changed = policy; changed.targetRefs = [first]; precondition(!apply(changed, stored).1.accepted)
        changed = policy; changed.endsAt = policy.endsAt.addingTimeInterval(300); precondition(!apply(changed, stored).1.accepted)
        precondition(calls == [first, second])
        print("Production-scope core: missing/removed/recovered ledger cannot re-enroll; spoof rejection, fake peer and immutable selection/deadline passed; no real app or daemon run.")
    }
}

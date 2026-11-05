import Foundation

// Compile-time fixture only. No socket, browser, network or persistent state.
struct TrustedSigningRequirement: Codable, Sendable, Equatable {
    let signingID: String; let teamID: String; let requirement: String
}

@main @MainActor enum SpikeTests {
    static func main() throws {
        let start = ISO8601DateFormatter().date(from: "2026-11-02T12:00:00Z")!
        let target = "dev.stanislav.focusdeck.guard-fixture"
        let guardCore = WindowGuardV2(allowedTargets: [target], trustedUID: 501, trustedHash: "fixture")
        let sites = StudyPolicy(blockedDomains: ["example.test"], protectedDomains: ["safe.example.test"], blockedAppBundleIds: [])
        var p = try WindowPolicy.create(start: start, end: start.addingTimeInterval(3600), targets: [target], budget: 300, maxGrant: 300, now: start.addingTimeInterval(-1))
        _ = p.reconcile(now: start)
        func apply(_ policy: WindowPolicy, _ current: WindowPolicy?, _ now: Date) -> WindowGuardReply {
            guardCore.apply(WindowGuardRequest(policy: policy, command: current == nil ? "publish" : "update"), current: current, peerUID: 501, peerHash: "fixture", now: now, uptime: 115, boot: "fixture").1
        }
        // This is an intentionally non-production decision fixture: a real integration must
        // authenticate and obtain a fresh status, and cancel in-flight actions on change.
        func wouldBlock(_ reply: WindowGuardReply?, _ url: String, _ now: Date) -> Bool {
            guard let reply, reply.accepted, let policy = reply.policy, reply.windowId == policy.windowId else { return false }
            return WindowGuardV2.enforcing(policy, now: now) && BrowserControl.blocks(url: url, policy: sites)
        }
        let url = "https://video.example.test/watch"
        var r = apply(p, nil, start); precondition(r.accepted && wouldBlock(r, url, start))
        precondition(!wouldBlock(r, "https://safe.example.test/", start))
        precondition(!wouldBlock(r, "https://example.test.evil.invalid/", start))
        precondition(!wouldBlock(nil, url, start))
        precondition(p.relief(now: start.addingTimeInterval(1)))
        r = apply(p, r.policy, start.addingTimeInterval(1)); precondition(r.accepted && !wouldBlock(r, url, start.addingTimeInterval(2)))
        precondition(wouldBlock(r, url, start.addingTimeInterval(302)))
        precondition(p.returnToWindow(now: start.addingTimeInterval(3)))
        r = apply(p, r.policy, start.addingTimeInterval(3)); precondition(r.accepted && wouldBlock(r, url, start.addingTimeInterval(3)))
        precondition(p.requestExit(now: start.addingTimeInterval(3), uptime: 100, boot: "fixture"))
        r = apply(p, r.policy, start.addingTimeInterval(3)); precondition(r.accepted && wouldBlock(r, url, start.addingTimeInterval(3)))
        precondition(p.exitWindow(now: start.addingTimeInterval(18), uptime: 115, boot: "fixture"))
        r = apply(p, r.policy, start.addingTimeInterval(18)); precondition(r.accepted && !wouldBlock(r, url, start.addingTimeInterval(18)))
        print("Synthetic model/domain spike passed: window, relief, return, exit, unknown and domain boundaries. No browser or socket used.")
    }
}

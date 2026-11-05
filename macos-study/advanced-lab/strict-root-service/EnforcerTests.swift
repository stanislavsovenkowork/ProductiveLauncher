import Foundation
import Darwin

@main enum EnforcerTests {
    static func main() {
        let trusted = TrustedSigningRequirement(signingID: "dev.focusdeck.synthetic", teamID: "ABCDE12345", requirement: "identifier \"dev.focusdeck.synthetic\" and anchor apple")
        let process = ProcessIdentity(pid: 40001, uid: 501, parent: 1, startSeconds: 3, startMicros: 4, signingID: trusted.signingID)
        let reused = ProcessIdentity(pid: 40001, uid: 501, parent: 1, startSeconds: 5, startMicros: 4, signingID: trusted.signingID)
        var enforcer = StrictAppEnforcer(); var sent: [(Int32, Int32)] = []; var scanCount = 0
        func tick(_ enforcing: Bool, _ t: Double, identity: ProcessIdentity = process, valid: Bool = true,
                  requirements: [TrustedSigningRequirement] = [trusted]) -> Int {
            enforcer.tick(enforcing: enforcing, requirements: requirements, targetUID: 501, now: t,
                          scan: { scanCount += 1; return [process] }, inspect: { _ in identity },
                          verify: { _, _ in valid }, send: { pid, signal in sent.append((pid, signal)); return 0 })
        }
        precondition(tick(false, 1) == 0 && scanCount == 0)
        precondition(tick(true, 1, identity: reused) == 0 && sent.isEmpty)
        precondition(tick(true, 1, valid: false) == 0 && sent.isEmpty)
        precondition(tick(true, 1) == 1 && sent.last?.1 == SIGTERM)
        precondition(tick(true, 2) == 0)
        precondition(tick(true, 3, identity: reused) == 0 && sent.count == 1)
        precondition(tick(true, 3, valid: false) == 0 && sent.count == 1)
        precondition(tick(true, 3) == 1 && sent.last?.1 == SIGKILL)
        precondition(tick(false, 4) == 0)
        precondition(tick(true, 5) == 1 && sent.last?.1 == SIGTERM)
        let protected = TrustedSigningRequirement(signingID: "com.openai.codex", teamID: "ABCDE12345", requirement: "identifier \"com.openai.codex\"")
        precondition(tick(true, 6, requirements: [protected]) == 0)
        print("PASS: enforcement gate, selected UID/identity/signature checks, protected ID, TERM then bounded KILL; signals mocked")
    }
}

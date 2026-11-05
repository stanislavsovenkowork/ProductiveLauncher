import Foundation
import Darwin
@main enum UserProtectionLedgerTests {
 static func main() throws {
    let dir = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    let store = UserProtectionLedgerStore(directory: dir, owner: getuid()); precondition(try! store.load(allowSynthetic: true) == nil)
    let now = Date(timeIntervalSince1970: floor(Date().timeIntervalSince1970)); var policy = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now, endsAt: now.addingTimeInterval(120), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 10, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active)
    let requirement = TrustedSigningRequirement(signingID: "dev.stanislav.focusdeck.guard-fixture", teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
    var state = UserProtectionLedger(schemaVersion: 1, policy: policy, signingRequirements: [requirement]); precondition(!state.valid())
    try store.save(state, allowSynthetic: true); precondition(try! store.load(allowSynthetic: true) == state)
    precondition(policy.relief(now: now)); state.policy = policy; try store.save(state, allowSynthetic: true)
    precondition(try! store.load(allowSynthetic: true)?.policy.reliefRemainingSeconds == 20)
    let spoof = UserProtectionLedger(schemaVersion: 1, policy: policy, signingRequirements: [TrustedSigningRequirement(signingID: "dev.stanislav.focusdeck.guard-fixture", teamID: "synthetic", requirement: "wrong")]); precondition(!spoof.valid(allowSynthetic: true))
    let protected = UserProtectionLedger(schemaVersion: 1, policy: policy, signingRequirements: [TrustedSigningRequirement(signingID: "com.apple.Safari", teamID: "ABCDEFGHIJ", requirement: "identifier \"com.apple.Safari\"")]); precondition(!protected.valid(allowSynthetic: true))
    print("User ledger: identities and spent budget persist together; synthetic spoof and protected target refused.")
 }
}

import Foundation
@main enum ReturnQALedgerTests {
 static func main() {
  let now = Date(); let target = WindowFixtureBounds.target; let hash = CommandLine.arguments[1]
  let p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "synthetic", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(590), reliefBudgetSeconds: 120, reliefRemainingSeconds: 120, reliefMaxGrantSeconds: 120, targetRefs: [target], state: .active)
  let requirement = TrustedSigningRequirement(signingID: target, teamID: "synthetic", requirement: hash)
  let state = UserProtectionLedger(schemaVersion: 1, policy: p, signingRequirements: [requirement])
  precondition(state.valid(allowSynthetic: true) && WindowFixtureBounds.permits(p, now: now))
  var invalid = state; invalid.signingRequirements[0] = TrustedSigningRequirement(signingID: target, teamID: "synthetic", requirement: String(repeating: "0", count: 40))
  precondition(!invalid.valid(allowSynthetic: true)); precondition(!state.valid(allowSynthetic: false))
  print("QA ledger accepts exact synthetic signature and rejects a different signature and production mode; no service started.")
 }
}

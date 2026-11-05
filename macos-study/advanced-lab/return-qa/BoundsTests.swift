import Foundation
@main enum ReturnQABoundsTests {
 static func main() {
  let now = Date(); let target = WindowFixtureBounds.target
  var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "synthetic", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(590), reliefBudgetSeconds: 120, reliefRemainingSeconds: 120, reliefMaxGrantSeconds: 120, targetRefs: [target], state: .active)
  precondition(WindowFixtureBounds.permits(p, now: now))
  precondition(p.relief(now: now)); precondition(p.reliefUntil == now.addingTimeInterval(120)); precondition(p.reliefRemainingSeconds == 0)
  precondition(p.returnToWindow(now: now.addingTimeInterval(10))); precondition(p.state == .active)
  p.targetRefs = ["dev.personal.app"]; precondition(!WindowFixtureBounds.permits(p, now: now)); p.targetRefs = [target]
  p.endsAt = p.startsAt.addingTimeInterval(601); precondition(!WindowFixtureBounds.permits(p, now: now)); p.endsAt = now.addingTimeInterval(590)
  p.reliefBudgetSeconds = 121; precondition(!WindowFixtureBounds.permits(p, now: now)); p.reliefBudgetSeconds = 120
  p.reliefMaxGrantSeconds = 121; precondition(!WindowFixtureBounds.permits(p, now: now))
  print("QA synthetic target, ten-minute bound, 120-second cap and return model passed; no service or process started.")
 }
}

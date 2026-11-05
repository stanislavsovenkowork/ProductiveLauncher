import Foundation
@main enum UserRecurrenceClockTests {
 static func main() {
  let f = ISO8601DateFormatter(), start = f.date(from: "2026-11-02T12:00:00Z")!, revision = UUID(), target = "dev.stanislav.focusdeck.guard-fixture"
  let policy = WindowPolicy(policyRevisionId: revision, windowId: UUID(), localDate: "2026-11-02", startsAt: start, endsAt: start.addingTimeInterval(600), reliefBudgetSeconds: 10, reliefRemainingSeconds: 10, reliefMaxGrantSeconds: 5, targetRefs: [target], state: .active)
  let schedule = WindowSchedule(policyRevisionId: revision, weekdays: [1,2,3,4,5,6,7], startMinute: 900, endMinute: 910, targetRefs: [target], reliefBudgetSeconds: 10, reliefMaxGrantSeconds: 5, exitCoolingOffSeconds: 15)
  let requirement = TrustedSigningRequirement(signingID: target, teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
  let anchor = UserProtectionLedger.ClockAnchor(wall: start, uptime: 100, boot: "A")
  let base = UserProtectionLedger(schemaVersion: 1, policy: policy, signingRequirements: [requirement], schedule: schedule, lastIssuedLocalDate: policy.localDate, clockAnchor: anchor)
  var reboot = base; precondition(reboot.advance(now: start.addingTimeInterval(1), uptime: 101, boot: "B") == "schedule_clock_unknown"); precondition(reboot.clockUncertain == true && reboot.policy.windowId == policy.windowId)
  var p = reboot.policy; precondition(p.requestExit(now: start.addingTimeInterval(1), uptime: 101, boot: "B")); precondition(p.exitWindow(now: start.addingTimeInterval(16), uptime: 116, boot: "B"))
  var jump = base; precondition(jump.advance(now: start.addingTimeInterval(86460), uptime: 101, boot: "A") == "schedule_clock_unknown")
  _ = jump.advance(now: start.addingTimeInterval(86460), uptime: 101, boot: "A"); precondition(jump.policy.windowId == policy.windowId && jump.clockUncertain == true)
  var sleep = base; precondition(sleep.advance(now: start.addingTimeInterval(300), uptime: 101, boot: "A") == "schedule_clock_unknown")
  print("Clock tests: reboot, forward jump and wall/uptime disagreement mark unknown without issuing a new budget; bounded exit remains available.")
 }
}

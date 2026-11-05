import Foundation
import Darwin
@main enum UserRecurrenceAgentTests {
 static func main() throws {
  let a = CommandLine.arguments; guard a.count == 6 else { fatalError("agent, child, data, socket, clock") }
  let formatter = ISO8601DateFormatter(), start = formatter.date(from: "2026-11-02T12:00:00Z")!, before = start.addingTimeInterval(-300)
  let clock = URL(fileURLWithPath: a[5]); var wall = before, uptime = 100.0, boot = "fake-boot-A"
  func setClock(_ date: Date, _ up: Double, _ id: String) throws {
   let data = try JSONSerialization.data(withJSONObject: ["wall": formatter.string(from: date), "uptime": up, "boot": id]); try data.write(to: clock, options: .atomic)
   wall = date; uptime = up; boot = id; usleep(400000)
  }
  try setClock(wall, uptime, boot)
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[3], "FOCUSDECK_USER_TEST_SOCKET": a[4], "FOCUSDECK_USER_TEST_CLOCK": a[5]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[4]) { break }; usleep(10000) }
  let id = "dev.stanislav.focusdeck.guard-fixture", revision = UUID()
  var p = WindowPolicy(policyRevisionId: revision, windowId: UUID(), localDate: "2026-11-02", startsAt: start, endsAt: start.addingTimeInterval(600), reliefBudgetSeconds: 10, reliefRemainingSeconds: 10, reliefMaxGrantSeconds: 5, targetRefs: [id], state: .inactive); p.exitCoolingOffSeconds = 1
  let schedule = WindowSchedule(policyRevisionId: revision, weekdays: [1,2,3,4,5,6,7], startMinute: 900, endMinute: 910, targetRefs: [id], reliefBudgetSeconds: 10, reliefMaxGrantSeconds: 5, exitCoolingOffSeconds: 1)
  let req = TrustedSigningRequirement(signingID: id, teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
  func send(_ command: String, _ policy: WindowPolicy, _ attach: Bool = false) throws -> WindowGuardReply { try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: command, signingRequirements: attach ? [req] : nil, schedule: attach ? schedule : nil), path: a[4], expectedUID: getuid()) }
  precondition(try! send("publish", p, true).accepted)
  try setClock(start.addingTimeInterval(60), 460, boot); p = try send("status", p).policy!; precondition(p.state == .active)
  var editedSchedule = schedule; editedSchedule.startMinute = 901; editedSchedule.endMinute = 911
  let edit = try WindowGuardV2Client.send(WindowGuardRequest(policy: p, command: "update", schedule: editedSchedule), path: a[4], expectedUID: getuid())
  precondition(!edit.accepted && edit.reason == "active_policy_immutable")
  let child = Process(); child.executableURL = URL(fileURLWithPath: a[2]); child.standardOutput = FileHandle.nullDevice; try child.run()
  for _ in 0..<50 { if !child.isRunning { break }; usleep(50000) }
  guard !child.isRunning else { child.terminate(); child.waitUntilExit(); fatalError("synthetic process was not limited") }; child.waitUntilExit()
  precondition(p.relief(now: wall)); precondition(try! send("update", p).accepted); precondition(p.reliefRemainingSeconds == 5)
  precondition(p.returnToWindow(now: wall)); precondition(try! send("update", p).accepted)
  precondition(p.requestExit(now: wall, uptime: uptime, boot: boot)); precondition(try! send("update", p).accepted)
  try setClock(wall.addingTimeInterval(2), uptime + 2, boot); precondition(p.exitWindow(now: wall, uptime: uptime, boot: boot)); precondition(try! send("update", p).accepted)
  usleep(400000); let same = try send("status", p); precondition(same.policy?.state == .exit && same.policy?.reliefRemainingSeconds == 5 && same.policy?.windowId == p.windowId)
  var duplicate = p; duplicate.windowId = UUID(); duplicate.state = .active; duplicate.reliefRemainingSeconds = 10
  precondition(!(try! send("publish", duplicate, true).accepted))
  agent.terminate(); agent.waitUntilExit()
  let restarted = Process(); restarted.executableURL = agent.executableURL; restarted.environment = agent.environment; try restarted.run(); defer { if restarted.isRunning { restarted.terminate(); restarted.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[4]) { break }; usleep(10000) }
  let restored = try send("status", p); precondition(restored.policy?.windowId == p.windowId && restored.policy?.reliefRemainingSeconds == 5 && restored.policy?.state == .exit)
  let tomorrow = start.addingTimeInterval(86400 + 60), advance = tomorrow.timeIntervalSince(wall)
  try setClock(tomorrow, uptime + advance, boot)
  var next = try send("status", p).policy!; precondition(next.localDate == "2026-11-03" && next.windowId == WindowPolicy.recurringWindowID(revision: revision, date: next.localDate) && next.reliefRemainingSeconds == 10 && next.state == .active)
  let second = try send("status", p); precondition(second.policy?.windowId == next.windowId && second.policy?.reliefRemainingSeconds == 10)
  try setClock(wall.addingTimeInterval(-3600), uptime + 1, boot)
  let unknown = try send("status", p); precondition(unknown.scheduleStatus == "clock_unknown" && unknown.policy?.windowId == next.windowId)
  try setClock(tomorrow, uptime + 1, "fake-boot-B")
  next = try send("status", p).policy!; precondition(next.windowId == second.policy?.windowId)
  precondition(next.requestExit(now: wall, uptime: uptime, boot: boot)); precondition(try! send("update", next).accepted)
  try setClock(wall.addingTimeInterval(2), uptime + 2, boot); precondition(next.exitWindow(now: wall, uptime: uptime, boot: boot)); precondition(try! send("update", next).accepted)
  precondition(try! send("status", next).policy?.state == .exit)
  restarted.terminate(); restarted.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: a[4]))
  print("User agent fake clock: daily single window/budget, explicit exit/restart, next day, clock uncertainty and bounded exit passed; only synthetic process affected.")
 }
}

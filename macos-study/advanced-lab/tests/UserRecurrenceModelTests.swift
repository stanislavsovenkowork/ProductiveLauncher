import Foundation
import Darwin
@main @MainActor enum UserRecurrenceModelTests {
 static func main() async throws {
  precondition(SessionModel.reliefBalanceText(seconds: 0) == "0 сек")
  precondition(SessionModel.reliefBalanceText(seconds: 30) == "30 сек")
  precondition(SessionModel.reliefBalanceText(seconds: 60) == "1 мин")
  precondition(SessionModel.reliefBalanceText(seconds: 90) == "1 мин 30 сек")
  let a = CommandLine.arguments; guard a.count == 5, let root = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], root.hasPrefix("/tmp/focusdeck-") else { fatalError("temporary paths required") }
  let f = ISO8601DateFormatter(), day1 = f.date(from: "2026-11-02T12:00:00Z")!, revision = UUID(), target = "dev.stanislav.focusdeck.guard-fixture"
  let clock = URL(fileURLWithPath: a[4]); var wall = day1.addingTimeInterval(60), uptime = 100.0; let boot = "model-fake-boot"
  func setClock(_ date: Date, _ up: Double) async throws { try JSONSerialization.data(withJSONObject: ["wall": f.string(from: date), "uptime": up, "boot": boot]).write(to: clock, options: .atomic); wall = date; uptime = up; try await Task.sleep(for: .milliseconds(450)) }
  try await setClock(wall, uptime)
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[2], "FOCUSDECK_USER_TEST_SOCKET": a[3], "FOCUSDECK_USER_TEST_CLOCK": a[4]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; try await Task.sleep(for: .milliseconds(10)) }
  var p = WindowPolicy(policyRevisionId: revision, windowId: UUID(), localDate: "2026-11-02", startsAt: day1, endsAt: day1.addingTimeInterval(600), reliefBudgetSeconds: 10, reliefRemainingSeconds: 10, reliefMaxGrantSeconds: 5, targetRefs: [target], state: .active); p.exitCoolingOffSeconds = 1
  let schedule = WindowSchedule(policyRevisionId: revision, weekdays: [1,2,3,4,5,6,7], startMinute: 900, endMinute: 910, targetRefs: [target], reliefBudgetSeconds: 10, reliefMaxGrantSeconds: 5, exitCoolingOffSeconds: 1)
  let requirement = TrustedSigningRequirement(signingID: target, teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc"), bridge = WindowCandidateBridge(path: a[3], expectedUID: getuid())
  guard let first = await bridge.request(p, command: "publish", requirements: [requirement], schedule: schedule), first.accepted else { fatalError("initial schedule refused") }
  precondition(p.requestExit(now: wall, uptime: uptime, boot: boot)); guard (await bridge.request(p, command: "update"))?.accepted == true else { fatalError("exit request refused") }
  try await setClock(wall.addingTimeInterval(2), uptime + 2); precondition(p.exitWindow(now: wall, uptime: uptime, boot: boot)); guard (await bridge.request(p, command: "update"))?.accepted == true else { fatalError("exit refused") }
  try WindowPolicyStore(directory: URL(fileURLWithPath: root)).saveConfiguration(p, schedule: schedule)
  let tomorrow = day1.addingTimeInterval(86460), elapsed = tomorrow.timeIntervalSince(wall); try await setClock(tomorrow, uptime + elapsed)
  let model = SessionModel(candidateBridge: bridge); await model.syncCandidate()
  precondition(model.candidateAuthoritative && model.windowPolicy?.localDate == "2026-11-03" && model.windowPolicy?.windowId == WindowPolicy.recurringWindowID(revision: revision, date: "2026-11-03"))
  let id = model.windowPolicy!.windowId
  var granted = model.windowPolicy!; precondition(granted.relief(now: wall))
  guard let receipt = await bridge.request(granted, command: "update"), receipt.accepted else { fatalError("relief refused") }
  await model.syncCandidate()
  precondition(model.candidateAuthoritative && model.windowPolicy?.reliefRemainingSeconds == 5)
  model.state.taskTitle = "Synthetic task one"; model.start(); model.finish(completed: false)
  model.state.taskTitle = "Synthetic task two"; model.start()
  precondition(model.windowPolicy?.windowId == id && model.windowPolicy?.reliefRemainingSeconds == 5)
  let reopened = SessionModel(candidateBridge: bridge); await reopened.syncCandidate()
  precondition(reopened.candidateAuthoritative && reopened.windowPolicy?.windowId == id && reopened.windowPolicy?.reliefRemainingSeconds == 5)
  precondition(reopened.state.taskTitle == "Synthetic task two")
  await reopened.stopUserSchedule()
  precondition(reopened.candidateAuthoritative && reopened.windowSchedule == nil && reopened.windowPolicy?.windowId == id && reopened.windowPolicy?.state == .limitedRelief)
  let status = await bridge.request(reopened.windowPolicy!, command: "status"); precondition(status?.scheduleStatus == "off" && status?.policy?.windowId == id && status?.policy?.reliefRemainingSeconds == 5)
  let day3 = tomorrow.addingTimeInterval(86400); try await setClock(day3, uptime + day3.timeIntervalSince(wall))
  let after = await bridge.request(reopened.windowPolicy!, command: "status"); precondition(after?.scheduleStatus == "off" && after?.policy?.windowId == id && after?.policy?.state == .expired)
  agent.terminate(); agent.waitUntilExit()
  print("Main SessionModel adopts helper's next day and spent budget across stop/new task/reopen; disabling schedule preserves the window.")
 }
}

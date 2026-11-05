import Foundation
import Darwin
@main @MainActor enum UserAgentModelTests {
 static func main() async throws {
  let a = CommandLine.arguments; guard a.count == 4, ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"]?.hasPrefix("/tmp/focusdeck-") == true else { fatalError("temporary paths required") }
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[2], "FOCUSDECK_USER_TEST_SOCKET": a[3]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; try await Task.sleep(for: .milliseconds(10)) }
  let model = SessionModel(fixtureBridge: WindowFixtureBridge(path: a[3], expectedUID: getuid()))
  let now = Date(); var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(30), reliefBudgetSeconds: 5, reliefRemainingSeconds: 5, reliefMaxGrantSeconds: 3, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active); p.exitCoolingOffSeconds = 1
  await model.startUserFixture(p); precondition(model.windowPolicy?.windowId == p.windowId && model.fixtureAcknowledgedState == .active)
  model.state.taskTitle = "Synthetic first task"; model.start(); model.finish(completed: false)
  model.state.taskTitle = "Synthetic second task"; model.start(); await model.syncFixture()
  precondition(model.windowPolicy?.windowId == p.windowId && model.windowPolicy?.reliefRemainingSeconds == 5 && model.fixtureAcknowledgedState == .active)
  await model.userFixtureAction("relief"); precondition(model.windowPolicy?.state == .limitedRelief && model.windowPolicy?.reliefRemainingSeconds == 2)
  agent.terminate(); agent.waitUntilExit()
  await model.userFixtureAction("returned")
  precondition(model.windowPolicy?.state == .limitedRelief && model.windowPolicy?.reliefRemainingSeconds == 2 && model.fixtureAcknowledgedState == nil && model.fixtureBridgeStatus.contains("неизвестна"))
  let restarted = Process(); restarted.executableURL = agent.executableURL; restarted.environment = agent.environment; try restarted.run(); defer { if restarted.isRunning { restarted.terminate(); restarted.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; try await Task.sleep(for: .milliseconds(10)) }
  await model.syncFixture(); precondition(model.windowPolicy?.reliefRemainingSeconds == 2 && model.fixtureAcknowledgedState == .limitedRelief)
  await model.userFixtureAction("returned"); precondition(model.windowPolicy?.state == .active && model.windowPolicy?.reliefRemainingSeconds == 2)
  model.windowPolicy = nil; await model.syncFixture()
  precondition(model.windowPolicy?.windowId == p.windowId && model.windowPolicy?.reliefRemainingSeconds == 2 && model.fixtureAcknowledgedState == .active)
  model.fixtureServiceDisconnected()
  precondition(model.fixtureAcknowledgedWindow == nil && model.fixtureAcknowledgedState == nil && model.fixtureBridgeStatus.contains("неизвестна"))
  await model.syncFixture(); precondition(model.fixtureAcknowledgedState == .active)
  restarted.terminate(); restarted.waitUntilExit()
  model.windowPolicy?.state = .expired; model.fixtureAcknowledgedState = .expired; model.fixtureAcknowledgedWindow = p.windowId; model.fixtureBridgeStatus = "Служба подтвердила состояние synthetic fixture."
  await model.syncFixture()
  precondition(model.fixtureAcknowledgedWindow == nil && model.fixtureAcknowledgedState == nil && model.fixtureBridgeStatus.contains("неизвестна"))
  print("User fixture SessionModel: helper-confirmed budget, offline unknown and restart adoption passed.")
 }
}

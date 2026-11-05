import Foundation
import Darwin
@main @MainActor enum UserProtectionActiveModelTests {
 static func main() async throws {
  let a = CommandLine.arguments; guard a.count == 5, let root = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], root.hasPrefix("/tmp/focusdeck-") else { fatalError("temporary paths required") }
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[3], "FOCUSDECK_USER_TEST_SOCKET": a[4]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[4]) { break }; try await Task.sleep(for: .milliseconds(10)) }
  let bridge = WindowCandidateBridge(path: a[4], expectedUID: getuid()), now = Date()
  var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(60), reliefBudgetSeconds: 5, reliefRemainingSeconds: 5, reliefMaxGrantSeconds: 3, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active); p.exitCoolingOffSeconds = 1
  let req = TrustedSigningRequirement(signingID: p.targetRefs[0], teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
  guard let initial = await bridge.request(p, command: "publish", requirements: [req]), initial.accepted else { fatalError("initial synthetic policy refused") }
  try WindowPolicyStore(directory: URL(fileURLWithPath: root)).save(p)
  let model = SessionModel(candidateBridge: bridge); await model.syncCandidate(); precondition(model.candidateAuthoritative)
  func spawn() throws -> Process { let p = Process(); p.executableURL = URL(fileURLWithPath: a[2]); p.standardOutput = FileHandle.nullDevice; try p.run(); return p }
  func killed(_ p: Process) async { for _ in 0..<50 { if !p.isRunning { break }; try? await Task.sleep(for: .milliseconds(50)) }; guard !p.isRunning else { p.terminate(); p.waitUntilExit(); fatalError("synthetic target survived") }; p.waitUntilExit(); precondition(p.terminationStatus == SIGTERM) }
  let blocked = try spawn(); await killed(blocked)
  await model.candidateAction("relief"); precondition(model.windowPolicy?.state == .limitedRelief && model.windowPolicy?.reliefRemainingSeconds == 2)
  let relieved = try spawn(); try await Task.sleep(for: .milliseconds(300)); precondition(relieved.isRunning)
  await model.candidateAction("returned"); await killed(relieved); precondition(model.windowPolicy?.reliefRemainingSeconds == 2)
  await model.candidateAction("exit_requested"); try await Task.sleep(for: .milliseconds(1100)); await model.candidateAction("exit")
  precondition(model.windowPolicy?.state == .exit)
  let released = try spawn(); try await Task.sleep(for: .milliseconds(300)); precondition(released.isRunning); released.terminate(); released.waitUntilExit()
  agent.terminate(); agent.waitUntilExit()
  print("Main SessionModel → user agent → synthetic process: block, relief, return, exit and reserved budget passed.")
 }
}

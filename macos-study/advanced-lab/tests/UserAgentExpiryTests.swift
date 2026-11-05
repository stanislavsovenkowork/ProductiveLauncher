import Foundation
import Darwin
@main enum UserAgentExpiryTests {
 static func main() throws {
  let a = CommandLine.arguments; guard a.count == 5 else { fatalError("agent, target, directory, socket required") }
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[3], "FOCUSDECK_USER_TEST_SOCKET": a[4]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[4]) { break }; usleep(10000) }
  let now = Date(); let p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(3), reliefBudgetSeconds: 5, reliefRemainingSeconds: 5, reliefMaxGrantSeconds: 3, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active)
  func send(_ cmd: String) throws -> WindowGuardReply { try WindowGuardV2Client.send(WindowGuardRequest(policy: p, command: cmd), path: a[4], expectedUID: getuid()) }
  precondition(try! send("publish").accepted)
  let blocked = Process(); blocked.executableURL = URL(fileURLWithPath: a[2]); blocked.standardOutput = FileHandle.nullDevice; try blocked.run()
  for _ in 0..<30 { if !blocked.isRunning { break }; usleep(50000) }
  guard !blocked.isRunning else { blocked.terminate(); blocked.waitUntilExit(); fatalError("fixture not blocked") }; blocked.waitUntilExit()
  usleep(3500000)
  precondition(try! send("status").policy?.state == .expired)
  let released = Process(); released.executableURL = URL(fileURLWithPath: a[2]); released.standardOutput = FileHandle.nullDevice; try released.run(); usleep(350000)
  precondition(released.isRunning); released.terminate(); released.waitUntilExit()
  agent.terminate(); agent.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: a[4]))
  print("User agent: absolute expiry independently releases synthetic target after client disconnect.")
 }
}

import Foundation
import Darwin
@main enum UserCalendarFixtureAgentTests {
 static func main() throws {
  let a = CommandLine.arguments; guard a.count == 5 else { fatalError("agent, target, directory, socket required") }
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[3], "FOCUSDECK_USER_TEST_SOCKET": a[4]]; try agent.run()
  defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[4]) { break }; usleep(10000) }
  var now = Date()
  var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(30), reliefBudgetSeconds: 5, reliefRemainingSeconds: 5, reliefMaxGrantSeconds: 3, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active); p.exitCoolingOffSeconds = 1
  func send(_ cmd: String, _ policy: WindowPolicy) throws -> WindowGuardReply { try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: cmd), path: a[4], expectedUID: getuid()) }
  func child() throws -> Process {
   let p = Process(); p.executableURL = URL(fileURLWithPath: a[2]); p.standardOutput = FileHandle.nullDevice; try p.run(); return p
  }
  func killed(_ p: Process) {
   for _ in 0..<60 { if !p.isRunning { break }; usleep(50000) }
   guard !p.isRunning else { p.terminate(); p.waitUntilExit(); fatalError("synthetic target survived active policy") }
   p.waitUntilExit(); precondition(p.terminationReason == .uncaughtSignal && p.terminationStatus == SIGTERM)
  }
  precondition(try! send("publish", p).accepted)
  let first = try child(); killed(first)
  let afterClientDisconnect = try child(); killed(afterClientDisconnect)
  var reset = p; reset.windowId = UUID(); precondition(!(try! send("publish", reset).accepted))
  now = Date(); precondition(p.relief(now: now)); precondition(try! send("publish", p).accepted)
  let remaining = p.reliefRemainingSeconds; precondition(remaining == 2)
  let free = try child(); usleep(350000); precondition(free.isRunning)
  precondition(p.returnToWindow(now: Date())); precondition(try! send("publish", p).accepted); killed(free)
  precondition(p.requestExit(now: Date())); precondition(try! send("publish", p).accepted); usleep(1200000)
  precondition(p.exitWindow(now: Date())); precondition(try! send("publish", p).accepted)
  let afterExit = try child(); usleep(350000); precondition(afterExit.isRunning); afterExit.terminate(); afterExit.waitUntilExit()
  agent.terminate(); agent.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: a[4]))
  let again = Process(); again.executableURL = agent.executableURL; again.environment = agent.environment; try again.run(); defer { if again.isRunning { again.terminate(); again.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[4]) { break }; usleep(10000) }
  let recovered = try send("status", p); precondition(recovered.policy?.state == .exit && recovered.policy?.reliefRemainingSeconds == remaining)
  again.terminate(); again.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: a[4]))
  print("User agent: exact synthetic target blocked after client disconnect; reset refused; relief/return/exit and restart budget passed, no root.")
 }
}

import Foundation
import Darwin
@main enum UserProtectionQATests {
 static func main() throws {
  let a = CommandLine.arguments; guard a.count == 4 else { fatalError("agent, data, socket required") }
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[2], "FOCUSDECK_USER_TEST_SOCKET": a[3]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; usleep(10000) }
  let now = Date(); var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(90), reliefBudgetSeconds: 10, reliefRemainingSeconds: 10, reliefMaxGrantSeconds: 5, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active); p.exitCoolingOffSeconds = 1
  func send(_ p: WindowPolicy, _ req: [TrustedSigningRequirement]?) throws -> WindowGuardReply { try WindowGuardV2Client.send(WindowGuardRequest(policy: p, command: "publish", signingRequirements: req), path: a[3], expectedUID: getuid()) }
  func clear() throws -> String {
    let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
    var addr = try guardAddress(a[3]); let rc = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }; guard rc == 0 else { throw RootSocketFailure.systemCall }
    try writeRootFrame(Data("clear-test-data".utf8), fd: fd, timeout: 2); return String(decoding: try readRootFrame(fd, timeout: 2), as: UTF8.self)
  }
  var other = p; other.targetRefs = ["com.example.unrelated"]
  let otherReq = TrustedSigningRequirement(signingID: other.targetRefs[0], teamID: "ABCDEFGHIJ", requirement: "identifier \\\"com.example.unrelated\\\"")
  let denied = try send(other, [otherReq]); precondition(!denied.accepted && denied.reason == "qa_synthetic_only")
  let req = TrustedSigningRequirement(signingID: p.targetRefs[0], teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
  precondition(try! send(p, [req]).accepted); precondition(try! clear() == "refused-active")
  precondition(p.requestExit(now: Date())); precondition(try! send(p, nil).accepted); usleep(1200000)
  precondition(p.exitWindow(now: Date())); precondition(try! send(p, nil).accepted)
  let unexpected = URL(fileURLWithPath: a[2]).appendingPathComponent("unexpected")
  try Data("fixture".utf8).write(to: unexpected); precondition(try! clear() == "refused-data")
  try FileManager.default.removeItem(at: unexpected); precondition(try! clear() == "cleared")
  agent.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: a[2]) && !FileManager.default.fileExists(atPath: a[3]))
  print("Main QA: non-synthetic and active/foreign cleanup refused; terminal synthetic data and socket removed.")
 }
}

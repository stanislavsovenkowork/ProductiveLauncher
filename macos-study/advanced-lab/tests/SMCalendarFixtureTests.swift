import Foundation
import Darwin
@main enum SMCalendarFixtureTests {
 static func main() throws {
    let args = CommandLine.arguments
    guard args.count == 5 else { fatalError("daemon, data directory, socket and argv launcher required") }
    let daemon = Process(); daemon.executableURL = URL(fileURLWithPath: args[4]); daemon.arguments = [args[1]]; daemon.environment = ["FOCUSDECK_SM_FAKE_DIR": args[2], "FOCUSDECK_SM_FAKE_SOCKET": args[3]]
    try daemon.run(); defer { if daemon.isRunning { daemon.terminate(); daemon.waitUntilExit() } }
    for _ in 0..<100 { if FileManager.default.fileExists(atPath: args[3]) { break }; usleep(10000) }
    let now = Date(); var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(120), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active)
    p.exitCoolingOffSeconds = 1
    func send(_ command: String, _ policy: WindowPolicy) throws -> WindowGuardReply { try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: command), path: args[3], expectedUID: getuid()) }
    func clear() throws -> String {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
        var address = try guardAddress(args[3])
        let rc = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard rc == 0 else { throw RootSocketFailure.systemCall }
        try writeRootFrame(Data("clear-test-data".utf8), fd: fd, timeout: 2)
        return String(decoding: try readRootFrame(fd, timeout: 2), as: UTF8.self)
    }
    precondition(try! send("status", p).policy == nil)
    precondition(try! send("publish", p).accepted)
    precondition(try! clear() == "refused-active")
    precondition(p.relief(now: Date())); precondition(try! send("publish", p).accepted)
    let remaining = p.reliefRemainingSeconds; precondition(remaining < 30)
    precondition(p.returnToWindow(now: Date())); precondition(try! send("publish", p).accepted)
    precondition(p.requestExit(now: Date())); precondition(try! send("publish", p).accepted)
    Thread.sleep(forTimeInterval: 1.2); precondition(p.exitWindow(now: Date())); precondition(try! send("publish", p).accepted)
    let terminal = try send("status", p); precondition(terminal.policy?.state == .exit && terminal.policy?.reliefRemainingSeconds == remaining)
    daemon.terminate(); daemon.waitUntilExit(); FileHandle.standardError.write(Data("daemon reason=\(daemon.terminationReason.rawValue) status=\(daemon.terminationStatus) socket=\(FileManager.default.fileExists(atPath: args[3]))\n".utf8)); precondition(!FileManager.default.fileExists(atPath: args[3]))
    let events = try String(contentsOfFile: args[2] + "/events.jsonl", encoding: .utf8)
    for name in ["fixture_terminated", "relief_fixture_started", "policy_active", "policy_exit"] { precondition(events.contains(name)) }
    let rows = events.split(separator: "\n").compactMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
    guard let lastChild = rows.last(where: { ($0["event"] as? String) == "exit_fixture_started" })?["childPID"] as? Int32 else { fatalError("missing child pid") }
    precondition(kill(lastChild, 0) == -1 && errno == ESRCH)
    let restarted = Process(); restarted.executableURL = URL(fileURLWithPath: args[4]); restarted.arguments = [args[1]]; restarted.environment = daemon.environment
    try restarted.run(); defer { if restarted.isRunning { restarted.terminate(); restarted.waitUntilExit() } }
    for _ in 0..<100 { if FileManager.default.fileExists(atPath: args[3]) { break }; usleep(10000) }
    let restored = try send("status", p); precondition(restored.policy?.state == .exit && restored.policy?.reliefRemainingSeconds == remaining)
    let unexpected = URL(fileURLWithPath: args[2]).appendingPathComponent("unexpected.txt")
    try Data("fixture".utf8).write(to: unexpected)
    precondition(try! clear() == "refused-data")
    precondition(FileManager.default.fileExists(atPath: unexpected.path))
    try FileManager.default.removeItem(at: unexpected)
    precondition(try! clear() == "cleared")
    restarted.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: args[3]))
    precondition(!FileManager.default.fileExists(atPath: args[2]))
    print("Fixture: active/foreign file cleanup refused; terminal clear removed owned data, socket and child; restart retained terminal budget.")
 }
}

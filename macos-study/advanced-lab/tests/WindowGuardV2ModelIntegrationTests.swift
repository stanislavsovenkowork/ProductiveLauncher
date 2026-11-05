import Foundation
import Darwin
@main @MainActor enum WindowGuardV2ModelIntegrationTests {
    static let target = "dev.stanislav.focusdeck.guard-fixture"
    static func main() async throws {
        let args = CommandLine.arguments
        if args.count == 5 && args[1] == "server" { try server(socketPath: args[2], pidFile: args[3], owner: Int32(args[4])!); return }
        guard args.count == 2, let dataDir = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], dataDir.contains("focusdeck") else { fatalError("isolated fixture directory and synthetic binary required") }
        let dir = URL(fileURLWithPath: dataDir); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let path = "/tmp/fdv2-" + String(UUID().uuidString.prefix(8)) + ".sock"
        let pidFile = dir.appendingPathComponent("fixture-pid")
        let serverProcess = Process(); serverProcess.executableURL = URL(fileURLWithPath: args[0]); serverProcess.arguments = ["server", path, pidFile.path, String(getpid())]
        let serverOut = Pipe(); serverProcess.standardOutput = serverOut; try serverProcess.run()
        defer { if serverProcess.isRunning { serverProcess.terminate() }; unlink(path) }
        precondition(serverOut.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        func spawn() throws -> Process {
            let proc = Process(); proc.executableURL = URL(fileURLWithPath: args[1]); let out = Pipe(); proc.standardOutput = out
            try proc.run(); precondition(out.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
            guard let identity = ProcessIdentity.inspect(proc.processIdentifier), identity.signingID == target, identity.parent == UInt32(getpid()) else { fatalError("wrong fixture identity") }
            try String(proc.processIdentifier).write(to: pidFile, atomically: true, encoding: .utf8); return proc
        }
        let model = SessionModel(fixtureBridge: WindowFixtureBridge(path: path, expectedUID: getuid()))
        let now = Date(); var policy = WindowPolicy(windowId: UUID(), localDate: "fixture", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(120), reliefBudgetSeconds: 60, reliefRemainingSeconds: 60, reliefMaxGrantSeconds: 60, targetRefs: [target], state: .active)
        policy.exitCoolingOffSeconds = 1; model.windowPolicy = policy
        let first = try spawn(); await model.syncFixture(); first.waitUntilExit(); precondition(first.terminationReason == .uncaughtSignal && first.terminationStatus == SIGTERM)
        model.windowAction("relief"); let second = try spawn(); await model.syncFixture(); precondition(second.isRunning)
        model.windowAction("returned"); await model.syncFixture(); second.waitUntilExit(); precondition(second.terminationReason == .uncaughtSignal && second.terminationStatus == SIGTERM)
        model.windowAction("exit_requested"); await model.syncFixture(); try await Task.sleep(for: .milliseconds(1200))
        let third = try spawn(); model.windowAction("exit"); await model.syncFixture(); precondition(third.isRunning)
        third.terminate(); third.waitUntilExit(); serverProcess.waitUntilExit(); precondition(serverProcess.terminationStatus == 0)
        let journal = try String(contentsOf: dir.appendingPathComponent("window-events-v2.jsonl"), encoding: .utf8)
        for name in ["relief_started", "returned", "exit_requested", "window_exited"] { precondition(journal.contains(name)) }
        print("SessionModel actions → Unix socket → unprivileged fixture service → owned signed synthetic process: stop/relief/return/exit and model events passed. No root/hosts/network/personal apps.")
    }
    static func server(socketPath: String, pidFile: String, owner: Int32) throws {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); defer { close(fd); unlink(socketPath) }
        var address = try guardAddress(socketPath)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard fd >= 0, bound == 0, listen(fd, 4) == 0 else { throw RootSocketFailure.systemCall }
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let core = WindowGuardV2(allowedTargets: [target], trustedUID: getuid(), trustedHash: "fixture")
        var current: WindowPolicy?
        for _ in 0..<5 {
            let client = accept(fd, nil, nil); guard client >= 0 else { throw RootSocketFailure.systemCall }
            var uid: uid_t = 0, gid: gid_t = 0; guard getpeereid(client, &uid, &gid) == 0 else { throw RootSocketFailure.systemCall }
            let request = try decoder.decode(WindowGuardRequest.self, from: readRootFrame(client, timeout: 2))
            let (next, reply) = core.apply(request, current: current, peerUID: uid, peerHash: "fixture", now: Date()); current = next
            if reply.accepted, let policy = next, let text = try? String(contentsOfFile: pidFile, encoding: .utf8), let pid = Int32(text),
               let identity = ProcessIdentity.inspect(pid), identity.parent == UInt32(owner), identity.uid == uid, identity.signingID == target {
                _ = core.attempt(target, policy: policy, now: Date()) { _ in RootGuardCore.terminate(identity) }
            }
            try writeRootFrame(encoder.encode(reply), fd: client, timeout: 2); close(client)
        }
    }
}

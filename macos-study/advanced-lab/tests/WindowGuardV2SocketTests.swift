import Foundation
import Darwin
@main enum WindowGuardV2SocketTests {
    static let target = "dev.stanislav.focusdeck.guard-fixture"
    static func main() throws {
        let args = CommandLine.arguments
        if args.count == 3 && args[1] == "server" { try server(args[2]); return }
        let path = "/tmp/fdv2-" + String(UUID().uuidString.prefix(8)) + ".sock"
        let child = Process(); child.executableURL = URL(fileURLWithPath: args[0]); child.arguments = ["server", path]
        let output = Pipe(); child.standardOutput = output; try child.run()
        defer { if child.isRunning { child.terminate() }; unlink(path) }
        precondition(output.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        let now = Date(), id = UUID()
        var policy = WindowPolicy(windowId: id, localDate: "fixture", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(300), reliefBudgetSeconds: 60, reliefRemainingSeconds: 60, reliefMaxGrantSeconds: 60, targetRefs: [target], state: .active)
        let started = try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: "publish"), path: path, expectedUID: getuid())
        precondition(started.accepted && started.enforcement == "selected_apps_requested")
        precondition(policy.relief(now: now)); let paused = try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: "update"), path: path, expectedUID: getuid())
        precondition(paused.accepted && paused.enforcement == "released")
        precondition(policy.returnToWindow(now: now.addingTimeInterval(1)))
        let resumed = try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: "update"), path: path, expectedUID: getuid())
        precondition(resumed.accepted && resumed.enforcement == "selected_apps_requested")
        child.waitUntilExit(); precondition(child.terminationStatus == 0)
        print("V2 Unix socket client → synthetic unprivileged service → fixture policy publish/relief/return passed; no root, hosts, network or app termination.")
    }
    static func server(_ path: String) throws {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); defer { close(fd); unlink(path) }
        var address = try guardAddress(path)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard fd >= 0, bound == 0, listen(fd, 4) == 0 else { throw RootSocketFailure.systemCall }
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let core = WindowGuardV2(allowedTargets: [target], trustedUID: getuid(), trustedHash: "fixture")
        var current: WindowPolicy?
        for _ in 0..<3 {
            let client = accept(fd, nil, nil); guard client >= 0 else { throw RootSocketFailure.systemCall }
            var uid: uid_t = 0, gid: gid_t = 0
            guard getpeereid(client, &uid, &gid) == 0 else { throw RootSocketFailure.systemCall }
            let req = try decoder.decode(WindowGuardRequest.self, from: readRootFrame(client, timeout: 2))
            let (next, reply) = core.apply(req, current: current, peerUID: uid, peerHash: "fixture", now: Date())
            current = next; try writeRootFrame(encoder.encode(reply), fd: client, timeout: 2); close(client)
        }
    }
}

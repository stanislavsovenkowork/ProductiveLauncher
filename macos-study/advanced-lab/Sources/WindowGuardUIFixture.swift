import Foundation
import Darwin

// Unprivileged manual UI fixture. Can signal only a child it spawned and whose
// UID, parent, start time and synthetic signing ID were re-verified.
@main enum WindowGuardUIFixture {
    static let target = "dev.stanislav.focusdeck.guard-fixture"
    static func main() throws {
        let args = CommandLine.arguments
        if args.count == 3 && args[1] == "--stop" {
            guard args[2].hasPrefix("/tmp/focusdeck-") else { throw Failure.invalid }
            let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
            var address = try guardAddress(args[2])
            let connected = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
            var uid: uid_t = 0, gid: gid_t = 0
            guard connected == 0, getpeereid(fd, &uid, &gid) == 0, uid == getuid() else { throw Failure.invalid }
            try writeRootFrame(Data("shutdown".utf8), fd: fd, timeout: 2)
            guard try readRootFrame(fd, timeout: 2) == Data("stopped".utf8) else { throw Failure.invalid }
            print("Fixture service and owned child stopped."); return
        }
        guard args.count == 5, args[1].hasPrefix("/tmp/focusdeck-"), args[2].hasPrefix("/tmp/focusdeck-"),
              args[4].count == 40, args[4].allSatisfy({ $0.isHexDigit }) else { throw Failure.invalid }
        let socketPath = args[1], dir = URL(fileURLWithPath: args[2]), executable = URL(fileURLWithPath: args[3]), hash = args[4]
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let now = Date()
        var policy = WindowPolicy(windowId: UUID(), localDate: "synthetic-ui-only", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(600), reliefBudgetSeconds: 300, reliefRemainingSeconds: 300, reliefMaxGrantSeconds: 300, targetRefs: [target], state: .active)
        policy.policyRevisionId = UUID(); policy.exitCoolingOffSeconds = 5
        let store = WindowPolicyStore(directory: dir)
        let schedule = WindowSchedule(policyRevisionId: policy.policyRevisionId, weekdays: [], startMinute: 900, endMinute: 901, targetRefs: [target], reliefBudgetSeconds: 300, reliefMaxGrantSeconds: 300, exitCoolingOffSeconds: 5)
        try store.saveConfiguration(policy, schedule: schedule)
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd); unlink(socketPath) }
        var address = try guardAddress(socketPath)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard bound == 0, chmod(socketPath, 0o600) == 0, listen(fd, 4) == 0 else { throw Failure.invalid }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let guardCore = WindowGuardV2(allowedTargets: [target], trustedUID: getuid(), trustedHash: hash)
        var current: WindowPolicy?, child: Process?, lastState: WindowPolicy.State?
        let events = dir.appendingPathComponent("ui-fixture-events.jsonl")
        func event(_ name: String) throws {
            var data = try JSONSerialization.data(withJSONObject: ["event": name, "at": ISO8601DateFormatter().string(from: Date())]); data.append(10)
            if !FileManager.default.fileExists(atPath: events.path) { FileManager.default.createFile(atPath: events.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
            let h = try FileHandle(forWritingTo: events); defer { try? h.close() }; try h.seekToEnd(); try h.write(contentsOf: data)
        }
        func spawn() throws -> Process {
            let p = Process(); p.executableURL = executable; let out = Pipe(); p.standardOutput = out; try p.run()
            guard out.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8), let identity = ProcessIdentity.inspect(p.processIdentifier),
                  identity.uid == getuid(), identity.parent == UInt32(getpid()), identity.signingID == target else { throw Failure.invalid }
            try event("fixture_started"); return p
        }
        func terminate(_ p: Process) throws {
            guard p.isRunning, let identity = ProcessIdentity.inspect(p.processIdentifier), identity.uid == getuid(),
                  identity.parent == UInt32(getpid()), identity.signingID == target,
                  RootGuardCore.terminate(identity) else { throw Failure.invalid }
            p.waitUntilExit(); guard p.terminationReason == .uncaughtSignal && p.terminationStatus == SIGTERM else { throw Failure.invalid }
            try event("fixture_terminated")
        }
        print("UI fixture ready; synthetic data only; no root/network/hosts."); fflush(stdout)
        while true {
            let client = accept(fd, nil, nil); guard client >= 0 else { continue }
            var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, length = socklen_t(MemoryLayout<Int32>.size)
            if getpeereid(client, &uid, &gid) == 0, getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &length) == 0,
               let data = try? readRootFrame(client, timeout: 1) {
                if uid == getuid(), data == Data("shutdown".utf8) {
                    if let p = child, p.isRunning { try terminate(p) }
                    try event("service_stopped"); try writeRootFrame(Data("stopped".utf8), fd: client, timeout: 1)
                    close(client); return
                }
                guard let request = try? decoder.decode(WindowGuardRequest.self, from: data) else { close(client); continue }
                let (next, reply) = guardCore.apply(request, current: current, peerUID: uid, peerHash: RootSocketServer.verifiedHash(pid: pid), now: Date())
                if reply.accepted, let next {
                    current = next
                    if lastState != next.state {
                        switch next.state {
                        case .active:
                            if lastState == nil { child = try spawn(); try terminate(child!) }
                            else if lastState == .limitedRelief, let p = child, p.isRunning { try event("relief_fixture_survived"); try terminate(p) }
                        case .limitedRelief: child = try spawn()
                        case .exit: child = try spawn(); Thread.sleep(forTimeInterval: 0.3); if child?.isRunning == true { try event("exit_fixture_survived") }
                        default: break
                        }
                        lastState = next.state
                    }
                }
                try? writeRootFrame(encoder.encode(reply), fd: client, timeout: 1)
            }
            close(client)
        }
    }
    enum Failure: Error { case invalid }
}

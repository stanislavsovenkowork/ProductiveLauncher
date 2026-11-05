import Foundation
import Darwin
import CryptoKit
private struct EnrollmentBinding: Decodable { let approvedWindow: WindowPolicy }
private struct EnrollmentReady: Decodable { let trustDigest: String; let policyRevisionId: UUID }
@main enum CandidateEnrollmentSocketTests {
    static func main() throws {
        let args = CommandLine.arguments
        if args.count == 5 && args[1] == "server" { try server(socketPath: args[2], dir: args[3], mode: args[4]); return }
        guard args.count == 2 else { fatalError("temporary prepared directory required") }
        let dir = URL(fileURLWithPath: args[1]), file = dir.appendingPathComponent("root-config.json")
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let approved = try decoder.decode(EnrollmentBinding.self, from: Data(contentsOf: file)).approvedWindow
        func spawn(_ mode: String) throws -> (Process, String) {
            let path = "/tmp/focusdeck-enroll-" + String(UUID().uuidString.prefix(8))
            let proc = Process(); proc.executableURL = URL(fileURLWithPath: args[0]); proc.arguments = ["server", path, dir.path, mode]
            let out = Pipe(); proc.standardOutput = out; try proc.run()
            precondition(out.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8)); return (proc, path)
        }
        func send(_ p: WindowPolicy, _ command: String, _ path: String) throws -> WindowGuardReply {
            try WindowGuardV2Client.send(WindowGuardRequest(policy: p, command: command), path: path, expectedUID: getuid())
        }
        let (firstServer, firstPath) = try spawn("first")
        defer { if firstServer.isRunning { firstServer.terminate() }; unlink(firstPath) }
        let initial = try send(approved, "status", firstPath)
        guard var grant = initial.policy else { fatalError("no prepared ledger") }
        precondition(initial.accepted && grant.windowId == approved.windowId && grant.reliefRemainingSeconds == approved.reliefBudgetSeconds)
        precondition(grant.relief(now: approved.startsAt.addingTimeInterval(1)))
        do { _ = try send(grant, "update", firstPath); fatalError("lost acknowledgement unexpectedly returned") } catch { }
        firstServer.waitUntilExit(); precondition(firstServer.terminationStatus == 0)
        let (secondServer, secondPath) = try spawn("second")
        defer { if secondServer.isRunning { secondServer.terminate() }; unlink(secondPath) }
        let recovered = try send(approved, "status", secondPath)
        precondition(recovered.accepted && recovered.policy?.windowId == approved.windowId && recovered.policy?.reliefRemainingSeconds == 0)
        let duplicate = try send(grant, "update", secondPath)
        precondition(duplicate.accepted && duplicate.policy?.windowId == approved.windowId && duplicate.policy?.reliefRemainingSeconds == 0)
        let recoveredExit = try send(grant, "synthetic-admin-recover", secondPath)
        precondition(recoveredExit.accepted && recoveredExit.policy?.state == .exit && recoveredExit.policy?.reliefRemainingSeconds == 0)
        secondServer.waitUntilExit(); precondition(secondServer.terminationStatus == 0)
        let (thirdServer, thirdPath) = try spawn("terminal")
        defer { if thirdServer.isRunning { thirdServer.terminate() }; unlink(thirdPath) }
        let terminal = try send(approved, "status", thirdPath)
        precondition(terminal.policy?.state == .exit && terminal.policy?.windowId == approved.windowId && terminal.policy?.reliefRemainingSeconds == 0)
        var reset = approved; reset.windowId = UUID()
        let rejected = try send(reset, "publish", thirdPath); precondition(!rejected.accepted)
        thirdServer.waitUntilExit(); precondition(thirdServer.terminationStatus == 0)
        print("Prepared ledger → fake socket → lost ACK → restart/status/duplicate → terminal tombstone/restart passed; no root or personal apps.")
    }
    static func server(socketPath: String, dir path: String, mode: String) throws {
        let dir = URL(fileURLWithPath: path), data = try Data(contentsOf: dir.appendingPathComponent("root-config.json"))
        let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let approved = try decoder.decode(EnrollmentBinding.self, from: data).approvedWindow
        let ready = try JSONDecoder().decode(EnrollmentReady.self, from: Data(contentsOf: dir.appendingPathComponent("ready.json")))
        let store = CandidateDiskStore(directory: dir, expectedOwner: getuid()); var disk = try store.load()
        guard ready.trustDigest == digest, ready.policyRevisionId == approved.policyRevisionId, disk.matches(approved: approved, trustDigest: digest) else { throw RootSocketFailure.invalidResponse }
        if disk.policy.state == .inactive { disk.policy.state = .active; disk.bootID = "fixture"; disk.uptimeDeadline = 3600; try store.save(disk) }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); defer { close(fd); unlink(socketPath) }
        var address = try guardAddress(socketPath)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard fd >= 0, bound == 0, listen(fd, 4) == 0 else { throw RootSocketFailure.systemCall }
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        let core = WindowGuardV2(allowedTargets: Set(approved.targetRefs), trustedUID: getuid(), trustedHash: "fixture", requireCalendar: true)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        for index in 1...(mode == "second" ? 3 : 2) {
            let client = accept(fd, nil, nil); guard client >= 0 else { throw RootSocketFailure.systemCall }
            var uid: uid_t = 0, gid: gid_t = 0; guard getpeereid(client, &uid, &gid) == 0 else { throw RootSocketFailure.systemCall }
            let request = try decoder.decode(WindowGuardRequest.self, from: readRootFrame(client, timeout: 2))
            disk = try store.load(); guard disk.matches(approved: approved, trustDigest: digest) else { throw RootSocketFailure.invalidResponse }
            if mode == "second" && request.command == "synthetic-admin-recover" {
                disk.policy.state = .exit; disk.policy.reliefUntil = nil; disk.reliefUptimeDeadline = nil; try store.save(disk)
                let reply = WindowGuardReply(accepted: true, reason: "synthetic", windowId: disk.policy.windowId, enforcement: "released", policy: disk.policy)
                try writeRootFrame(encoder.encode(reply), fd: client, timeout: 2); close(client); continue
            }
            let (next, reply) = core.apply(request, current: disk.policy, peerUID: uid, peerHash: "fixture", now: approved.startsAt.addingTimeInterval(1), uptime: 100, boot: "fixture")
            if reply.accepted && request.command != "status", let next { disk.policy = next; try store.save(disk) }
            if mode == "first" && index == 2 { precondition(reply.accepted); close(client); continue }
            try writeRootFrame(encoder.encode(reply), fd: client, timeout: 2); close(client)
        }
    }
}

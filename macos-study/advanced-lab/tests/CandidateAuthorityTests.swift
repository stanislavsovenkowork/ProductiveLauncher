import Foundation
import Darwin
@main @MainActor enum CandidateAuthorityTests {
    static let target = "dev.stanislav.focusdeck.guard-fixture-two"
    static func main() async throws {
        let args = CommandLine.arguments
        if args.count == 4 && args[1] == "server" { try server(args[2], statePath: args[3]); return }
        guard let data = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], data.contains("focusdeck") else { fatalError("isolated data required") }
        let dir = URL(fileURLWithPath: data); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let socket = "/tmp/focusdeck-authority-" + String(UUID().uuidString.prefix(8))
        let process = Process(); process.executableURL = URL(fileURLWithPath: args[0]); process.arguments = ["server", socket, dir.appendingPathComponent("server.json").path]
        let output = Pipe(); process.standardOutput = output; try process.run()
        defer { if process.isRunning { process.terminate() }; unlink(socket) }
        precondition(output.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        let now = Date(), revision = UUID()
        var local = WindowPolicy(policyRevisionId: revision, windowId: UUID(), localDate: "synthetic", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(600), reliefBudgetSeconds: 300, reliefRemainingSeconds: 300, reliefMaxGrantSeconds: 300, targetRefs: [target], state: .active)
        local.exitCoolingOffSeconds = 5
        let store = WindowPolicyStore(directory: dir); try store.save(local)
        let model = SessionModel(candidateBridge: WindowCandidateBridge(path: socket, expectedUID: getuid()))
        await model.syncCandidate()
        guard let adopted = model.windowPolicy else { fatalError("missing status") }
        precondition(model.candidateAuthoritative && adopted.windowId != local.windowId && adopted.reliefRemainingSeconds == 120)
        await model.candidateAction("relief") // fake service commits then drops response
        precondition(!model.candidateAuthoritative && model.candidateStatus.contains("неизвестно"))
        precondition(model.windowPolicy!.reliefRemainingSeconds == 120 && model.windowPolicy!.state == .active)
        await model.syncCandidate() // status discovers the committed change without re-granting
        precondition(model.candidateAuthoritative && model.windowPolicy!.reliefRemainingSeconds == 0 && model.windowPolicy!.state == .limitedRelief)
        let restarted = SessionModel(candidateBridge: WindowCandidateBridge(path: socket, expectedUID: getuid()))
        await restarted.syncCandidate()
        precondition(restarted.candidateAuthoritative && restarted.windowPolicy!.windowId == adopted.windowId && restarted.windowPolicy!.reliefRemainingSeconds == 0)
        await restarted.candidateAction("returned") // fake service explicitly rejects update
        precondition(!restarted.candidateAuthoritative && restarted.windowPolicy!.state == .limitedRelief)
        await restarted.syncCandidate()
        precondition(restarted.candidateAuthoritative && restarted.windowPolicy!.state == .limitedRelief && restarted.windowPolicy!.reliefRemainingSeconds == 0)
        process.waitUntilExit(); precondition(process.terminationStatus == 0)
        let committed = try JSONDecoder().decode(WindowPolicy.self, from: Data(contentsOf: dir.appendingPathComponent("server.json")))
        precondition(committed.windowId == adopted.windowId && committed.reliefRemainingSeconds == 0)
        print("Authoritative fake socket: different window ID, spent budget, commit+lost response, status recovery, UI model restart and refused return passed; no root or personal data.")
    }
    static func server(_ path: String, statePath: String) throws {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); defer { close(fd); unlink(path) }
        var address = try guardAddress(path)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard fd >= 0, bound == 0, listen(fd, 4) == 0 else { throw RootSocketFailure.systemCall }
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let core = WindowGuardV2(allowedTargets: [target], trustedUID: getuid(), trustedHash: "fixture")
        var current: WindowPolicy?
        for index in 1...8 {
            let client = accept(fd, nil, nil); guard client >= 0 else { throw RootSocketFailure.systemCall }
            var uid: uid_t = 0, gid: gid_t = 0; guard getpeereid(client, &uid, &gid) == 0 else { throw RootSocketFailure.systemCall }
            let request = try decoder.decode(WindowGuardRequest.self, from: readRootFrame(client, timeout: 2))
            if current == nil { var initial = request.policy; initial.windowId = UUID(); initial.reliefRemainingSeconds = 120; current = initial }
            if index == 7 {
                let refused = WindowGuardReply(accepted: false, reason: "synthetic_refusal", windowId: nil, enforcement: "unknown", policy: nil)
                try writeRootFrame(encoder.encode(refused), fd: client, timeout: 2); close(client); continue
            }
            let (next, reply) = core.apply(request, current: current, peerUID: uid, peerHash: "fixture", now: Date()); current = next
            if index == 3 {
                precondition(reply.accepted && reply.policy?.reliefRemainingSeconds == 0)
                try JSONEncoder().encode(current!).write(to: URL(fileURLWithPath: statePath), options: .atomic)
                close(client); continue // deliberately lose response after commit
            }
            try writeRootFrame(encoder.encode(reply), fd: client, timeout: 2); close(client)
        }
    }
}

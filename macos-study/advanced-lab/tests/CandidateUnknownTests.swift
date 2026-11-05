import Foundation
import Darwin
@main @MainActor enum CandidateUnknownTests {
    static let target = "dev.stanislav.focusdeck.guard-fixture-two"
    static func main() async throws {
        let args = CommandLine.arguments
        if args.count == 4 && args[1] == "server" { try server(args[2], mode: args[3]); return }
        guard let data = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], data.contains("focusdeck") else { fatalError("isolated data required") }
        let dir = URL(fileURLWithPath: data); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let now = Date(); let local = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "synthetic", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(600), reliefBudgetSeconds: 300, reliefRemainingSeconds: 300, reliefMaxGrantSeconds: 300, targetRefs: [target], state: .active)
        try WindowPolicyStore(directory: dir).save(local)
        for mode in ["empty", "conflict"] {
            let socket = "/tmp/focusdeck-unknown-" + String(UUID().uuidString.prefix(8))
            let process = Process(); process.executableURL = URL(fileURLWithPath: args[0]); process.arguments = ["server", socket, mode]
            let pipe = Pipe(); process.standardOutput = pipe; try process.run(); precondition(pipe.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
            let model = SessionModel(candidateBridge: WindowCandidateBridge(path: socket, expectedUID: getuid()))
            await model.syncCandidate()
            precondition(!model.candidateAuthoritative && model.windowPolicy?.windowId == local.windowId && model.windowPolicy?.reliefRemainingSeconds == 300)
            precondition(mode == "empty" ? model.candidateStatus.contains("не опубликована") : model.candidateStatus.contains("другой политике"))
            process.waitUntilExit(); precondition(process.terminationStatus == 0); unlink(socket)
        }
        print("Empty/conflicting fake helper yields unknown without publishing or resetting local state; no root or personal data.")
    }
    static func server(_ path: String, mode: String) throws {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); defer { close(fd); unlink(path) }
        var address = try guardAddress(path)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard fd >= 0, bound == 0, listen(fd, 4) == 0 else { throw RootSocketFailure.systemCall }
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        let client = accept(fd, nil, nil); guard client >= 0 else { throw RootSocketFailure.systemCall }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let request = try decoder.decode(WindowGuardRequest.self, from: readRootFrame(client, timeout: 2))
        precondition(request.command == "status")
        var policy: WindowPolicy? = nil
        if mode == "conflict" { var other = request.policy; other.policyRevisionId = UUID(); policy = other }
        let response = WindowGuardReply(accepted: true, reason: "observed", windowId: policy?.windowId, enforcement: "released", policy: policy)
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        try writeRootFrame(encoder.encode(response), fd: client, timeout: 2); close(client)
        var ready = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        precondition(poll(&ready, 1, 300) == 0) // no hidden follow-up publish
    }
}

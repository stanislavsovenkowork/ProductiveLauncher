import Foundation
import Darwin
@main @MainActor enum WindowCandidateModelTests {
    static let target = "dev.stanislav.focusdeck.guard-fixture-two"
    static func main() async throws {
        let args = CommandLine.arguments
        if args.count == 3 && args[1] == "server" { try server(args[2]); return }
        guard let data = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], data.contains("focusdeck") else { fatalError("isolated data required") }
        let socket = "/tmp/focusdeck-candidate-" + String(UUID().uuidString.prefix(8))
        let process = Process(); process.executableURL = URL(fileURLWithPath: args[0]); process.arguments = ["server", socket]
        let pipe = Pipe(); process.standardOutput = pipe; try process.run()
        defer { if process.isRunning { process.terminate() }; unlink(socket) }
        precondition(pipe.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        let model = SessionModel(candidateBridge: WindowCandidateBridge(path: socket, expectedUID: getuid()))
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let start = calendar.nextDate(after: Date(), matching: DateComponents(hour: 15, minute: 1), matchingPolicy: .nextTime)!
        model.windowStart = start; model.windowEnd = start.addingTimeInterval(3600)
        let weekday = ((calendar.component(.weekday, from: start) + 5) % 7) + 1; model.recurringWeekdays = [weekday]
        model.selectedProtectionTargets = [ProtectionTarget(id: target, name: "Synthetic")]; model.windowTargets = target
        model.saveWindow(); guard let policy = model.windowPolicy else { fatalError("policy not saved") }
        precondition(policy.targetRefs == [target]); await model.syncCandidate()
        guard let adopted = model.windowPolicy else { fatalError("no adopted state") }
        precondition(adopted.windowId != policy.windowId && adopted.reliefRemainingSeconds == 120)
        precondition(model.candidateStatus.contains("Помощник сообщил"))
        let trust = try JSONSerialization.jsonObject(with: Data(contentsOf: model.trustProposalURL)) as! [String: Any]
        precondition(trust["proposalStatus"] as? String == "local_uninstalled")
        precondition(trust["blockedSigningIDs"] as? [String] == [target] && (trust["blockedDomains"] as? [String])?.isEmpty == true)
        precondition(trust["policyRevisionId"] as? String == policy.policyRevisionId?.uuidString)
        let approved = trust["approvedWindow"] as! [String: Any]
        precondition(approved["windowId"] as? String == policy.windowId.uuidString && approved["policyRevisionId"] as? String == policy.policyRevisionId?.uuidString)
        precondition(approved["state"] as? String == "inactive" && approved["reliefRemainingSeconds"] as? Int == policy.reliefBudgetSeconds)
        print("Selected synthetic target → local trust proposal → authenticated fake status → adopt helper window ID and spent budget passed. No root or real app enforcement.")
    }
    static func server(_ path: String) throws {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); defer { close(fd); unlink(path) }
        var address = try guardAddress(path)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard fd >= 0, bound == 0, listen(fd, 4) == 0 else { throw RootSocketFailure.systemCall }
        FileHandle.standardOutput.write(Data("ready\n".utf8))
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let core = WindowGuardV2(allowedTargets: [target], trustedUID: getuid(), trustedHash: "fixture", requireCalendar: true)
        var current: WindowPolicy?
        while true {
            let client = accept(fd, nil, nil); guard client >= 0 else { continue }
            var uid: uid_t = 0, gid: gid_t = 0; guard getpeereid(client, &uid, &gid) == 0 else { close(client); continue }
            if let frame = try? readRootFrame(client, timeout: 2), let request = try? decoder.decode(WindowGuardRequest.self, from: frame) {
                if current == nil && request.command == "status" {
                    var existing = request.policy; existing.windowId = UUID(); existing.reliefRemainingSeconds = 120
                    current = existing
                }
                let (next, reply) = core.apply(request, current: current, peerUID: uid, peerHash: "fixture", now: Date()); current = next
                try? writeRootFrame(encoder.encode(reply), fd: client, timeout: 2)
            }
            close(client)
        }
    }
}

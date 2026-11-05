import Foundation
import Darwin

// Dedicated socket, not the historical v1 endpoint.
enum WindowGuardV2Client {
    #if PRODUCTION_GUARD_CANDIDATE
    static let path = "/var/run/dev.stanislav.focusdeck.guard-v2-candidate.sock"
    #else
    static let path = "/var/run/dev.stanislav.focusdeck.guard-v2-fixture.sock"
    #endif
    static func send(_ request: WindowGuardRequest, path: String = path, expectedUID: UInt32 = 0, expectedHash: String? = nil, requireHash: Bool = false) throws -> WindowGuardReply {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
        var address = try guardAddress(path)
        let result = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard result == 0 else { throw RootSocketFailure.systemCall }
        var uid: uid_t = 0, gid: gid_t = 0
        guard getpeereid(fd, &uid, &gid) == 0, uid == expectedUID else { throw RootSocketFailure.systemCall }
        if requireHash {
            var pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
            guard let expectedHash, getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
                  RootSocketServer.verifiedHash(pid: pid) == expectedHash else { throw RootSocketFailure.invalidResponse }
        }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        try writeRootFrame(encoder.encode(request), fd: fd, timeout: 2)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(WindowGuardReply.self, from: readRootFrame(fd, timeout: 2))
    }
}

actor WindowFixtureBridge {
    private let path: String
    private let expectedUID: UInt32
    init(path: String = WindowGuardV2Client.path, expectedUID: UInt32 = 0) { self.path = path; self.expectedUID = expectedUID }
    func request(_ policy: WindowPolicy, command: String) -> WindowGuardReply? {
        try? WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: command), path: path, expectedUID: expectedUID)
    }
    func send(_ policy: WindowPolicy) -> (String, Bool) {
        do {
            let reply = try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: "publish"), path: path, expectedUID: expectedUID)
            guard reply.accepted else { return ("Тестовый помощник отклонил запрос: \(reply.reason). Защита не подтверждена.", false) }
            return (reply.enforcement == "selected_apps_requested" ? "Тестовый помощник принял правило fixture; результат действия ещё не подтверждён." : "Тестовый помощник принял освобождение fixture. Личные приложения и сайты не блокируются.", true)
        } catch { return ("Тестовый помощник недоступен. Цели не считаются защищёнными.", false) }
    }
}

actor WindowCandidateBridge {
    #if USER_GUARD_QA
    static let candidatePath = "/tmp/focusdeck-user-protection-qa-\(getuid()).sock"
    #elseif USER_GUARD_MAIN
    static let candidatePath = "/tmp/focusdeck-user-protection-\(getuid()).sock"
    #else
    static let candidatePath = "/var/run/dev.stanislav.focusdeck.guard-v2-candidate.sock"
    #endif
    private let path: String
    private let expectedUID: UInt32
    private let expectedHash: String?
    private let requireHash: Bool
    #if USER_GUARD_MAIN
    init(path: String? = nil, expectedUID: UInt32 = getuid(), expectedHash: String? = nil) {
        self.path = path ?? Self.candidatePath; self.expectedUID = expectedUID; self.requireHash = path == nil || expectedHash != nil
        self.expectedHash = path == nil ? RootSocketServer.verifiedHash(at: Bundle.main.bundleURL.appendingPathComponent("Contents/Library/HelperTools/UserProtectionAgent")) : expectedHash
    }
    #else
    init(path: String = candidatePath, expectedUID: UInt32 = 0) { self.path = path; self.expectedUID = expectedUID; self.expectedHash = nil; self.requireHash = false }
    #endif
    func request(_ policy: WindowPolicy, command: String, requirements: [TrustedSigningRequirement]? = nil, schedule: WindowSchedule? = nil) -> WindowGuardReply? {
        try? WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: command, signingRequirements: requirements, schedule: schedule), path: path, expectedUID: expectedUID, expectedHash: expectedHash, requireHash: requireHash)
    }
}

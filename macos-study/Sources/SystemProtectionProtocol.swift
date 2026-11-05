import Foundation
import Darwin

enum SystemProtectionPaths {
    static let directory = "/Library/Application Support/FocusDeckProtection"
    static let socket = "/var/run/dev.stanislav.focusdeck.protection.sock"
    static let helper = "/Library/PrivilegedHelperTools/FocusDeckProtection"
    static let label = "dev.stanislav.focusdeck.protection"
}

struct SystemProtectionRequest: Codable, Sendable {
    let command: String
    var seconds: Int = 3600
    var domains: [String] = []
    var applications: [TrustedSigningRequirement] = []
}

// Durable evidence from the actual service boot, retained after expiry.
struct SystemProtectionRecovery: Codable, Sendable, Equatable {
    var windowID: UUID
    var previousBoot: String
    var currentBoot: String
    var restoredAt: Date
    var secondsRemaining: Int
    var exitSecondsRemaining: Int?
    var domains: [String]
    var applicationCount: Int
    var networkReady = false
    var dnsReady = false
    var browserReady = false
    var confirmedAt: Date?
    var error: String?
}

struct SystemProtectionReply: Codable, Sendable {
    var accepted: Bool
    var message: String
    var active: Bool = false
    var secondsRemaining: Int = 0
    var domains: [String] = []
    var applicationCount: Int = 0
    var networkActive: Bool = false
    var blockedAddressCount: Int = 0
    var validationAvailable: Bool = false
    var servicePID: Int32 = 0
    var dnsActive: Bool = false
    var dnsDeniedQueries: Int = 0
    var managedBrowserPID: Int32?
    var managedBrowserOnly: Bool = false
    var exitSecondsRemaining: Int?
    var bootResumes: Int? = nil
    var recoveredFromBackup: Bool? = nil
    var windowID: UUID? = nil
    var lastBootRecovery: SystemProtectionRecovery? = nil
    var effectiveSecondsRemaining: Int { min(secondsRemaining, exitSecondsRemaining ?? secondsRemaining) }
}

enum SystemProtectionClient {
    static func send(_ request: SystemProtectionRequest, expectedHash: String) throws -> SystemProtectionReply {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw RootSocketFailure.systemCall }
        defer { close(fd) }
        var address = try guardAddress(SystemProtectionPaths.socket)
        let result = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        guard result == 0 else { throw RootSocketFailure.systemCall }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0
        var size = socklen_t(MemoryLayout<Int32>.size)
        guard getpeereid(fd, &uid, &gid) == 0, uid == 0,
              getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
              RootSocketServer.verifiedHash(pid: pid) == expectedHash else { throw RootSocketFailure.invalidResponse }
        try writeRootFrame(JSONEncoder().encode(request), fd: fd, timeout: 2)
        return try JSONDecoder().decode(SystemProtectionReply.self, from: readRootFrame(fd, timeout: 3))
    }
}

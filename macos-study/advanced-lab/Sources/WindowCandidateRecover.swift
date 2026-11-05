import Foundation
import Darwin
import CryptoKit

private struct RecoveryBinding: Decodable { let approvedWindow: WindowPolicy }
private struct RecoveryReady: Decodable { let trustDigest: String; let policyRevisionId: UUID }

// Independent of FocusDeck.app and its CDHash. Root-only explicit recovery of
// this candidate's own policy, not a generic service or application controller.
@main enum WindowCandidateRecover {
    static func main() throws {
        guard CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--recover", geteuid() == 0 else { throw Failure.invalid }
        let dir = WindowCandidateRecoveryCore.directory
        var info = stat()
        guard lstat(dir, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.invalid }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
        var address = try guardAddress(WindowCandidateRecoveryCore.socket)
        let connected = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        if connected == 0 {
            var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0
            var size = socklen_t(MemoryLayout<Int32>.size)
            guard getpeereid(fd, &uid, &gid) == 0, uid == 0,
                  getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0, pid > 1 else { throw Failure.invalid }
            var pathBytes = [UInt8](repeating: 0, count: Int(MAXPATHLEN) * 4)
            guard proc_pidpath(pid, &pathBytes, UInt32(pathBytes.count)) > 0 else { throw Failure.invalid }
            let observed = String(decoding: pathBytes.prefix { $0 != 0 }, as: UTF8.self)
            let expected = dir + "/FocusDeckV2ProductionCandidate"
            guard URL(fileURLWithPath: observed).resolvingSymlinksInPath().path == URL(fileURLWithPath: expected).resolvingSymlinksInPath().path,
                  lstat(expected, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0 else { throw Failure.invalid }
            try writeRootFrame(WindowCandidateRecoveryCore.command, fd: fd, timeout: 2)
            guard try readRootFrame(fd, timeout: 2) == WindowCandidateRecoveryCore.reply else { throw Failure.invalid }
            print("Candidate terminal tombstone saved and daemon stopped; other services were not changed."); return
        }
        // Offline recovery is permitted only when no daemon holds its own lock.
        let lock = open(dir + "/daemon.lock", O_RDWR | O_NOFOLLOW | O_CLOEXEC)
        guard lock >= 0 else { throw Failure.invalid }; defer { close(lock) }
        var lockInfo = stat()
        guard fstat(lock, &lockInfo) == 0, lockInfo.st_uid == 0, lockInfo.st_mode & S_IFMT == S_IFREG,
              lockInfo.st_mode & 0o077 == 0, flock(lock, LOCK_EX | LOCK_NB) == 0 else { throw Failure.invalid }
        func privateData(_ name: String) throws -> Data {
            let path = dir + "/" + name; var details = stat()
            guard lstat(path, &details) == 0, details.st_uid == 0, details.st_mode & S_IFMT == S_IFREG,
                  details.st_mode & 0o077 == 0, details.st_size <= 64 * 1024 else { throw Failure.invalid }
            return try Data(contentsOf: URL(fileURLWithPath: path))
        }
        let config = try privateData("root-config.json"), readyData = try privateData("ready.json")
        let digest = SHA256.hash(data: config).map { String(format: "%02x", $0) }.joined()
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let approved = try decoder.decode(RecoveryBinding.self, from: config).approvedWindow
        let ready = try JSONDecoder().decode(RecoveryReady.self, from: readyData)
        let store = CandidateDiskStore(directory: URL(fileURLWithPath: dir), expectedOwner: 0)
        var state = try store.load() // absent or corrupt never means a new budget
        guard ready.trustDigest == digest, ready.policyRevisionId == approved.policyRevisionId,
              state.matches(approved: approved, trustDigest: digest) else { throw Failure.invalid }
        state.policy.state = .exit; state.policy.reliefUntil = nil; state.policy.exitAvailableAt = nil
        state.policy.exitUptimeDeadline = nil; state.policy.exitBootID = nil; state.policy.stateBeforeExitPending = nil
        state.reliefUptimeDeadline = nil
        try store.save(state)
        print("Offline candidate terminal tombstone saved; no daemon was running and other services were not changed.")
    }
    enum Failure: Error { case invalid }
}

import Foundation
import Darwin
import Security

struct GuardSession: Codable, Equatable {
    let schemaVersion: Int
    let sessionId: UUID
    let targetUID: UInt32
    let createdAt: Date
    var expiresAt: Date
    let blockedSigningIDs: [String]
    var pauseUntil: Date?
    var pauseUsed: Bool = false
    var pauseGrantSeconds: Double = 0
    var blockedDomains: [String] = []
    var protectedDomains: [String] = ["gitlab.eskhata.com", "openai.com", "chatgpt.com"]
    var hostsEnabled: Bool = false
    func validate(now: Date) throws {
        guard schemaVersion == 1, targetUID >= 500, createdAt <= now,
              expiresAt > now, expiresAt.timeIntervalSince(createdAt) <= 25 * 60 + pauseGrantSeconds,
              pauseGrantSeconds.isFinite, pauseGrantSeconds >= 0, pauseGrantSeconds <= 300,
              (pauseUsed || pauseGrantSeconds == 0),
              expiresAt.timeIntervalSince(createdAt) > 0,
              !blockedSigningIDs.isEmpty,
              !blockedSigningIDs.contains(where: StudyPolicy.isInfrastructure) else { throw GuardFailure.invalidSession }
        try StudyPolicy(blockedDomains: blockedDomains, protectedDomains: protectedDomains, blockedAppBundleIds: blockedSigningIDs).validate()
        if let pauseUntil { guard pauseUntil <= expiresAt, pauseUntil.timeIntervalSince(now) <= 300 else { throw GuardFailure.invalidSession } }
    }
    enum GuardFailure: Error { case invalidSession }
}

struct ProcessIdentity: Equatable {
    let pid: Int32
    let uid: UInt32
    let parent: UInt32
    let startSeconds: UInt64
    let startMicros: UInt64
    let signingID: String?
    static func basic(_ pid: Int32) -> ProcessIdentity? {
        var info = proc_bsdinfo()
        let length = Int32(MemoryLayout<proc_bsdinfo>.size)
        guard proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &info, length) == length else { return nil }
        return ProcessIdentity(pid: pid, uid: info.pbi_uid, parent: info.pbi_ppid,
                               startSeconds: info.pbi_start_tvsec, startMicros: info.pbi_start_tvusec, signingID: nil)
    }
    static func inspect(_ pid: Int32) -> ProcessIdentity? {
        guard let basic = basic(pid) else { return nil }
        var code: SecCode?
        let attributes = [kSecGuestAttributePid: NSNumber(value: pid)] as CFDictionary
        var signingID: String?
        if SecCodeCopyGuestWithAttributes(nil, attributes, [], &code) == errSecSuccess, let code,
           SecCodeCheckValidity(code, [], nil) == errSecSuccess {
            var staticCode: SecStaticCode?
            if SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode {
                var details: CFDictionary?
                if SecCodeCopySigningInformation(staticCode, [], &details) == errSecSuccess,
                   let map = details as? [String: Any] { signingID = map[kSecCodeInfoIdentifier as String] as? String }
            }
        }
        return ProcessIdentity(pid: pid, uid: basic.uid, parent: basic.parent,
                               startSeconds: basic.startSeconds, startMicros: basic.startMicros, signingID: signingID)
    }
    static func pids() -> [Int32] {
        let estimated = proc_listallpids(nil, 0)
        guard estimated > 0 else { return [] }
        var pids = [Int32](repeating: 0, count: Int(estimated) + 128)
        let capacity = Int32(pids.count * MemoryLayout<Int32>.size)
        let count = pids.withUnsafeMutableBytes { proc_listallpids($0.baseAddress, capacity) }
        return Array(pids.prefix(max(0, min(Int(count), pids.count))))
    }
}

// Only target-UID metadata reaches expensive signing lookup. Short cache bounds
// same-PID exec changes; every signal still performs a fresh full identity check.
struct GuardProcessScanner {
    private struct Entry { let basic: ProcessIdentity; let path: String; let identity: ProcessIdentity; let until: TimeInterval }
    private var cached: [Int32: Entry] = [:]
    private var bundleIDs: [String: String] = [:]
    mutating func scan(uid: UInt32, targets: Set<String>, uptime: TimeInterval) -> [ProcessIdentity] {
        var seen = Set<Int32>()
        var result: [ProcessIdentity] = []
        for pid in ProcessIdentity.pids() {
            guard let basic = ProcessIdentity.basic(pid), basic.uid == uid else { continue }
            seen.insert(pid)
            var bytes = [UInt8](repeating: 0, count: Int(MAXPATHLEN) * 4)
            let length = proc_pidpath(pid, &bytes, UInt32(bytes.count))
            let path = length > 0 ? String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self) : ""
            let fixture = targets.contains("dev.stanislav.focusdeck.guard-fixture") && (path as NSString).lastPathComponent == "GuardFixture"
            var candidate = fixture
            if let range = path.range(of: ".app/Contents/MacOS/", options: .backwards) {
                let bundlePath = String(path[..<range.lowerBound]) + ".app"
                let id: String
                if let known = bundleIDs[bundlePath] { id = known }
                else { id = Bundle(url: URL(fileURLWithPath: bundlePath))?.bundleIdentifier ?? ""; bundleIDs[bundlePath] = id }
                candidate = candidate || targets.contains(id)
            }
            guard candidate else { continue }
            if let entry = cached[pid], entry.basic == basic, entry.path == path, uptime < entry.until {
                result.append(entry.identity)
            } else if let identity = ProcessIdentity.inspect(pid) {
                cached[pid] = Entry(basic: basic, path: path, identity: identity, until: uptime + 5)
                result.append(identity)
            }
        }
        cached = cached.filter { seen.contains($0.key) }
        return result
    }
}

struct RootGuardCore {
    let session: GuardSession
    let deadline: TimeInterval
    let pauseDeadline: TimeInterval
    init(session: GuardSession, now: Date, uptime: TimeInterval, maximumDeadline: TimeInterval? = nil) throws {
        try session.validate(now: now)
        self.session = session
        deadline = min(maximumDeadline ?? .infinity, uptime + session.expiresAt.timeIntervalSince(now))
        pauseDeadline = uptime + min(300, max(0, session.pauseUntil?.timeIntervalSince(now) ?? 0))
    }
    func enforcing(now: Date, uptime: TimeInterval) -> Bool {
        now < session.expiresAt && uptime < deadline &&
            (session.pauseUntil.map({ now >= $0 || uptime >= pauseDeadline }) ?? true)
    }
    func targets(supplying processes: () -> [ProcessIdentity], now: Date, uptime: TimeInterval) -> [ProcessIdentity] {
        guard enforcing(now: now, uptime: uptime) else { return [] }
        return targets(processes(), now: now, uptime: uptime)
    }
    func targets(_ processes: [ProcessIdentity], now: Date, uptime: TimeInterval) -> [ProcessIdentity] {
        guard enforcing(now: now, uptime: uptime) else { return [] }
        return processes.filter { process in
            guard process.pid > 1, process.pid != getpid(), process.uid == session.targetUID,
                  let id = process.signingID, !StudyPolicy.isInfrastructure(id) else { return false }
            return session.blockedSigningIDs.contains(id)
        }
    }
    // Recheck start time and identity immediately before sending a signal. No raw
    // stored PID is trusted across polls (PIDs can be reused by essential processes).
    static func terminate(_ expected: ProcessIdentity, inspect: (Int32) -> ProcessIdentity? = ProcessIdentity.inspect,
                          signal: (Int32, Int32) -> Int32 = Darwin.kill, force: Bool = false) -> Bool {
        guard inspect(expected.pid) == expected else { return false }
        return signal(expected.pid, force ? SIGKILL : SIGTERM) == 0
    }
}


struct RootSessionDisk {
    static let directory = "/Library/Application Support/FocusDeckGuard"
    static let path = directory + "/session.json"
    static func load() throws -> GuardSession? {
        var directoryInfo = stat()
        guard lstat(directory, &directoryInfo) == 0 else {
            if errno == ENOENT { return nil }; throw DiskFailure.unsafeOwnership
        }
        guard directoryInfo.st_uid == 0, directoryInfo.st_mode & S_IFMT == S_IFDIR,
              directoryInfo.st_mode & 0o077 == 0 else { throw DiskFailure.unsafeOwnership }
        let fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { if errno == ENOENT { return nil }; throw DiskFailure.unsafeOwnership }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        defer { try? handle.close() }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_uid == 0,
              info.st_mode & 0o077 == 0, info.st_size <= 64 * 1024 else { throw DiskFailure.unsafeOwnership }
        return try JSONDecoder().decode(GuardSession.self, from: handle.readToEnd() ?? Data())
    }
    static func write(_ session: GuardSession) throws {
        try session.validate(now: Date())
        let url = URL(fileURLWithPath: path)
        guard try loadDirectoryIsPrivate() else { throw DiskFailure.unsafeOwnership }
        try JSONEncoder().encode(session).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
    }
    static func loadDirectoryIsPrivate() throws -> Bool {
        var info = stat()
        return lstat(directory, &info) == 0 && info.st_uid == 0 && info.st_mode & S_IFMT == S_IFDIR && info.st_mode & 0o077 == 0
    }
    static func loadTrust() throws -> RootTrust {
        guard try loadDirectoryIsPrivate() else { throw DiskFailure.unsafeOwnership }
        let url = URL(fileURLWithPath: directory + "/root-config.json")
        var info = stat()
        guard lstat(url.path, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_mode & 0o077 == 0, info.st_size < 64 * 1024 else { throw DiskFailure.unsafeOwnership }
        let trust = try JSONDecoder().decode(RootTrust.self, from: Data(contentsOf: url))
        try trust.validate(); return trust
    }
    enum DiskFailure: Error { case unsafeOwnership }
}

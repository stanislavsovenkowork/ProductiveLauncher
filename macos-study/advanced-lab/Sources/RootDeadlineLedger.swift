import Foundation
import Darwin

struct RootDeadlineLedger: Codable {
    let service: String
    let bootID: String
    var deadlines: [String: Double]
    static func bootIdentifier() throws -> String {
        var length = 0
        guard sysctlbyname("kern.bootsessionuuid", nil, &length, nil, 0) == 0, length > 1, length < 256 else { throw Failure.bootID }
        var bytes = [CChar](repeating: 0, count: length)
        guard sysctlbyname("kern.bootsessionuuid", &bytes, &length, nil, 0) == 0 else { throw Failure.bootID }
        return String(decoding: bytes.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }
    static func load() throws -> RootDeadlineLedger {
        let boot = try bootIdentifier()
        let url = URL(fileURLWithPath: RootSessionDisk.directory + "/deadline-ledger.json")
        var info = stat()
        if lstat(url.path, &info) != 0 {
            if errno == ENOENT { return RootDeadlineLedger(service: "dev.stanislav.focusdeck.guard", bootID: boot, deadlines: [:]) }
            throw Failure.unsafeFile
        }
        guard info.st_uid == 0, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 4 * 1024 * 1024 else { throw Failure.unsafeFile }
        let saved = try JSONDecoder().decode(RootDeadlineLedger.self, from: Data(contentsOf: url))
        guard saved.service == "dev.stanislav.focusdeck.guard" else { throw Failure.unsafeFile }
        // A new boot cannot reuse a prior uptime epoch. UTC expiry still bounds
        // remaining policy; clock/reboot tamper behavior requires live acceptance.
        return saved.bootID == boot ? saved : RootDeadlineLedger(service: saved.service, bootID: boot, deadlines: [:])
    }
    mutating func prune(uptime: TimeInterval, protecting id: UUID?) {
        let protected = id?.uuidString
        // Current policy's cap survives clock changes/reloads. Non-current expired
        // sessions cannot be restored by ordinary IPC; keep a bounded recent tail.
        let expired = deadlines.filter { $0.key != protected && $0.value + 300 < uptime }.sorted { $0.value > $1.value }
        let keep = Set(expired.prefix(256).map(\.key))
        deadlines = deadlines.filter { $0.key == protected || $0.value + 300 >= uptime || keep.contains($0.key) }
    }
    func save() throws {
        guard try RootSessionDisk.loadDirectoryIsPrivate(), deadlines.count <= 32768 else { throw Failure.unsafeFile }
        let url = URL(fileURLWithPath: RootSessionDisk.directory + "/deadline-ledger.json")
        try JSONEncoder().encode(self).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    enum Failure: Error { case bootID, unsafeFile }
}

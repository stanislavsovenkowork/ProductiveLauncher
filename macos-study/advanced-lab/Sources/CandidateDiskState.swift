import Foundation
import Darwin

// Single authoritative one-window ledger. Missing/legacy/corrupt files are errors.
struct CandidateDiskState: Codable, Equatable {
    var schemaVersion: Int = 2
    var policy: WindowPolicy
    var bootID: String
    var uptimeDeadline: Double
    var reliefUptimeDeadline: Double?
    var trustDigest: String
    func matches(approved: WindowPolicy, trustDigest expected: String) -> Bool {
        schemaVersion == 2 && trustDigest == expected && policy.windowId == approved.windowId &&
        policy.policyRevisionId == approved.policyRevisionId && policy.targetRefs == approved.targetRefs &&
        policy.timezone == approved.timezone && policy.localDate == approved.localDate && policy.startsAt == approved.startsAt && policy.endsAt == approved.endsAt &&
        policy.reliefBudgetSeconds == approved.reliefBudgetSeconds && policy.reliefMaxGrantSeconds == approved.reliefMaxGrantSeconds &&
        policy.exitCoolingOffSeconds == approved.exitCoolingOffSeconds && (0...approved.reliefBudgetSeconds).contains(policy.reliefRemainingSeconds) &&
        uptimeDeadline.isFinite && uptimeDeadline >= 0 && !bootID.isEmpty
    }
}
struct CandidateDiskStore {
    let directory: URL
    let expectedOwner: UInt32
    var file: URL { directory.appendingPathComponent("window-v2-candidate.json") }
    func validateDirectory() throws {
        var info = stat()
        guard lstat(directory.path, &info) == 0, info.st_uid == expectedOwner, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.unsafe }
    }
    func load() throws -> CandidateDiskState {
        try validateDirectory()
        let fd = open(file.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); guard fd >= 0 else { throw Failure.unsafe }; defer { close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == expectedOwner, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 64 * 1024 else { throw Failure.unsafe }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(CandidateDiskState.self, from: FileHandle(fileDescriptor: fd, closeOnDealloc: false).readToEnd() ?? Data())
    }
    func save(_ value: CandidateDiskState, requireExisting: Bool = true) throws {
        try validateDirectory()
        var previous = stat()
        if requireExisting {
            guard lstat(file.path, &previous) == 0, previous.st_uid == expectedOwner,
                  previous.st_mode & S_IFMT == S_IFREG, previous.st_mode & 0o077 == 0 else { throw Failure.unsafe }
        }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(value), temp = directory.appendingPathComponent(".candidate-" + UUID().uuidString)
        let fd = open(temp.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw Failure.unsafe }; defer { close(fd); unlink(temp.path) }
        try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
        guard fsync(fd) == 0 else { throw Failure.unsafe }
        if requireExisting {
            var current = stat()
            guard lstat(file.path, &current) == 0, current.st_uid == expectedOwner, current.st_mode & S_IFMT == S_IFREG,
                  current.st_mode & 0o077 == 0, current.st_dev == previous.st_dev, current.st_ino == previous.st_ino else { throw Failure.unsafe }
        }
        guard rename(temp.path, file.path) == 0 else { throw Failure.unsafe }
        let dirFD = open(directory.path, O_RDONLY | O_CLOEXEC); guard dirFD >= 0 else { throw Failure.unsafe }; defer { close(dirFD) }
        // If this fails, outcome is unknown and no caller may acknowledge success.
        guard fsync(dirFD) == 0 else { throw Failure.unsafe }
    }
    enum Failure: Error { case unsafe }
}

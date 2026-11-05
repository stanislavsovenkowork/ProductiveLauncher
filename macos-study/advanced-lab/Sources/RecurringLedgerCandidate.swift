import Foundation
import Darwin
import CryptoKit

// Isolated prototype. It is not connected to the installed app or daemon.
// A policy becomes visible to the caller only after its complete snapshot is committed.
struct RecurringLedgerCandidate: Codable {
    struct Clock: Codable, Equatable { var wall: Date; var uptime: Double; var bootID: String }
    enum Outcome: Equatable { case noWindow, window(WindowPolicy), clockUnknown }
    var schedule: WindowSchedule
    var trustDigest: String
    var latestObservedDate: String?
    var issuedLocalDate: String?
    var policy: WindowPolicy?
    var anchor: Clock
    var clockUnknown = false

    static func localDate(_ now: Date) -> String {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let x = cal.dateComponents([.year, .month, .day], from: now)
        return String(format: "%04d-%02d-%02d", x.year!, x.month!, x.day!)
    }
    static func valid(_ schedule: WindowSchedule, trustDigest: String) -> Bool {
        schedule.policyRevisionId != nil && trustDigest.count == 64 && trustDigest.allSatisfy(\.isHexDigit) &&
        !schedule.weekdays.isEmpty && Set(schedule.weekdays).count == schedule.weekdays.count && schedule.weekdays.allSatisfy { (1...7).contains($0) } &&
        schedule.startMinute >= 900 && schedule.endMinute > schedule.startMinute && schedule.endMinute < 1440 &&
        !schedule.targetRefs.isEmpty && Set(schedule.targetRefs).count == schedule.targetRefs.count &&
        schedule.targetRefs.allSatisfy { WindowGuardV2.validSigningID($0) && !WindowGuardV2.isProtected($0) } &&
        (0...3600).contains(schedule.reliefBudgetSeconds) && (1...900).contains(schedule.reliefMaxGrantSeconds) && (1...120).contains(schedule.exitCoolingOffSeconds)
    }
    static func windowID(revision: UUID, date: String) -> UUID {
        let digest = Array(SHA256.hash(data: Data((revision.uuidString + ":" + date).utf8)))
        var bytes = Array(digest.prefix(16)); bytes[6] = (bytes[6] & 0x0f) | 0x50; bytes[8] = (bytes[8] & 0x3f) | 0x80
        let raw = bytes.map { String(format: "%02x", $0) }.joined()
        let hex = Array(raw)
        return UUID(uuidString: String(hex[0..<8]) + "-" + String(hex[8..<12]) + "-" + String(hex[12..<16]) + "-" + String(hex[16..<20]) + "-" + String(hex[20..<32]))!
    }
    mutating func reconcile(now: Date, uptime: Double, bootID: String, trustDigest expected: String) throws -> Outcome {
        guard Self.valid(schedule, trustDigest: trustDigest), trustDigest == expected, !bootID.isEmpty, uptime.isFinite, uptime >= 0 else { throw Failure.invalid }
        if let current = policy {
            let terminal = current.state == .exit || current.state == .expired
            guard current.policyVersion == 2, current.targetRefs == schedule.targetRefs,
                  current.localDate == issuedLocalDate, current.startsAt < current.endsAt,
                  current.localDate == Self.localDate(current.startsAt), current.localDate == Self.localDate(current.endsAt),
                  current.reliefBudgetSeconds >= 0, (0...current.reliefBudgetSeconds).contains(current.reliefRemainingSeconds),
                  terminal || (current.policyRevisionId == schedule.policyRevisionId && current.reliefBudgetSeconds == schedule.reliefBudgetSeconds && current.reliefMaxGrantSeconds == schedule.reliefMaxGrantSeconds && current.exitCoolingOffSeconds == schedule.exitCoolingOffSeconds) else { throw Failure.invalid }
        }
        if clockUnknown { return .clockUnknown }
        let delta = uptime - anchor.uptime, wallDelta = now.timeIntervalSince(anchor.wall)
        guard bootID == anchor.bootID, delta >= 0, abs(wallDelta - delta) <= 2 else { clockUnknown = true; return .clockUnknown }
        let today = Self.localDate(now)
        guard latestObservedDate.map({ today >= $0 }) ?? true else { clockUnknown = true; return .clockUnknown }
        anchor = Clock(wall: now, uptime: uptime, bootID: bootID); latestObservedDate = today
        if var current = policy {
            if now >= current.endsAt && current.state != .exit { current.state = .expired; current.reliefUntil = nil; policy = current }
            if current.localDate == today { return .window(current) }
            guard current.state == .exit || current.state == .expired else { throw Failure.activePreviousWindow }
        }
        // A terminal instance cannot be silently re-created, even after edits.
        if issuedLocalDate == today { return .noWindow }
        guard var window = schedule.window(on: now), now >= window.startsAt else { return .noWindow }
        window.windowId = Self.windowID(revision: schedule.policyRevisionId!, date: today)
        _ = window.reconcile(now: now)
        issuedLocalDate = today; policy = window
        return .window(window)
    }
    mutating func replaceSchedule(_ replacement: WindowSchedule) throws {
        guard Self.valid(replacement, trustDigest: trustDigest), replacement.targetRefs == schedule.targetRefs else { throw Failure.invalid }
        if let p = policy, p.state != .exit && p.state != .expired { throw Failure.activePreviousWindow }
        schedule = replacement // issuedLocalDate and consumed state remain independently retained.
    }
    enum Failure: Error { case invalid, activePreviousWindow, unsafeStorage }
}

struct RecurringLedgerStore {
    let directory: URL
    let expectedOwner: UInt32
    var file: URL { directory.appendingPathComponent("recurring-ledger-prototype.json") }
    func validateDirectory() throws {
        var info = stat()
        guard lstat(directory.path, &info) == 0, info.st_uid == expectedOwner, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }
    }
    func load() throws -> RecurringLedgerCandidate {
        try validateDirectory()
        let fd = open(file.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }; defer { close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == expectedOwner, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 64 * 1024 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(RecurringLedgerCandidate.self, from: handle.readToEnd() ?? Data())
    }
    func save(_ ledger: RecurringLedgerCandidate) throws {
        try validateDirectory()
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(ledger)
        let temp = directory.appendingPathComponent(".ledger-" + UUID().uuidString)
        let fd = open(temp.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }
        defer { close(fd); unlink(temp.path) }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        try handle.write(contentsOf: data); guard fsync(fd) == 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }
        guard rename(temp.path, file.path) == 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }
        let dirFD = open(directory.path, O_RDONLY | O_CLOEXEC)
        guard dirFD >= 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }; defer { close(dirFD) }
        guard fsync(dirFD) == 0 else { throw RecurringLedgerCandidate.Failure.unsafeStorage }
    }
    // No mutation is returned until save succeeds; an existing corrupt file is never replaced on load.
    func transition(now: Date, uptime: Double, bootID: String, trustDigest: String) throws -> RecurringLedgerCandidate.Outcome {
        var state = try load()
        let outcome = try state.reconcile(now: now, uptime: uptime, bootID: bootID, trustDigest: trustDigest)
        try save(state); return outcome
    }
}

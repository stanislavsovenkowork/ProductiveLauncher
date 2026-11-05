import Foundation
import Darwin
import CryptoKit

// One user-owned authoritative record: selected identities and budget commit together.
// This component is not the installed root candidate and never touches network settings.
struct UserProtectionLedger: Codable, Equatable {
    let schemaVersion: Int
    var policy: WindowPolicy
    var signingRequirements: [TrustedSigningRequirement]
    var schedule: WindowSchedule? = nil
    var lastIssuedLocalDate: String? = nil
    var clockAnchor: ClockAnchor? = nil
    var clockUncertain: Bool? = nil
    struct ClockAnchor: Codable, Equatable { let wall: Date; let uptime: Double; let boot: String }
    func valid(allowSynthetic: Bool = false) -> Bool {
        guard schemaVersion == 1, policy.policyVersion == 2, policy.policyRevisionId != nil,
              (!signingRequirements.isEmpty || !(policy.siteDomains ?? []).isEmpty), signingRequirements.count <= 64,
              !(policy.siteSubdomains == true && policy.siteDomains == nil), SiteDomainPolicy.validList(policy.siteDomains, includeSubdomains: policy.siteSubdomains == true),
              Set(signingRequirements.map(\.signingID)).count == signingRequirements.count,
              Set(signingRequirements.map(\.signingID)) == Set(policy.targetRefs), Set(policy.targetRefs).count == policy.targetRefs.count,
              (0...3600).contains(policy.reliefBudgetSeconds), (0...policy.reliefBudgetSeconds).contains(policy.reliefRemainingSeconds),
              (1...900).contains(policy.reliefMaxGrantSeconds), (1...120).contains(policy.exitCoolingOffSeconds),
              policy.timezone == "Europe/Minsk", policy.startsAt < policy.endsAt,
              policy.endsAt.timeIntervalSince(policy.startsAt) <= 9 * 3600 else { return false }
        if !allowSynthetic {
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
            let parts = calendar.dateComponents([.year, .month, .day, .hour], from: policy.startsAt)
            let date = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
            guard date == policy.localDate, (parts.hour ?? -1) >= 15, calendar.isDate(policy.startsAt, inSameDayAs: policy.endsAt) else { return false }
        }
        if let schedule {
            guard schedule.policyRevisionId == policy.policyRevisionId, schedule.targetRefs == policy.targetRefs, schedule.siteDomains == policy.siteDomains, schedule.siteSubdomains == policy.siteSubdomains,
                  schedule.reliefBudgetSeconds == policy.reliefBudgetSeconds, schedule.reliefMaxGrantSeconds == policy.reliefMaxGrantSeconds,
                  schedule.exitCoolingOffSeconds == policy.exitCoolingOffSeconds, !schedule.weekdays.isEmpty,
                  Set(schedule.weekdays).count == schedule.weekdays.count, schedule.weekdays.allSatisfy({ (1...7).contains($0) }),
                  schedule.startMinute >= 900, schedule.endMinute > schedule.startMinute, schedule.endMinute < 1440,
                  lastIssuedLocalDate == policy.localDate, let anchor = clockAnchor, anchor.uptime.isFinite, anchor.uptime >= 0, !anchor.boot.isEmpty else { return false }
        }
        return signingRequirements.allSatisfy { requirement in
            if allowSynthetic && requirement.signingID == "dev.stanislav.focusdeck.guard-fixture" {
                return requirement.teamID == "synthetic" && requirement.requirement == "3e481e3d08c534a55081e55313fa2768f44d57bc"
            }
            return ProcessSignatureRequirement.valid(requirement)
        }
    }
    static func day(_ now: Date) -> String {
        var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let p = c.dateComponents([.year, .month, .day], from: now)
        return String(format: "%04d-%02d-%02d", p.year!, p.month!, p.day!)
    }
    // A transition becomes authoritative only after the caller commits the full ledger.
    mutating func advance(now: Date, uptime: Double, boot: String) -> String? {
        guard let schedule, let anchor = clockAnchor else { return policy.reconcile(now: now) }
        guard uptime.isFinite, uptime >= anchor.uptime, !boot.isEmpty, boot == anchor.boot,
              abs(now.timeIntervalSince(anchor.wall) - (uptime - anchor.uptime)) <= 3 else {
            if clockUncertain != true { clockUncertain = true; return "schedule_clock_unknown" }
            return policy.reconcile(now: now)
        }
        if clockUncertain == true { return policy.reconcile(now: now) }
        if let event = policy.reconcile(now: now) { return event }
        let today = Self.day(now)
        guard let issued = lastIssuedLocalDate, today > issued, [.exit, .expired].contains(policy.state),
              var next = schedule.window(on: now), now >= next.startsAt, let revision = schedule.policyRevisionId else { return nil }
        next.windowId = WindowPolicy.recurringWindowID(revision: revision, date: today)
        _ = next.reconcile(now: now); policy = next; lastIssuedLocalDate = today
        return "scheduled_window_started"
    }

}
struct UserProtectionLedgerStore {
    let directory: URL
    let owner: uid_t
    var file: URL { directory.appendingPathComponent("protection-ledger.json") }
    func load(allowSynthetic: Bool = false) throws -> UserProtectionLedger? {
        var info = stat()
        guard lstat(directory.path, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.invalid }
        let fd = open(file.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
        if fd < 0 { if errno == ENOENT { return nil }; throw Failure.invalid }; defer { close(fd) }
        guard fstat(fd, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 128 * 1024 else { throw Failure.invalid }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let value = try decoder.decode(UserProtectionLedger.self, from: FileHandle(fileDescriptor: fd, closeOnDealloc: false).readToEnd() ?? Data())
        guard value.valid(allowSynthetic: allowSynthetic) else { throw Failure.invalid }; return value
    }
    func save(_ ledger: UserProtectionLedger, allowSynthetic: Bool = false) throws {
        guard ledger.valid(allowSynthetic: allowSynthetic) else { throw Failure.invalid }
        var dirInfo = stat()
        guard lstat(directory.path, &dirInfo) == 0, dirInfo.st_uid == owner, dirInfo.st_mode & S_IFMT == S_IFDIR, dirInfo.st_mode & 0o077 == 0 else { throw Failure.invalid }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let data = try encoder.encode(ledger), temp = directory.appendingPathComponent(".ledger-" + UUID().uuidString)
        let fd = open(temp.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw Failure.invalid }; defer { close(fd); unlink(temp.path) }
        try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
        guard fsync(fd) == 0, rename(temp.path, file.path) == 0 else { throw Failure.invalid }
        let d = open(directory.path, O_RDONLY | O_DIRECTORY | O_CLOEXEC); guard d >= 0 else { throw Failure.invalid }; defer { close(d) }
        guard fsync(d) == 0 else { throw Failure.invalid }
    }
    enum Failure: Error { case invalid }
}

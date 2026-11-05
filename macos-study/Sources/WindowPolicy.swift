import Foundation
import Darwin
import CryptoKit

// ASCII DNS names only. A selected root includes its subdomains; paths,
// wildcards, ports, IPs and overlapping infrastructure are not accepted.
enum SiteDomainPolicy {
    static let maximumDomains = 32
    static func parse(_ input: String) throws -> [String] {
        let domains = input.split(whereSeparator: { $0 == "," || $0 == "\n" }).map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
        let sorted = domains.sorted()
        guard sorted.isEmpty || validList(sorted, includeSubdomains: true) else { throw WindowPolicy.Failure.invalid }
        return sorted
    }
    static func valid(_ name: String, includeSubdomains: Bool = false) -> Bool {
        guard name == name.lowercased(), name.utf8.count <= 253 else { return false }
        let labels = name.split(separator: ".", omittingEmptySubsequences: false)
        guard labels.count >= 2, labels.allSatisfy({ label in
            !label.isEmpty && label.utf8.count <= 63 && label.first != "-" && label.last != "-" &&
            label.utf8.allSatisfy { (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }
        }), labels.last!.contains(where: { $0.isLetter }) else { return false }
        let protected = ["gitlab.eskhata.com", "openai.com", "chatgpt.com"]
        return !protected.contains(where: { name == $0 || name.hasSuffix("." + $0) || (includeSubdomains && $0.hasSuffix("." + name)) })
    }
    static func validList(_ domains: [String]?, includeSubdomains: Bool = false) -> Bool {
        guard let domains else { return true }
        return !domains.isEmpty && domains.count <= maximumDomains && domains.reduce(0, { $0 + $1.utf8.count }) <= 2048 && domains == domains.sorted() && Set(domains).count == domains.count && domains.allSatisfy { valid($0, includeSubdomains: includeSubdomains) }
    }
}

// One explicitly configured calendar window, independent of task sessions.
struct WindowPolicy: Codable, Equatable, Sendable {
    enum State: String, Codable, Sendable { case inactive, active, limitedRelief = "limited-relief", rest, exitPending = "exit-pending", exit, expired }
    var policyVersion = 2
    var policyRevisionId: UUID?
    var windowId: UUID
    var timezone = "Europe/Minsk"
    var localDate: String
    var startsAt: Date
    var endsAt: Date
    var reliefBudgetSeconds: Int
    var reliefRemainingSeconds: Int
    var reliefMaxGrantSeconds: Int
    var targetRefs: [String]
    var siteDomains: [String]? = nil
    var siteSubdomains: Bool? = nil
    var state: State = .inactive
    var reliefUntil: Date?
    var exitAvailableAt: Date?
    var stateBeforeExitPending: State?
    var exitBootID: String?
    var exitUptimeDeadline: Double?
    var exitCoolingOffSeconds: Int = 15

    mutating func reconcile(now: Date) -> String? {
        guard state != .exit && state != .expired else { return nil }
        if now >= endsAt { state = .expired; reliefUntil = nil; return "window_expired" }
        if now < startsAt { return nil }
        if state == .inactive { state = .active; return "window_started" }
        if state == .exitPending, stateBeforeExitPending == .limitedRelief, let deadline = reliefUntil, now >= deadline {
            stateBeforeExitPending = .active; reliefUntil = nil; return "relief_ended"
        }
        if state == .limitedRelief, let deadline = reliefUntil, now >= deadline {
            state = .active; reliefUntil = nil; return "relief_ended"
        }
        return nil
    }
    mutating func relief(now: Date) -> Bool {
        _ = reconcile(now: now)
        guard state == .active || state == .rest else { return false }
        let grant = min(reliefMaxGrantSeconds, reliefRemainingSeconds, max(0, Int(endsAt.timeIntervalSince(now))))
        guard grant > 0 else { return false }
        // Reserve the complete grant so restart cannot restore spent budget.
        reliefRemainingSeconds -= grant; reliefUntil = now.addingTimeInterval(TimeInterval(grant)); state = .limitedRelief
        return true
    }
    mutating func rest(now: Date) -> Bool {
        _ = reconcile(now: now)
        guard state == .active else { return false }; state = .rest; return true
    }
    mutating func returnToWindow(now: Date) -> Bool {
        _ = reconcile(now: now)
        guard state == .limitedRelief || state == .rest else { return false }
        reliefUntil = nil; state = .active; return true
    }
    static func bootID() -> String? {
        var size = 0
        guard sysctlbyname("kern.bootsessionuuid", nil, &size, nil, 0) == 0, size > 1, size < 256 else { return nil }
        var bytes = [CChar](repeating: 0, count: size)
        guard sysctlbyname("kern.bootsessionuuid", &bytes, &size, nil, 0) == 0 else { return nil }
        return String(decoding: bytes.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }
    func canConfirmExit(now: Date, uptime: Double = ProcessInfo.processInfo.systemUptime, boot: String? = WindowPolicy.bootID()) -> Bool {
        guard state == .exitPending, let available = exitAvailableAt, now >= available, now < endsAt,
              let savedBoot = exitBootID, let boot, savedBoot == boot,
              let deadline = exitUptimeDeadline, uptime >= deadline else { return false }
        return true
    }
    mutating func requestExit(now: Date, uptime: Double = ProcessInfo.processInfo.systemUptime, boot: String? = WindowPolicy.bootID()) -> Bool {
        _ = reconcile(now: now)
        guard [.inactive, .active, .limitedRelief, .rest].contains(state) else { return false }
        guard let boot, uptime.isFinite else { return false }
        stateBeforeExitPending = state; exitAvailableAt = now.addingTimeInterval(TimeInterval(exitCoolingOffSeconds))
        exitBootID = boot; exitUptimeDeadline = uptime + Double(exitCoolingOffSeconds); state = .exitPending
        return true
    }
    mutating func cancelExit(now: Date) -> Bool {
        _ = reconcile(now: now)
        guard state == .exitPending, let prior = stateBeforeExitPending else { return false }
        state = prior; exitAvailableAt = nil; stateBeforeExitPending = nil; exitBootID = nil; exitUptimeDeadline = nil; _ = reconcile(now: now); return true
    }
    mutating func exitWindow(now: Date, uptime: Double = ProcessInfo.processInfo.systemUptime, boot: String? = WindowPolicy.bootID()) -> Bool {
        _ = reconcile(now: now)
        guard canConfirmExit(now: now, uptime: uptime, boot: boot) else { return false }
        state = .exit; reliefUntil = nil; exitAvailableAt = nil; stateBeforeExitPending = nil; exitBootID = nil; exitUptimeDeadline = nil; return true
    }
    static func recurringWindowID(revision: UUID, date: String) -> UUID {
        var b = Array(SHA256.hash(data: Data((revision.uuidString + ":" + date).utf8)).prefix(16)); b[6] = (b[6] & 0x0f) | 0x50; b[8] = (b[8] & 0x3f) | 0x80
        let h = Array(b.map { String(format: "%02x", $0) }.joined())
        return UUID(uuidString: String(h[0..<8]) + "-" + String(h[8..<12]) + "-" + String(h[12..<16]) + "-" + String(h[16..<20]) + "-" + String(h[20..<32]))!
    }
    static func create(start: Date, end: Date, targets: [String], sites: [String] = [], budget: Int, maxGrant: Int, now: Date) throws -> WindowPolicy {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let components = calendar.dateComponents([.hour, .year, .month, .day], from: start)
        guard start > now, end > start, calendar.isDate(start, inSameDayAs: end), (components.hour ?? -1) >= 15,
              budget >= 0, maxGrant > 0, (!targets.isEmpty || !sites.isEmpty), SiteDomainPolicy.validList(sites.isEmpty ? nil : sites, includeSubdomains: true) else { throw Failure.invalid }
        let day = String(format: "%04d-%02d-%02d", components.year!, components.month!, components.day!)
        var policy = WindowPolicy(windowId: UUID(), localDate: day, startsAt: start, endsAt: end,
                            reliefBudgetSeconds: budget, reliefRemainingSeconds: budget, reliefMaxGrantSeconds: maxGrant, targetRefs: targets)
        policy.policyRevisionId = UUID(); policy.siteDomains = sites.isEmpty ? nil : sites; policy.siteSubdomains = sites.isEmpty ? nil : true; return policy
    }
    enum Failure: Error { case invalid }
}

private struct WindowSnapshot: Codable {
    var policy: WindowPolicy?
    var schedule: WindowSchedule?
}
struct WindowPolicyStore {
    let directory: URL
    var policyURL: URL { directory.appendingPathComponent("window-policy-v2.json") }
    var eventURL: URL { directory.appendingPathComponent("window-events-v2.jsonl") }
    var snapshotURL: URL { directory.appendingPathComponent("window-snapshot-v2.json") }
    private func snapshot() throws -> WindowSnapshot {
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        if FileManager.default.fileExists(atPath: snapshotURL.path) {
            return try decoder.decode(WindowSnapshot.self, from: Data(contentsOf: snapshotURL))
        }
        // One-way migration; if either legacy file is malformed fail closed without overwriting it.
        let policy = FileManager.default.fileExists(atPath: policyURL.path) ? try decoder.decode(WindowPolicy.self, from: Data(contentsOf: policyURL)) : nil
        let schedule = FileManager.default.fileExists(atPath: scheduleURL.path) ? try decoder.decode(WindowSchedule.self, from: Data(contentsOf: scheduleURL)) : nil
        return WindowSnapshot(policy: policy, schedule: schedule)
    }
    private func saveSnapshot(_ snapshot: WindowSnapshot) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        try encoder.encode(snapshot).write(to: snapshotURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: snapshotURL.path)
    }
    func load() throws -> WindowPolicy? { try snapshot().policy }
    func saveConfiguration(_ policy: WindowPolicy, schedule: WindowSchedule, now: Date = Date()) throws {
        try saveSnapshot(WindowSnapshot(policy: policy, schedule: schedule))
        try appendEvent("configured", policy: policy, now: now)
    }
    func save(_ policy: WindowPolicy, event: String? = nil, now: Date = Date()) throws {
        var combined = try snapshot(); combined.policy = policy; try saveSnapshot(combined)
        if let event { try appendEvent(event, policy: policy, now: now) }
    }
    private func appendEvent(_ event: String, policy: WindowPolicy, now: Date) throws {
            var entry: [String: Any] = ["event": event, "windowId": policy.windowId.uuidString, "at": ISO8601DateFormatter().string(from: now), "policyVersion": 2, "eventId": UUID().uuidString, "commitStatus": "committed"]
            if let revision = policy.policyRevisionId { entry["policyRevisionId"] = revision.uuidString }
            var data = try JSONSerialization.data(withJSONObject: entry, options: [.sortedKeys]); data.append(10)
            if !FileManager.default.fileExists(atPath: eventURL.path) { FileManager.default.createFile(atPath: eventURL.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
            let handle = try FileHandle(forWritingTo: eventURL); defer { try? handle.close() }; try handle.seekToEnd(); try handle.write(contentsOf: data)
    }
}

struct WindowSchedule: Codable, Equatable, Sendable {
    var scheduleId = UUID()
    var policyRevisionId: UUID?
    var weekdays: [Int] // ISO Monday = 1
    var startMinute: Int
    var endMinute: Int
    var targetRefs: [String]
    var reliefBudgetSeconds: Int
    var reliefMaxGrantSeconds: Int
    var exitCoolingOffSeconds: Int = 15
    var siteDomains: [String]? = nil
    var siteSubdomains: Bool? = nil
    func window(on now: Date) -> WindowPolicy? {
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let weekday = ((calendar.component(.weekday, from: now) + 5) % 7) + 1
        guard weekdays.contains(weekday), startMinute >= 900, endMinute > startMinute, endMinute < 1440 else { return nil }
        let day = calendar.startOfDay(for: now)
        guard let start = calendar.date(byAdding: .minute, value: startMinute, to: day),
              let end = calendar.date(byAdding: .minute, value: endMinute, to: day), now < end else { return nil }
        let parts = calendar.dateComponents([.year, .month, .day], from: day)
        let localDate = String(format: "%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
        var window = WindowPolicy(windowId: UUID(), localDate: localDate, startsAt: start, endsAt: end,
                            reliefBudgetSeconds: reliefBudgetSeconds, reliefRemainingSeconds: reliefBudgetSeconds,
                            reliefMaxGrantSeconds: reliefMaxGrantSeconds, targetRefs: targetRefs)
        window.exitCoolingOffSeconds = exitCoolingOffSeconds; window.policyRevisionId = policyRevisionId; window.siteDomains = siteDomains; window.siteSubdomains = siteSubdomains
        return window
    }
}
extension WindowPolicyStore {
    var scheduleURL: URL { directory.appendingPathComponent("window-schedule-v2.json") }
    func loadSchedule() throws -> WindowSchedule? { try snapshot().schedule }
    func saveSchedule(_ schedule: WindowSchedule?) throws {
        var combined = try snapshot(); combined.schedule = schedule; try saveSnapshot(combined)
    }
}

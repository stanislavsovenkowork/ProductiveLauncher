import Foundation
import Darwin

// User-scope candidate: only explicitly selected signed applications; no network changes.
private let fixtureID = "dev.stanislav.focusdeck.guard-fixture"
private let fixtureHash = "3e481e3d08c534a55081e55313fa2768f44d57bc"
#if USER_GUARD_TEST || USER_GUARD_QA
private let allowSynthetic = true
#else
private let allowSynthetic = false
#endif
#if USER_GUARD_TEST
private let socketPath = ProcessInfo.processInfo.environment["FOCUSDECK_USER_TEST_SOCKET"] ?? "/tmp/focusdeck-user-invalid.sock"
private let directory = ProcessInfo.processInfo.environment["FOCUSDECK_USER_TEST_DIR"] ?? "/tmp/focusdeck-user-invalid-data"
#elseif USER_GUARD_QA
private let socketPath = "/tmp/focusdeck-user-protection-qa-\(getuid()).sock"
private let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckUserProtectionQAAgent", isDirectory: true).path
#else
private let socketPath = "/tmp/focusdeck-user-protection-\(getuid()).sock"
private let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckUserProtectionAgent", isDirectory: true).path
#endif
private let owner = getuid()
private let allowedUID = getuid()
private struct AgentClock: Decodable { let wall: Date; let uptime: Double; let boot: String }
private func sampleClock() throws -> AgentClock {
    #if USER_RECURRENCE_TEST
    guard let file = ProcessInfo.processInfo.environment["FOCUSDECK_USER_TEST_CLOCK"], file.hasPrefix("/tmp/focusdeck-") else { throw UserProtectionAgent.Failure.invalid }
    let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
    return try decoder.decode(AgentClock.self, from: Data(contentsOf: URL(fileURLWithPath: file)))
    #else
    guard let boot = WindowPolicy.bootID() else { throw UserProtectionAgent.Failure.invalid }
    return AgentClock(wall: Date(), uptime: ProcessInfo.processInfo.systemUptime, boot: boot)
    #endif
}
private final class Stop: @unchecked Sendable {
    private let lock = NSLock(); private var value = false
    func set() { lock.lock(); value = true; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}
@main enum UserProtectionAgent {
 static func main() {
    do { try run() } catch { fputs("user protection agent failed; inspect fixture state\n", stderr); exit(1) }
 }
 static func run() throws {
    guard geteuid() == owner else { throw Failure.invalid }
    let path = URL(fileURLWithPath: directory, isDirectory: true)
    var info = stat()
    if lstat(directory, &info) != 0 { guard errno == ENOENT else { throw Failure.invalid }; try FileManager.default.createDirectory(at: path, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
    guard lstat(directory, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.invalid }
    let store = UserProtectionLedgerStore(directory: path, owner: owner)
    var current = try store.load(allowSynthetic: allowSynthetic)
    #if USER_GUARD_QA
    if let current, Set(current.signingRequirements.map(\.signingID)) != [fixtureID] { throw Failure.invalid }
    #endif
    let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
    let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
    var existing = stat(); guard lstat(socketPath, &existing) != 0, errno == ENOENT else { throw Failure.invalid }
    let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
    var address = try guardAddress(socketPath)
    guard withUnsafePointer(to: &address, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }) == 0 else { throw Failure.invalid }
    var own = stat(); guard lstat(socketPath, &own) == 0 else { throw Failure.invalid }
    defer { var last = stat(); if lstat(socketPath, &last) == 0 && last.st_uid == owner && last.st_mode & S_IFMT == S_IFSOCK && last.st_dev == own.st_dev && last.st_ino == own.st_ino { unlink(socketPath) } }
    guard chmod(socketPath, 0o666) == 0, listen(fd, 4) == 0 else { throw Failure.invalid }
    let stop = Stop(); signal(SIGTERM, SIG_IGN)
    let signals = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .global()); signals.setEventHandler { @Sendable in stop.set() }; signals.resume(); defer { signals.cancel() }
    var scanner = GuardProcessScanner()
    var attempts: [String: TimeInterval] = [:]
    func event(_ name: String, pid: Int32 = 0) throws {
        let url = path.appendingPathComponent("events.jsonl")
        var data = try JSONSerialization.data(withJSONObject: ["event": name, "pid": pid] as [String: Any]); data.append(10)
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
        let h = try FileHandle(forWritingTo: url); defer { try? h.close() }; try h.seekToEnd(); try h.write(contentsOf: data)
    }
    while !stop.get() {
        var pollItem = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        let clock = try sampleClock(), now = clock.wall, uptime = clock.uptime
        if var state = current, let change = state.advance(now: now, uptime: uptime, boot: clock.boot) {
            try store.save(state, allowSynthetic: allowSynthetic); current = state; try event(change)
        }
        if let state = current, WindowGuardV2.enforcing(state.policy, now: now) {
            let requirements = Dictionary(uniqueKeysWithValues: state.signingRequirements.map { ($0.signingID, $0) })
            for process in scanner.scan(uid: owner, targets: Set(requirements.keys), uptime: uptime) {
                guard let id = process.signingID, let expected = requirements[id], !WindowGuardV2.isProtected(id) else { continue }
                #if USER_GUARD_QA
                guard id == fixtureID else { continue }
                #endif
                let verifiedSignal: (Int32, Int32) -> Int32 = { pid, signal in
                    if allowSynthetic && id == fixtureID { guard RootSocketServer.verifiedHash(pid: pid) == fixtureHash else { return -1 } }
                    else { guard ProcessSignatureRequirement.matches(pid: pid, expected: expected) else { return -1 } }
                    return Darwin.kill(pid, signal)
                }
                let key = "\(process.pid):\(process.startSeconds):\(process.startMicros)"
                if let first = attempts[key] {
                    guard uptime - first >= 2 else { continue }
                    if RootGuardCore.terminate(process, signal: verifiedSignal, force: true) { try event("selected_app_force_signal_sent", pid: process.pid) }
                } else {
                    try event("selected_app_attempt", pid: process.pid)
                    if RootGuardCore.terminate(process, signal: verifiedSignal) { try event("selected_app_signal_sent", pid: process.pid) }
                }
                attempts[key] = uptime
            }
        } else { attempts.removeAll() }
        guard poll(&pollItem, 1, 250) > 0 else { continue }
        let client = accept(fd, nil, nil); guard client >= 0 else { continue }
        do {
        defer { close(client) }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard getpeereid(client, &uid, &gid) == 0, uid == allowedUID,
              getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
              let data = try? readRootFrame(client, timeout: 0.5) else { continue }
        #if USER_GUARD_QA
        if data == Data("clear-test-data".utf8) {
            guard current == nil || current?.policy.state == .exit || current?.policy.state == .expired else { try? writeRootFrame(Data("refused-active".utf8), fd: client, timeout: 0.5); continue }
            do { try UserProtectionQACleanup.clear(directory: path, owner: owner, allowEmpty: current == nil); try writeRootFrame(Data("cleared".utf8), fd: client, timeout: 0.5); return }
            catch { try? writeRootFrame(Data("refused-data".utf8), fd: client, timeout: 0.5); continue }
        }
        #endif
        guard let request = try? decoder.decode(WindowGuardRequest.self, from: data) else { continue }
        let clock = try sampleClock(), now = clock.wall, hash = RootSocketServer.verifiedHash(pid: pid)
        if request.command == "status" {
            let policy = current?.policy
            let scheduleStatus = current?.schedule == nil ? "off" : (current?.clockUncertain == true ? "clock_unknown" : "scheduled")
            let response = WindowGuardReply(accepted: true, reason: "observed", windowId: policy?.windowId, enforcement: policy.map { WindowGuardV2.enforcing($0, now: now) ? "selected_apps_requested" : "released" } ?? "released", policy: policy, scheduleStatus: scheduleStatus)
            try? writeRootFrame(encoder.encode(response), fd: client, timeout: 0.5); continue
        }
        if request.command == "disable_schedule" {
            guard var state = current else { continue }
            state.schedule = nil; state.clockAnchor = nil; state.clockUncertain = nil
            try store.save(state, allowSynthetic: allowSynthetic); current = state; try event("schedule_disabled")
            let response = WindowGuardReply(accepted: true, reason: "schedule_disabled", windowId: state.policy.windowId, enforcement: WindowGuardV2.enforcing(state.policy, now: now) ? "selected_apps_requested" : "released", policy: state.policy, scheduleStatus: "off")
            try? writeRootFrame(encoder.encode(response), fd: client, timeout: 0.5); continue
        }
        let shouldReplace = current == nil || (current!.policy.state == .exit || current!.policy.state == .expired) && request.policy.localDate > current!.policy.localDate
        if !shouldReplace, (request.schedule != nil && request.schedule != current?.schedule) || (request.signingRequirements != nil && request.signingRequirements != current?.signingRequirements) {
            let refusal = WindowGuardReply(accepted: false, reason: "active_policy_immutable", windowId: nil, enforcement: "unknown", policy: nil)
            try? writeRootFrame(encoder.encode(refusal), fd: client, timeout: 0.5); continue
        }
        let requirements = shouldReplace ? (request.signingRequirements ?? []) : (current?.signingRequirements ?? [])
        let schedule = shouldReplace ? request.schedule : current?.schedule
        let anchor = shouldReplace && schedule != nil ? UserProtectionLedger.ClockAnchor(wall: now, uptime: clock.uptime, boot: clock.boot) : current?.clockAnchor
        let issued = shouldReplace ? request.policy.localDate : current?.lastIssuedLocalDate
        let uncertain = shouldReplace ? nil : current?.clockUncertain
        let candidate = UserProtectionLedger(schemaVersion: 1, policy: request.policy, signingRequirements: requirements, schedule: schedule, lastIssuedLocalDate: issued, clockAnchor: anchor, clockUncertain: uncertain)
        #if USER_GUARD_QA
        guard Set(requirements.map(\.signingID)) == [fixtureID], WindowFixtureBounds.permits(request.policy, now: now) else {
            let refusal = WindowGuardReply(accepted: false, reason: "qa_synthetic_only", windowId: nil, enforcement: "unknown", policy: nil)
            try? writeRootFrame(encoder.encode(refusal), fd: client, timeout: 0.5); continue
        }
        #endif
        guard candidate.valid(allowSynthetic: allowSynthetic), hash != nil else {
            let refusal = WindowGuardReply(accepted: false, reason: "invalid_targets", windowId: nil, enforcement: "unknown", policy: nil)
            try? writeRootFrame(encoder.encode(refusal), fd: client, timeout: 0.5); continue
        }
        let core = WindowGuardV2(allowedTargets: Set(requirements.map(\.signingID)), trustedUID: owner, trustedHash: hash!, requireCalendar: !allowSynthetic)
        let (next, reply) = core.apply(request, current: current?.policy, peerUID: uid, peerHash: hash, now: now, uptime: clock.uptime, boot: clock.boot)
        if reply.accepted, let next {
            // A durable state must remain readable through the bounded status protocol.
            let largestStatus = WindowGuardReply(accepted: true, reason: "observed", windowId: next.windowId, enforcement: "selected_apps_requested", policy: next, scheduleStatus: "clock_unknown")
            guard let statusSize = try? encoder.encode(largestStatus).count, statusSize < 4096 else {
                let refusal = WindowGuardReply(accepted: false, reason: "status_too_large", windowId: nil, enforcement: "unknown", policy: nil)
                try? writeRootFrame(encoder.encode(refusal), fd: client, timeout: 0.5); continue
            }
            var result = candidate; result.policy = next
            try store.save(result, allowSynthetic: allowSynthetic); current = result; try event("policy_" + next.state.rawValue)
        }
        try? writeRootFrame(encoder.encode(reply), fd: client, timeout: 0.5)
        }
    }
 }
 enum Failure: Error { case invalid }
}

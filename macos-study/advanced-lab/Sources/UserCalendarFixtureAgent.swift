import Foundation
import Darwin

// Dedicated SMAppService fixture. No personal targets, domains, or network changes.
private let target = "dev.stanislav.focusdeck.guard-fixture"
private let fixtureHash = "3e481e3d08c534a55081e55313fa2768f44d57bc"
#if USER_GUARD_TEST
private let socketPath = ProcessInfo.processInfo.environment["FOCUSDECK_USER_TEST_SOCKET"] ?? "/tmp/focusdeck-user-invalid.sock"
private let directory = ProcessInfo.processInfo.environment["FOCUSDECK_USER_TEST_DIR"] ?? "/tmp/focusdeck-user-invalid-data"
#else
private let socketPath = "/tmp/focusdeck-user-calendar-\(getuid()).sock"
private let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckUserCalendarFixtureAgent", isDirectory: true).path
#endif
private let owner = getuid()
private let allowedUID = getuid()
private final class Stop: @unchecked Sendable {
    private let lock = NSLock(); private var value = false
    func set() { lock.lock(); value = true; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}
@main enum UserCalendarFixtureAgent {
 static func main() {
    do { try run() } catch { fputs("user fixture agent failed; inspect fixture state\n", stderr); exit(1) }
 }
 static func run() throws {
    guard geteuid() == owner else { throw Failure.invalid }
    let path = URL(fileURLWithPath: directory, isDirectory: true)
    var info = stat()
    if lstat(directory, &info) != 0 { guard errno == ENOENT else { throw Failure.invalid }; try FileManager.default.createDirectory(at: path, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]) }
    guard lstat(directory, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.invalid }
    let file = path.appendingPathComponent("window.json")
    let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
    let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
    var current: WindowPolicy?
    if lstat(file.path, &info) == 0 {
        guard info.st_uid == owner, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 16384 else { throw Failure.invalid }
        current = try decoder.decode(WindowPolicy.self, from: Data(contentsOf: file))
    } else if errno != ENOENT { throw Failure.invalid }
    func persist(_ policy: WindowPolicy) throws {
        let data = try encoder.encode(policy); let temp = path.appendingPathComponent(".window-" + UUID().uuidString)
        let fd = open(temp.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw Failure.invalid }; defer { close(fd); unlink(temp.path) }
        try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
        guard fsync(fd) == 0, rename(temp.path, file.path) == 0 else { throw Failure.invalid }
        let d = open(directory, O_RDONLY | O_CLOEXEC); guard d >= 0 else { throw Failure.invalid }; defer { close(d) }
        guard fsync(d) == 0 else { throw Failure.invalid }
    }
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
    func event(_ name: String, pid: Int32 = 0) throws {
        let url = path.appendingPathComponent("events.jsonl")
        var data = try JSONSerialization.data(withJSONObject: ["event": name, "pid": pid] as [String: Any]); data.append(10)
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
        let h = try FileHandle(forWritingTo: url); defer { try? h.close() }; try h.seekToEnd(); try h.write(contentsOf: data)
    }
    while !stop.get() {
        var pollItem = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        let now = Date(), uptime = ProcessInfo.processInfo.systemUptime
        if var policy = current, let change = policy.reconcile(now: now) {
            try persist(policy); current = policy; try event(change)
        }
        if let policy = current, WindowGuardV2.enforcing(policy, now: now) {
            for process in scanner.scan(uid: owner, targets: [target], uptime: uptime) where process.signingID == target {
                guard RootSocketServer.verifiedHash(pid: process.pid) == fixtureHash else { continue }
                let verifiedSignal: (Int32, Int32) -> Int32 = { pid, signal in
                    guard RootSocketServer.verifiedHash(pid: pid) == fixtureHash else { return -1 }
                    return Darwin.kill(pid, signal)
                }
                try event("fixture_attempt", pid: process.pid)
                if RootGuardCore.terminate(process, signal: verifiedSignal) { try event("fixture_signal_sent", pid: process.pid) }
            }
        }
        guard poll(&pollItem, 1, 250) > 0 else { continue }
        let client = accept(fd, nil, nil); guard client >= 0 else { continue }
        do {
        defer { close(client) }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard getpeereid(client, &uid, &gid) == 0, uid == allowedUID,
              getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
              let data = try? readRootFrame(client, timeout: 0.5) else { continue }
        if data == Data("clear-test-data".utf8) {
            guard current == nil || current?.state == .exit || current?.state == .expired else {
                try? writeRootFrame(Data("refused-active".utf8), fd: client, timeout: 0.5); continue
            }
            do {
                try SMCalendarFixtureCleanup.clear(directory: path, owner: owner, allowEmpty: current == nil)
                try writeRootFrame(Data("cleared".utf8), fd: client, timeout: 0.5)
                return // never recreate this directory in the running daemon
            } catch { try? writeRootFrame(Data("refused-data".utf8), fd: client, timeout: 0.5); continue }
        }
        guard let request = try? decoder.decode(WindowGuardRequest.self, from: data) else { continue }
        let now = Date(), hash = RootSocketServer.verifiedHash(pid: pid)
        // UID is the intended trust boundary for this fixture, not the UI CDHash.
        let core = WindowGuardV2(allowedTargets: [target], trustedUID: allowedUID, trustedHash: hash ?? "")
        let (next, proposed) = core.apply(request, current: current, peerUID: uid, peerHash: hash, now: now)
        let permitted = request.command == "status" || WindowFixtureBounds.permits(request.policy, now: now)
        let reply = permitted ? proposed : WindowGuardReply(accepted: false, reason: "fixture_bounds", windowId: nil, enforcement: "unknown", policy: nil)
        if reply.accepted, request.command != "status", let next {
            try persist(next); current = next; try event("policy_" + next.state.rawValue)
        }
        try? writeRootFrame(encoder.encode(reply), fd: client, timeout: 0.5)
        }
    }
 }
 enum Failure: Error { case invalid }
}

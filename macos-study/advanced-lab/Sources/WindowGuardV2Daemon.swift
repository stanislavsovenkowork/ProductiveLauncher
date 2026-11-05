import Foundation
import Darwin

private struct FixtureTrustFingerprint: Decodable { let fixtureCDHash: String }
private struct FixtureDiskState: Codable {
    var policy: WindowPolicy
    var bootID: String
    var uptimeDeadline: Double
    var reliefUptimeDeadline: Double?
}
// Synthetic fixture ONLY. This executable never enables hosts or browser rules.
@main enum WindowGuardV2Daemon {
    static let fixtureID = "dev.stanislav.focusdeck.guard-fixture"
    static let directory = "/Library/Application Support/FocusDeckGuardV2Fixture"
    static func main() throws {
        guard CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--fixture-daemon", geteuid() == 0 else { throw Failure.invalidSetup }
        var dirInfo = stat(), configInfo = stat()
        let configPath = directory + "/root-config.json"
        guard lstat(directory, &dirInfo) == 0, dirInfo.st_uid == 0, dirInfo.st_mode & S_IFMT == S_IFDIR, dirInfo.st_mode & 0o077 == 0,
              lstat(configPath, &configInfo) == 0, configInfo.st_uid == 0, configInfo.st_mode & S_IFMT == S_IFREG,
              configInfo.st_mode & 0o077 == 0, configInfo.st_size <= 16384 else { throw Failure.invalidSetup }
        let lockFD = open(directory + "/daemon.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard lockFD >= 0 else { throw Failure.invalidSetup }; defer { close(lockFD) }
        var lockInfo = stat()
        guard fstat(lockFD, &lockInfo) == 0, lockInfo.st_uid == 0, lockInfo.st_mode & S_IFMT == S_IFREG,
              lockInfo.st_mode & 0o077 == 0, flock(lockFD, LOCK_EX | LOCK_NB) == 0 else { throw Failure.invalidSetup }
        let trustData = try Data(contentsOf: URL(fileURLWithPath: configPath))
        let trust = try JSONDecoder().decode(RootTrust.self, from: trustData); try trust.validate()
        let fixtureHash = try JSONDecoder().decode(FixtureTrustFingerprint.self, from: trustData).fixtureCDHash.lowercased()
        guard fixtureHash.count == 40, fixtureHash.allSatisfy(\.isHexDigit), Set(trust.blockedSigningIDs) == [fixtureID], trust.blockedDomains.isEmpty else { throw Failure.invalidSetup }
        let socketPath = WindowGuardV2Client.path
        var old = stat()
        // Never replace a pre-existing socket, even one owned by root.
        guard lstat(socketPath, &old) != 0, errno == ENOENT else { throw Failure.invalidSetup }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalidSetup }
        var ownedSocket: (dev_t, ino_t)?
        defer {
            close(fd)
            var current = stat()
            if let ownedSocket, lstat(socketPath, &current) == 0, current.st_uid == 0,
               current.st_mode & S_IFMT == S_IFSOCK, current.st_dev == ownedSocket.0, current.st_ino == ownedSocket.1 { unlink(socketPath) }
        }
        var address = try guardAddress(socketPath)
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard bound == 0 else { throw Failure.invalidSetup }
        var socketInfo = stat()
        guard lstat(socketPath, &socketInfo) == 0, socketInfo.st_uid == 0, socketInfo.st_mode & S_IFMT == S_IFSOCK else { throw Failure.invalidSetup }
        ownedSocket = (socketInfo.st_dev, socketInfo.st_ino)
        guard chmod(socketPath, 0o666) == 0, listen(fd, 4) == 0, fcntl(fd, F_SETFL, O_NONBLOCK) == 0 else { throw Failure.invalidSetup }
        let marker = URL(fileURLWithPath: directory + "/socket-inode.json")
        let markerData = try JSONSerialization.data(withJSONObject: ["device": UInt64(socketInfo.st_dev), "inode": UInt64(socketInfo.st_ino)])
        try markerData.write(to: marker, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: marker.path)
        let file = URL(fileURLWithPath: directory + "/window-v2-fixture.json")
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        var current: WindowPolicy?
        var cap: Double?
        guard let boot = WindowPolicy.bootID() else { throw Failure.invalidSetup }
        var reliefCap: Double?
        if FileManager.default.fileExists(atPath: file.path) {
            var info = stat()
            guard lstat(file.path, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0, info.st_size <= 16384 else { throw Failure.invalidSetup }
            let disk = try decoder.decode(FixtureDiskState.self, from: Data(contentsOf: file))
            current = disk.policy
            cap = disk.bootID == boot ? disk.uptimeDeadline : ProcessInfo.processInfo.systemUptime + max(0, disk.policy.endsAt.timeIntervalSinceNow)
            if let until = disk.policy.reliefUntil {
                reliefCap = disk.bootID == boot ? disk.reliefUptimeDeadline : ProcessInfo.processInfo.systemUptime + max(0, until.timeIntervalSinceNow)
            }
        }
        func persist(_ p: WindowPolicy, deadline: Double) throws {
            try encoder.encode(FixtureDiskState(policy: p, bootID: boot, uptimeDeadline: deadline, reliefUptimeDeadline: reliefCap)).write(to: file, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
        }
        func event(_ name: String, policy: WindowPolicy) {
            let path = directory + "/fixture-events.jsonl"
            let row = ["event": name, "windowId": policy.windowId.uuidString, "at": ISO8601DateFormatter().string(from: Date())]
            guard var data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]) else { return }; data.append(10)
            if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil, attributes: [.posixPermissions: 0o600]) }
            guard let handle = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) else { return }
            defer { try? handle.close() }; _ = try? handle.seekToEnd(); try? handle.write(contentsOf: data)
        }
        let guardCore = WindowGuardV2(allowedTargets: [fixtureID], trustedUID: trust.allowedUID, trustedHash: trust.uiCDHash)
        var scanner = GuardProcessScanner()
        while true {
            let client = accept(fd, nil, nil)
            if client >= 0 {
                var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, length = socklen_t(MemoryLayout<Int32>.size)
                if getpeereid(client, &uid, &gid) == 0, getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &length) == 0,
                   let data = try? readRootFrame(client, timeout: 0.5) {
                    if RootFixtureStopCore.authorized(uid: uid, payload: data) {
                        if var policy = current {
                            policy.state = .exit; policy.reliefUntil = nil; policy.exitAvailableAt = nil
                            try persist(policy, deadline: cap ?? ProcessInfo.processInfo.systemUptime)
                            event("explicit_fixture_stop", policy: policy)
                        }
                        try writeRootFrame(RootFixtureStopCore.reply, fd: client, timeout: 0.5)
                        close(client); return
                    }
                    guard let request = try? decoder.decode(WindowGuardRequest.self, from: data) else { close(client); continue }
                    let now = Date()
                    let (result, proposed) = guardCore.apply(request, current: current, peerUID: uid, peerHash: RootSocketServer.verifiedHash(pid: pid), now: now)
                    let reply: WindowGuardReply
                    if proposed.accepted && request.command != "status" && !WindowFixtureBounds.permits(request.policy, now: now) {
                        reply = WindowGuardReply(accepted: false, reason: "fixture_window_exceeds_bound", windowId: nil, enforcement: "unknown", policy: nil)
                    } else { reply = proposed }
                    if reply.accepted, request.command != "status", let result {
                        if current?.windowId != result.windowId || cap == nil { cap = ProcessInfo.processInfo.systemUptime + max(0, result.endsAt.timeIntervalSinceNow) }
                        if let until = result.reliefUntil, current?.reliefUntil != until { reliefCap = ProcessInfo.processInfo.systemUptime + max(0, until.timeIntervalSinceNow) }
                        if result.reliefUntil == nil { reliefCap = nil }
                        try persist(result, deadline: cap!); current = result
                        event("policy_updated", policy: result)
                    }
                    try? writeRootFrame(encoder.encode(reply), fd: client, timeout: 0.5)
                }
                close(client)
            }
            let now = Date(), uptime = ProcessInfo.processInfo.systemUptime
            if var policy = current, let until = policy.reliefUntil, now >= until || (reliefCap.map { uptime >= $0 } ?? false) {
                if policy.state == .limitedRelief { policy.state = .active }
                if policy.state == .exitPending && policy.stateBeforeExitPending == .limitedRelief { policy.stateBeforeExitPending = .active }
                policy.reliefUntil = nil; reliefCap = nil; try persist(policy, deadline: cap ?? uptime); current = policy; event("relief_ended", policy: policy)
            }
            if var policy = current, policy.state != .expired && policy.state != .exit,
               now >= policy.endsAt || (cap.map { uptime >= $0 } ?? false) {
                policy.state = .expired; try persist(policy, deadline: cap ?? uptime); current = policy; event("window_expired", policy: policy)
            }
            if let policy = current, WindowGuardV2.enforcing(policy, now: now), cap.map({ uptime < $0 }) == true {
                let processes = scanner.scan(uid: trust.allowedUID, targets: [fixtureID], uptime: uptime)
                for process in processes where process.signingID == fixtureID {
                    guard RootSocketServer.verifiedHash(pid: process.pid)?.lowercased() == fixtureHash else { continue }
                    let verifiedSignal: (Int32, Int32) -> Int32 = { pid, signal in
                        guard RootSocketServer.verifiedHash(pid: pid)?.lowercased() == fixtureHash else { errno = EPERM; return -1 }
                        return Darwin.kill(pid, signal)
                    }
                    event("fixture_attempt", policy: policy)
                    if RootGuardCore.terminate(process, signal: verifiedSignal, force: false) { event("fixture_signal_sent", policy: policy) }
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
    }
    enum Failure: Error { case invalidSetup }
}

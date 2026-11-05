import Foundation
import Darwin
import CoreFoundation

// Dedicated one-shot ROOT QA fixture. It only signals one explicitly bound,
// exact signed synthetic process. It is not a production service.
@main enum StrictFixtureDaemon {
    static let qaUID: uid_t = 501
    static let rootDirectory = "/Library/Application Support/FocusDeckStrictQA"
    static let helperDirectory = "/Library/PrivilegedHelperTools/FocusDeckStrictQA"
    static let executable = helperDirectory + "/StrictRootQADaemon"
    static let fixturePath = helperDirectory + "/GuardFixture"
    static let configPath = helperDirectory + "/config.json"
    static let socketPath = "/tmp/focusdeck-strict-root-qa-501.sock"
    static let target = "dev.stanislav.focusdeck.guard-fixture"
    static let targetHash = "3e481e3d08c534a55081e55313fa2768f44d57bc"
    struct SocketMarker: Codable { let device: UInt64; let inode: UInt64 }
    struct Config: Codable { let clientHash: String; let fixtureHash: String }

    static func main() {
        do {
            if CommandLine.arguments == [CommandLine.arguments[0], "--stop"] { try requestStop(); return }
            if CommandLine.arguments == [CommandLine.arguments[0], "--cleanup"] { try recoverStoppedFixture(); return }
            try run()
        } catch { fputs("strict fixture failed; inspect its dedicated QA paths\n", stderr); exit(1) }
    }
    static func requestStop() throws {
        guard geteuid() == 0, let ownHash = RootSocketServer.verifiedHash(pid: getpid()) else { throw Failure.invalid }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
        var addr = try guardAddress(socketPath)
        guard withUnsafePointer(to: &addr, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }) == 0 else { throw Failure.invalid }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard getpeereid(fd, &uid, &gid) == 0, uid == 0, getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
              RootSocketServer.verifiedHash(pid: pid) == ownHash else { throw Failure.invalid }
        try writeRootFrame(Data("fixture-stop".utf8), fd: fd, timeout: 2)
        guard try readRootFrame(fd, timeout: 2) == Data("fixture-stopping".utf8) else { throw Failure.invalid }
        print("fixture stop acknowledged")
    }
    static func recoverStoppedFixture() throws {
        guard geteuid() == 0 else { throw Failure.invalid }
        var helperInfo = stat(), helperDirInfo = stat(), rootInfo = stat()
        guard lstat(executable, &helperInfo) == 0, helperInfo.st_uid == 0, helperInfo.st_mode & S_IFMT == S_IFREG,
              helperInfo.st_mode & 0o022 == 0, helperInfo.st_nlink == 1,
              lstat(helperDirectory, &helperDirInfo) == 0, helperDirInfo.st_uid == 0, helperDirInfo.st_mode & S_IFMT == S_IFDIR,
              helperDirInfo.st_mode & 0o022 == 0 else { throw Failure.invalid }
        if lstat(rootDirectory, &rootInfo) == 0 {
            guard rootInfo.st_uid == 0, rootInfo.st_mode & S_IFMT == S_IFDIR, rootInfo.st_mode & 0o077 == 0 else { throw Failure.invalid }
            let lock = open(rootDirectory + "/authority.lock", O_RDWR | O_NOFOLLOW | O_CLOEXEC)
            defer { if lock >= 0 { flock(lock, LOCK_UN); close(lock) } }
            if lock >= 0 {
                var lockInfo = stat()
                guard fstat(lock, &lockInfo) == 0, lockInfo.st_uid == 0, lockInfo.st_mode & S_IFMT == S_IFREG,
                      lockInfo.st_mode & 0o077 == 0, lockInfo.st_nlink == 1, flock(lock, LOCK_EX | LOCK_NB) == 0 else { throw Failure.invalid }
            } else if errno != ENOENT { throw Failure.invalid }
            var socketInfo = stat()
            if lstat(socketPath, &socketInfo) == 0 {
                let markerPath = rootDirectory + "/socket-marker.json"; var markerInfo = stat()
                guard socketInfo.st_uid == 0, socketInfo.st_mode & S_IFMT == S_IFSOCK,
                      lstat(markerPath, &markerInfo) == 0, markerInfo.st_uid == 0,
                      markerInfo.st_mode & S_IFMT == S_IFREG, markerInfo.st_mode & 0o077 == 0, markerInfo.st_nlink == 1,
                      let marker = try? JSONDecoder().decode(SocketMarker.self, from: Data(contentsOf: URL(fileURLWithPath: markerPath))),
                      UInt64(socketInfo.st_dev) == marker.device, socketInfo.st_ino == marker.inode else { throw Failure.invalid }
                let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
                var addr = try guardAddress(socketPath)
                let rc = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
                guard rc != 0, errno == ECONNREFUSED else { throw Failure.invalid }
                var current = stat()
                guard lstat(socketPath, &current) == 0, current.st_dev == socketInfo.st_dev, current.st_ino == socketInfo.st_ino,
                      unlink(socketPath) == 0 else { throw Failure.invalid }
            } else if errno != ENOENT { throw Failure.invalid }
            cleanup(directoryInfo: rootInfo, helperInfo: helperInfo, helperDirInfo: helperDirInfo)
            guard lstat(rootDirectory, &rootInfo) != 0, errno == ENOENT else { throw Failure.invalid }
        } else {
            guard errno == ENOENT, lstat(socketPath, &rootInfo) != 0, errno == ENOENT else { throw Failure.invalid }
            cleanupHelper(helperInfo: helperInfo, helperDirInfo: helperDirInfo)
        }
        guard lstat(helperDirectory, &helperDirInfo) != 0, errno == ENOENT else { throw Failure.invalid }
        print("fixture cleanup confirmed")
    }
    static func clock() throws -> StrictRootClock {
        var info = mach_timebase_info_data_t()
        guard mach_timebase_info(&info) == KERN_SUCCESS, info.denom > 0, let boot = WindowPolicy.bootID() else { throw Failure.invalid }
        let seconds = Double(mach_continuous_time()) * Double(info.numer) / Double(info.denom) / 1_000_000_000
        guard seconds.isFinite else { throw Failure.invalid }
        return StrictRootClock(wall: Date(), continuous: seconds, bootID: boot)
    }
    static func run() throws {
        let args = CommandLine.arguments
        guard geteuid() == 0, args.count == 2, args[1] == "--serve" else { throw Failure.invalid }
        var helperInfo = stat(), helperDirInfo = stat()
        guard lstat(executable, &helperInfo) == 0, helperInfo.st_uid == 0, helperInfo.st_mode & S_IFMT == S_IFREG,
              helperInfo.st_mode & 0o022 == 0, helperInfo.st_nlink == 1,
              lstat(helperDirectory, &helperDirInfo) == 0, helperDirInfo.st_uid == 0,
              helperDirInfo.st_mode & S_IFMT == S_IFDIR, helperDirInfo.st_mode & 0o022 == 0 else { throw Failure.invalid }
        let configFD = open(configPath, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
        guard configFD >= 0 else { throw Failure.invalid }; defer { close(configFD) }
        var configInfo = stat(), fixtureInfo = stat()
        guard fstat(configFD, &configInfo) == 0, configInfo.st_uid == 0,
              configInfo.st_mode & S_IFMT == S_IFREG, configInfo.st_mode & 0o077 == 0,
              configInfo.st_nlink == 1, configInfo.st_size > 0, configInfo.st_size <= 1024,
              lstat(fixturePath, &fixtureInfo) == 0, fixtureInfo.st_uid == 0,
              fixtureInfo.st_mode & S_IFMT == S_IFREG, fixtureInfo.st_mode & 0o022 == 0,
              fixtureInfo.st_nlink == 1 else { throw Failure.invalid }
        var bytes = [UInt8](repeating: 0, count: Int(configInfo.st_size))
        guard read(configFD, &bytes, bytes.count) == bytes.count,
              let config = try? JSONDecoder().decode(Config.self, from: Data(bytes)),
              config.clientHash.count == 40, config.clientHash.allSatisfy(\.isHexDigit),
              config.fixtureHash.lowercased() == targetHash else { throw Failure.invalid }
        let clientHash = config.clientHash.lowercased()
        var existing = stat()
        guard lstat(rootDirectory, &existing) != 0, errno == ENOENT,
              lstat(socketPath, &existing) != 0, errno == ENOENT,
              mkdir(rootDirectory, 0o700) == 0 else { throw Failure.invalid }
        var rootInfo = stat(); guard lstat(rootDirectory, &rootInfo) == 0 else { throw Failure.invalid }
        defer { cleanup(directoryInfo: rootInfo, helperInfo: helperInfo, helperDirInfo: helperDirInfo) }

        do {
            let store = try StrictRootStore(directory: rootDirectory, owner: 0)
            let onlyFixture: (TrustedSigningRequirement) -> Bool = { $0.signingID == target && $0.teamID == "SYNTHETIC" && $0.requirement == targetHash }
            let authority = try StrictRootAuthority(store: store, allowedUID: qaUID, allowedHash: clientHash, requirement: onlyFixture)
            let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
            var addr = try guardAddress(socketPath)
            guard withUnsafePointer(to: &addr, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }) == 0 else { throw Failure.invalid }
            var socketInfo = stat(); guard lstat(socketPath, &socketInfo) == 0, socketInfo.st_uid == 0, socketInfo.st_mode & S_IFMT == S_IFSOCK else { throw Failure.invalid }
            defer {
                var current = stat()
                if lstat(socketPath, &current) == 0 && current.st_uid == 0 && current.st_mode & S_IFMT == S_IFSOCK && current.st_dev == socketInfo.st_dev && current.st_ino == socketInfo.st_ino { unlink(socketPath) }
            }
            let marker = try JSONEncoder().encode(SocketMarker(device: UInt64(socketInfo.st_dev), inode: socketInfo.st_ino))
            let markerFD = open(rootDirectory + "/socket-marker.json", O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard markerFD >= 0 else { throw Failure.invalid }
            let markerWritten = marker.withUnsafeBytes { Darwin.write(markerFD, $0.baseAddress!, $0.count) }
            let markerSynced = fsync(markerFD); close(markerFD)
            guard markerWritten == marker.count, markerSynced == 0 else { throw Failure.invalid }
            guard chmod(socketPath, 0o666) == 0, listen(fd, 4) == 0 else { throw Failure.invalid }
            let boot = try clock(); let stopAt = boot.continuous + 120
            var bound = false; var attempted = Set<String>(); var stopping = false
            let ownHash = RootSocketServer.verifiedHash(pid: getpid())
            guard ownHash != nil else { throw Failure.invalid }
            while !stopping {
                let now = try clock(); if now.continuous >= stopAt || now.bootID != boot.bootID { break }
                let committed = try authority.tick(at: now)
                if let record = committed, record.enforcementRequested, bound {
                    // This fixture scans paths only to select its fixed root-owned
                    // executable; it never inspects or signals any other target.
                    for candidatePID in ProcessIdentity.pids() where candidatePID > 1 {
                        var path = [CChar](repeating: 0, count: Int(MAXPATHLEN) * 4)
                        guard proc_pidpath(candidatePID, &path, UInt32(path.count)) > 0,
                              String(cString: path) == fixturePath,
                              let process = ProcessIdentity.inspect(candidatePID), process.uid == qaUID,
                              process.signingID == target,
                              RootSocketServer.verifiedHash(pid: candidatePID)?.lowercased() == targetHash else { continue }
                        let key = "\(process.pid):\(process.startSeconds):\(process.startMicros)"
                        guard !attempted.contains(key) else { continue }
                        let safeSignal: (Int32, Int32) -> Int32 = { pid, signal in
                            var livePath = [CChar](repeating: 0, count: Int(MAXPATHLEN) * 4)
                            guard proc_pidpath(pid, &livePath, UInt32(livePath.count)) > 0,
                                  String(cString: livePath) == fixturePath,
                                  RootSocketServer.verifiedHash(pid: pid)?.lowercased() == targetHash else { return -1 }
                            return Darwin.kill(pid, signal)
                        }
                        if RootGuardCore.terminate(process, signal: safeSignal) { attempted.insert(key) }
                    }
                }
                var pollInfo = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
                guard poll(&pollInfo, 1, 250) > 0 else { continue }
                let client = accept(fd, nil, nil); if client < 0 { continue }
                defer { close(client) }
                var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
                guard getpeereid(client, &uid, &gid) == 0,
                      getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
                      let frame = try? readRootFrame(client, timeout: 0.5) else { continue }
                let peerHash = RootSocketServer.verifiedHash(pid: pid)?.lowercased()
                if uid == 0, peerHash == ownHash?.lowercased(), frame == Data("fixture-stop".utf8) {
                    try? writeRootFrame(Data("fixture-stopping".utf8), fd: client, timeout: 0.5); stopping = true; continue
                }
                guard uid == qaUID, peerHash == clientHash else { continue }
                let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
                if let raw = try? JSONSerialization.jsonObject(with: frame) as? [String: Any],
                   raw["kind"] as? String == "fixture-bind", Set(raw.keys) == ["schemaVersion", "kind", "pid"],
                   raw["schemaVersion"] as? Int == 1, let number = raw["pid"] as? NSNumber,
                   CFGetTypeID(number) != CFBooleanGetTypeID(), number.int64Value > 1, number.int64Value <= Int32.max {
                    let candidate = ProcessIdentity.inspect(number.int32Value)
                    let permitted = candidate.map { $0.uid == qaUID && $0.pid != getpid() && $0.signingID == target && RootSocketServer.verifiedHash(pid: $0.pid)?.lowercased() == targetHash } ?? false
                    var acceptedBinding = false
                    if permitted, let candidate, candidate.uid == qaUID {
                        var path = [CChar](repeating: 0, count: Int(MAXPATHLEN) * 4)
                        acceptedBinding = proc_pidpath(candidate.pid, &path, UInt32(path.count)) > 0 && String(cString: path) == fixturePath
                        if acceptedBinding { bound = true }
                    }
                    let snapshot = try authority.tick(at: now)?.snapshot()
                    let reply = StrictRootReply(schemaVersion: 1, accepted: acceptedBinding, reason: acceptedBinding ? "fixture_bound" : "fixture_target_rejected", snapshot: snapshot)
                    if let response = try? reply.frame() { try? writeRootFrame(response, fd: client, timeout: 0.5) }
                    continue
                }
                guard let envelope = try? decoder.decode(StrictRootEnvelope.self, from: frame) else { continue }
                if envelope.kind == .enroll {
                    guard let plan = envelope.enrollment?.plan,
                          plan.appRequirements.count == 1, plan.appRequirements.allSatisfy(onlyFixture),
                          plan.siteDomains.isEmpty, plan.siteSubdomains == false,
                          plan.endsAt.timeIntervalSince(plan.startsAt) <= 90,
                          plan.endsAt.timeIntervalSince(now.wall) <= 90 else { continue }
                }
                if let reply = try? authority.handle(envelope, peerUID: uid, peerHash: clientHash, at: now),
                   let response = try? reply.frame() { try? writeRootFrame(response, fd: client, timeout: 0.5) }
            }
        }
    }
    // Remove only our root-owned, pre-recorded fixtures; refuse unexpected files.
    static func cleanup(directoryInfo: stat, helperInfo: stat, helperDirInfo: stat) {
        let fm = FileManager.default
        func safeDirectory(_ path: String, original: stat, files: Set<String>) -> Bool {
            var current = stat()
            guard lstat(path, &current) == 0, current.st_uid == 0, current.st_mode & S_IFMT == S_IFDIR,
                  current.st_dev == original.st_dev, current.st_ino == original.st_ino,
                  let entries = try? fm.contentsOfDirectory(atPath: path), Set(entries).isSubset(of: files) else { return false }
            for name in entries {
                var file = stat(); guard lstat(path + "/" + name, &file) == 0, file.st_uid == 0,
                    file.st_mode & S_IFMT == S_IFREG, file.st_nlink == 1 else { return false }
                if name == "StrictRootQADaemon" && (file.st_dev != helperInfo.st_dev || file.st_ino != helperInfo.st_ino) { return false }
            }
            for name in entries { if unlink(path + "/" + name) != 0 { return false } }
            return rmdir(path) == 0
        }
        _ = safeDirectory(rootDirectory, original: directoryInfo, files: ["authority.lock", "record.json", "socket-marker.json"])
        _ = safeDirectory(helperDirectory, original: helperDirInfo, files: ["StrictRootQADaemon", "GuardFixture", "config.json", "fixture-qa-driver.py", "FixtureQASupervisor"])
    }
    static func cleanupHelper(helperInfo: stat, helperDirInfo: stat) {
        var unused = stat()
        cleanup(directoryInfo: unused, helperInfo: helperInfo, helperDirInfo: helperDirInfo)
    }
    enum Failure: Error { case invalid }
}

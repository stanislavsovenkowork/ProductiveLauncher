import Foundation
import Darwin
import CryptoKit

private struct CandidateBinding: Decodable { let approvedWindow: WindowPolicy }
private struct CandidateReady: Decodable { let trustDigest: String; let policyRevisionId: UUID }
// Production-scope SOURCE CANDIDATE, not installed or approved for live enforcement.
// Exact selected signing IDs only. Never enables hosts, DNS or website filtering.
@main enum WindowGuardV2ProductionDaemon {
    static let directory = "/Library/Application Support/FocusDeckGuardV2Candidate"
    static func main() throws {
        guard CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--candidate-daemon", geteuid() == 0 else { throw Failure.invalidSetup }
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
        let bindingDecoder = JSONDecoder(); bindingDecoder.dateDecodingStrategy = .iso8601
        let approved = try bindingDecoder.decode(CandidateBinding.self, from: trustData).approvedWindow
        let readyPath = directory + "/ready.json"; var readyInfo = stat()
        guard lstat(readyPath, &readyInfo) == 0, readyInfo.st_uid == 0, readyInfo.st_mode & S_IFMT == S_IFREG,
              readyInfo.st_mode & 0o077 == 0, readyInfo.st_size <= 16384 else { throw Failure.invalidSetup }
        let ready = try JSONDecoder().decode(CandidateReady.self, from: Data(contentsOf: URL(fileURLWithPath: readyPath)))
        let targets = Set(trust.blockedSigningIDs)
        guard let signedRequirements = trust.signingRequirements, signedRequirements.count == targets.count,
              Set(signedRequirements.map(\.signingID)) == targets,
              signedRequirements.allSatisfy(ProcessSignatureRequirement.valid) else { throw Failure.invalidSetup }
        let requirements = Dictionary(uniqueKeysWithValues: signedRequirements.map { ($0.signingID, $0) })
        let trustDigest = SHA256.hash(data: trustData).map { String(format: "%02x", $0) }.joined()
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let parts = calendar.dateComponents([.year, .month, .day, .hour], from: approved.startsAt)
        let localDay = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
        guard ready.trustDigest == trustDigest, ready.policyRevisionId == approved.policyRevisionId,
              approved.policyVersion == 2, approved.timezone == "Europe/Minsk", (parts.hour ?? -1) >= 15,
              localDay == approved.localDate, calendar.isDate(approved.startsAt, inSameDayAs: approved.endsAt),
              approved.startsAt < approved.endsAt, approved.endsAt.timeIntervalSince(approved.startsAt) <= 9 * 3600,
              (0...3600).contains(approved.reliefBudgetSeconds), (1...900).contains(approved.reliefMaxGrantSeconds),
              (1...120).contains(approved.exitCoolingOffSeconds), approved.state == .inactive,
              approved.reliefRemainingSeconds == approved.reliefBudgetSeconds, approved.reliefUntil == nil, approved.exitAvailableAt == nil,
              approved.exitBootID == nil, approved.exitUptimeDeadline == nil, approved.stateBeforeExitPending == nil,
              Set(approved.targetRefs) == targets else { throw Failure.invalidSetup }
        guard !targets.isEmpty, targets.count == trust.blockedSigningIDs.count, targets.count <= 64,
              targets.allSatisfy(WindowGuardV2.validSigningID), !targets.contains(where: WindowGuardV2.isProtected),
              trust.blockedDomains.isEmpty else { throw Failure.invalidSetup }
        let socketPath = WindowGuardV2Client.path
        var old = stat()
        // A root-owned socket is not necessarily ours. Never remove or replace it.
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
        let diskStore = CandidateDiskStore(directory: URL(fileURLWithPath: directory), expectedOwner: 0)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let disk = try diskStore.load() // missing, partial or old formats fail closed; never recreate
        guard disk.matches(approved: approved, trustDigest: trustDigest), let boot = WindowPolicy.bootID() else { throw Failure.invalidSetup }
        if disk.bootID == "prepared" {
            guard disk.policy.state == .inactive, disk.policy.reliefRemainingSeconds == disk.policy.reliefBudgetSeconds,
                  disk.policy.reliefUntil == nil, disk.policy.exitAvailableAt == nil else { throw Failure.invalidSetup }
        }
        var current: WindowPolicy? = disk.policy
        var cap: Double? = disk.bootID == boot ? disk.uptimeDeadline : ProcessInfo.processInfo.systemUptime + max(0, disk.policy.endsAt.timeIntervalSinceNow)
        var reliefCap: Double?
        if let until = disk.policy.reliefUntil {
            reliefCap = disk.bootID == boot ? disk.reliefUptimeDeadline : ProcessInfo.processInfo.systemUptime + max(0, until.timeIntervalSinceNow)
        }
        func persist(_ p: WindowPolicy, deadline: Double) throws {
            let state = CandidateDiskState(policy: p, bootID: boot, uptimeDeadline: deadline, reliefUptimeDeadline: reliefCap, trustDigest: trustDigest)
            guard state.matches(approved: approved, trustDigest: trustDigest) else { throw Failure.invalidSetup }
            try diskStore.save(state)
        }
        // The boot/cap transition is durable before this daemon can answer a request.
        try persist(disk.policy, deadline: cap!)
        func event(_ name: String, policy: WindowPolicy) {
            let path = directory + "/candidate-events.jsonl"
            let row = ["event": name, "windowId": policy.windowId.uuidString, "at": ISO8601DateFormatter().string(from: Date())]
            guard var data = try? JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]) else { return }; data.append(10)
            if !FileManager.default.fileExists(atPath: path) { FileManager.default.createFile(atPath: path, contents: nil, attributes: [.posixPermissions: 0o600]) }
            guard let handle = try? FileHandle(forWritingTo: URL(fileURLWithPath: path)) else { return }
            defer { try? handle.close() }; _ = try? handle.seekToEnd(); try? handle.write(contentsOf: data)
        }
        let guardCore = WindowGuardV2(allowedTargets: targets, trustedUID: trust.allowedUID, trustedHash: trust.uiCDHash, requireCalendar: true)
        var scanner = GuardProcessScanner()
        var attempted: [String: TimeInterval] = [:]
        while true {
            // Never recreate a missing or independently changed ledger from RAM.
            let observedDisk = try diskStore.load()
            guard observedDisk.matches(approved: approved, trustDigest: trustDigest), observedDisk.bootID == boot,
                  observedDisk.policy == current, observedDisk.uptimeDeadline == cap,
                  observedDisk.reliefUptimeDeadline == reliefCap else { throw Failure.invalidSetup }
            let client = accept(fd, nil, nil)
            if client >= 0 {
                var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, length = socklen_t(MemoryLayout<Int32>.size)
                if getpeereid(client, &uid, &gid) == 0, getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &length) == 0,
                   let data = try? readRootFrame(client, timeout: 0.5) {
                    if WindowCandidateRecoveryCore.authorized(uid: uid, payload: data) {
                        guard var policy = current else { throw Failure.invalidSetup }
                        policy.state = .exit; policy.reliefUntil = nil; policy.exitAvailableAt = nil; policy.exitUptimeDeadline = nil; policy.exitBootID = nil; policy.stateBeforeExitPending = nil
                        reliefCap = nil; try persist(policy, deadline: cap ?? ProcessInfo.processInfo.systemUptime)
                        current = policy; event("explicit_administrator_recovery", policy: policy)
                        try writeRootFrame(WindowCandidateRecoveryCore.reply, fd: client, timeout: 0.5)
                        close(client); return
                    }
                    guard let request = try? decoder.decode(WindowGuardRequest.self, from: data) else { close(client); continue }
                    let (result, proposedReply) = guardCore.apply(request, current: current, peerUID: uid, peerHash: RootSocketServer.verifiedHash(pid: pid), now: Date())
                    let reply: WindowGuardReply
                    if proposedReply.accepted && !WindowCandidateEnrollmentGate.permits(existing: current, command: request.command) {
                        reply = WindowGuardReply(accepted: false, reason: "enrollment_unavailable", windowId: nil, enforcement: "unknown", policy: nil)
                    } else { reply = proposedReply }
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
            if var policy = current, policy.state == .inactive, now >= policy.startsAt, now < policy.endsAt {
                policy.state = .active; try persist(policy, deadline: cap ?? uptime); current = policy; event("window_started", policy: policy)
            }
            if var policy = current, let until = policy.reliefUntil, now >= until || (reliefCap.map { uptime >= $0 } ?? false) {
                if policy.state == .limitedRelief { policy.state = .active }
                if policy.state == .exitPending && policy.stateBeforeExitPending == .limitedRelief { policy.stateBeforeExitPending = .active }
                policy.reliefUntil = nil; reliefCap = nil; try persist(policy, deadline: cap ?? uptime); current = policy; event("relief_ended", policy: policy)
            }
            if var policy = current, policy.state != .expired && policy.state != .exit,
               now >= policy.endsAt || (cap.map { uptime >= $0 } ?? false) {
                policy.state = .expired; try persist(policy, deadline: cap ?? uptime); current = policy; event("window_expired", policy: policy)
            }
            if let policy = current, Set(policy.targetRefs) == targets, WindowGuardV2.enforcing(policy, now: now), cap.map({ uptime < $0 }) == true {
                let processes = scanner.scan(uid: trust.allowedUID, targets: targets, uptime: uptime)
                for process in processes {
                    guard let signingID = process.signingID, targets.contains(signingID), !WindowGuardV2.isProtected(signingID),
                          let requirement = requirements[signingID], ProcessSignatureRequirement.matches(pid: process.pid, expected: requirement) else { continue }
                    let verifiedSignal: (Int32, Int32) -> Int32 = { pid, signal in
                        guard ProcessSignatureRequirement.matches(pid: pid, expected: requirement) else { errno = EPERM; return -1 }
                        return Darwin.kill(pid, signal)
                    }
                    let key = "\(process.pid):\(process.startSeconds):\(process.startMicros)"
                    if let previous = attempted[key] {
                        guard uptime - previous >= 2 else { continue }
                        if RootGuardCore.terminate(process, signal: verifiedSignal, force: true) { event("force_signal_sent", policy: policy) }
                        attempted[key] = uptime
                    } else {
                        event("selected_app_attempt", policy: policy)
                        if RootGuardCore.terminate(process, signal: verifiedSignal, force: false) { event("signal_sent", policy: policy) }
                        attempted[key] = uptime
                    }
                }
            } else { attempted.removeAll() }
            Thread.sleep(forTimeInterval: 0.25)
        }
    }
    enum Failure: Error { case invalidSetup }
}

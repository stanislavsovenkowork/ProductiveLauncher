import Foundation
import Darwin
import Security
import CryptoKit

// User-only lifecycle manager. It never mutates the protection ledger or a
// service it cannot identify as its own. No root, network, or trust changes.
private struct Record: Codable {
    let version: Int
    let agent: String
    let agentHash: String
    let plistHash: String
}
private struct Answer: Codable { let ok: Bool; let state: String; let reason: String }
private struct Problem: Error { let reason: String; init(_ reason: String) { self.reason = reason } }
private func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
private func checkFile(_ path: String, kind: mode_t, privateMode: Bool = false) throws {
    var st = stat()
    guard lstat(path, &st) == 0, st.st_uid == getuid(), st.st_mode & S_IFMT == kind,
          !privateMode || st.st_mode & 0o077 == 0 else { throw Problem("untrusted_file") }
}
private func staticHash(_ path: String, identifier: String) throws -> String {
    var code: SecStaticCode?
    guard SecStaticCodeCreateWithPath(URL(fileURLWithPath: path) as CFURL, [], &code) == errSecSuccess,
          let code, SecStaticCodeCheckValidity(code, [], nil) == errSecSuccess else { throw Problem("signature_invalid") }
    var details: CFDictionary?
    guard SecCodeCopySigningInformation(code, [], &details) == errSecSuccess,
          let info = details as? [String: Any], info[kSecCodeInfoIdentifier as String] as? String == identifier,
          let hash = info[kSecCodeInfoUnique as String] as? Data else { throw Problem("signature_invalid") }
    return hash.map { String(format: "%02x", $0) }.joined()
}
private func run(_ executable: String, _ arguments: [String], allowedFailure: Bool = false) throws -> (Int32, String) {
    let p = Process(); p.executableURL = URL(fileURLWithPath: executable); p.arguments = arguments
    let output = Pipe(); p.standardOutput = output; p.standardError = FileHandle.nullDevice
    do { try p.run() } catch { throw Problem("command_failed") }
    let data = output.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
    if p.terminationStatus != 0 && !allowedFailure { throw Problem("command_failed") }
    return (p.terminationStatus, String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines))
}
private struct Paths {
    let job: URL, root: URL, socket: String, ledger: URL, chrome: URL, agent: URL, host: URL
    let label: String, smLabel: String, test: Bool
    var record: URL { root.appendingPathComponent("owner.json") }
    var lock: URL { root.appendingPathComponent("lock") }
    var journal: URL { root.appendingPathComponent("update-incomplete") }
    var domain: String { "gui/\(getuid())" }
    var service: String { "\(domain)/\(label)" }
    init() throws {
        #if USER_AGENT_MANAGER_TEST
        let env = ProcessInfo.processInfo.environment
        guard let rootValue = env["FD_MANAGER_QA_ROOT"], rootValue.hasPrefix("/tmp/fd-manager-qa-"),
              let source = env["FD_MANAGER_QA_APP"], source.hasPrefix(rootValue + "/"),
              let value = env["FD_MANAGER_QA_LABEL"], value.hasPrefix("dev.stanislav.focusdeck.manager-qa-") else { throw Problem("invalid_test_configuration") }
        let base = URL(fileURLWithPath: rootValue, isDirectory: true)
        root = base.appendingPathComponent("manager"); job = base.appendingPathComponent("jobs/\(value).plist")
        socket = base.appendingPathComponent("agent.sock").path; ledger = base.appendingPathComponent("ledger")
        chrome = base.appendingPathComponent("chrome/dev.stanislav.focusdeck.sites.json")
        agent = URL(fileURLWithPath: source).appendingPathComponent("Contents/Library/HelperTools/UserProtectionAgent")
        host = URL(fileURLWithPath: source).appendingPathComponent("Contents/Library/HelperTools/FocusDeckSiteHost")
        label = value; smLabel = value + ".sm"; test = true
        #else
        let home = FileManager.default.homeDirectoryForCurrentUser
        label = "dev.stanislav.focusdeck.userprotection.manual"; smLabel = "dev.stanislav.focusdeck.userprotection"; test = false
        job = home.appendingPathComponent("Library/LaunchAgents/\(label).plist")
        root = home.appendingPathComponent("Library/Application Support/FocusDeckUserAgentManager", isDirectory: true)
        socket = "/tmp/focusdeck-user-protection-\(getuid()).sock"
        ledger = home.appendingPathComponent("Library/Application Support/FocusDeckUserProtectionAgent", isDirectory: true)
        chrome = home.appendingPathComponent("Library/Application Support/Google/Chrome/NativeMessagingHosts/dev.stanislav.focusdeck.sites.json")
        let here = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL.resolvingSymlinksInPath().deletingLastPathComponent()
        agent = here.appendingPathComponent("UserProtectionAgent"); host = here.appendingPathComponent("FocusDeckSiteHost")
        #endif
    }
}
private final class Manager {
    let p: Paths
    var lockFD: Int32 = -1
    init(_ p: Paths) throws {
        self.p = p
        let fm = FileManager.default
        if !fm.fileExists(atPath: p.root.path) { try fm.createDirectory(at: p.root, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
        try checkFile(p.root.path, kind: S_IFDIR, privateMode: true)
        lockFD = open(p.lock.path, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard lockFD >= 0, flock(lockFD, LOCK_EX | LOCK_NB) == 0 else { throw Problem("busy") }
    }
    deinit { if lockFD >= 0 { _ = flock(lockFD, LOCK_UN); close(lockFD) } }
    func exists(_ path: String) -> Bool { var s = stat(); return lstat(path, &s) == 0 }
    func loaded(_ label: String) throws -> Bool {
        var executable = "/bin/launchctl"
        #if USER_AGENT_MANAGER_TEST
        if let fake = ProcessInfo.processInfo.environment["FD_MANAGER_QA_LAUNCHCTL"] {
            guard let root = ProcessInfo.processInfo.environment["FD_MANAGER_QA_ROOT"], fake.hasPrefix(root + "/") else { throw Problem("launchctl_unknown") }
            executable = fake
        }
        #endif
        let process = Process(); process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = ["print", "\(p.domain)/\(label)"]
        var environment = ProcessInfo.processInfo.environment; environment["LC_ALL"] = "C"; process.environment = environment
        process.standardOutput = FileHandle.nullDevice
        let error = Pipe(); process.standardError = error
        do { try process.run() } catch { throw Problem("launchctl_unknown") }
        let diagnostic = String(decoding: error.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        process.waitUntilExit()
        if process.terminationStatus == 0 { return true }
        let absent = "Could not find service \"\(label)\" in domain for user gui: \(getuid())"
        if process.terminationStatus == 113 && diagnostic.contains(absent) { return false }
        throw Problem("launchctl_unknown")
    }
    func verifyCandidate() throws -> String {
        let agent = p.agent.standardizedFileURL.resolvingSymlinksInPath().path
        let hash = try staticHash(agent, identifier: "dev.stanislav.focusdeck.userprotection.agent")
        _ = try staticHash(p.host.path, identifier: "dev.stanislav.focusdeck.sites.host")
        let pin = try run(p.host.path, ["--agent-hash"]).1.lowercased()
        guard pin == hash else { throw Problem("chrome_pin_mismatch") }
        return hash
    }
    func readRecord() throws -> (Record, Data) {
        try checkFile(p.record.path, kind: S_IFREG, privateMode: true)
        try checkFile(p.job.path, kind: S_IFREG, privateMode: true)
        let record = try JSONDecoder().decode(Record.self, from: Data(contentsOf: p.record))
        let bytes = try Data(contentsOf: p.job)
        guard record.version == 1, digest(bytes) == record.plistHash,
              let plist = try PropertyListSerialization.propertyList(from: bytes, format: nil) as? [String: Any],
              plist["Label"] as? String == p.label,
              let args = plist["ProgramArguments"] as? [String], args == [record.agent],
              try staticHash(record.agent, identifier: "dev.stanislav.focusdeck.userprotection.agent") == record.agentHash else { throw Problem("ownership_mismatch") }
        return (record, bytes)
    }
    func plist(_ agent: String) throws -> Data {
        var content: [String: Any] = ["Label":p.label, "ProgramArguments":[agent], "RunAtLoad":true,
            "KeepAlive":["SuccessfulExit":false], "ThrottleInterval":30, "Umask":63,
            "StandardOutPath":"/dev/null", "StandardErrorPath":"/dev/null"]
        #if USER_AGENT_MANAGER_TEST
        content["EnvironmentVariables"] = ["FOCUSDECK_USER_TEST_SOCKET":p.socket, "FOCUSDECK_USER_TEST_DIR":p.ledger.path]
        #endif
        return try PropertyListSerialization.data(fromPropertyList: content, format: .xml, options: 0)
    }
    func saveRecord(_ record: Record) throws {
        try JSONEncoder().encode(record).write(to: p.record, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p.record.path)
    }
    func saveJob(_ data: Data, replacing expected: Data? = nil) throws {
        if let expected {
            guard try Data(contentsOf: p.job) == expected else { throw Problem("ownership_mismatch") }
            try data.write(to: p.job, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p.job.path)
        } else {
            let fd = open(p.job.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard fd >= 0 else { throw Problem("job_conflict") }
            defer { close(fd) }
            try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: data)
            guard fsync(fd) == 0 else { throw Problem("write_failed") }
        }
    }
    func peerStatus(_ hash: String) throws -> WindowGuardReply {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Problem("agent_unknown") }; defer { close(fd) }
        var addr = try guardAddress(p.socket)
        let rc = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard rc == 0, getpeereid(fd, &uid, &gid) == 0, uid == getuid(),
              getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
              RootSocketServer.verifiedHash(pid: pid)?.lowercased() == hash else { throw Problem("agent_unknown") }
        let now = Date(), stub = WindowPolicy(windowId: UUID(), localDate: "status", startsAt: now, endsAt: now.addingTimeInterval(60), reliefBudgetSeconds: 0, reliefRemainingSeconds: 0, reliefMaxGrantSeconds: 1, targetRefs: [])
        let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
        try writeRootFrame(enc.encode(WindowGuardRequest(policy: stub, command: "status")), fd: fd, timeout: 2)
        let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
        let reply = try dec.decode(WindowGuardReply.self, from: readRootFrame(fd, timeout: 2))
        guard reply.accepted, ["off", "scheduled"].contains(reply.scheduleStatus ?? ""), ["released", "selected_apps_requested"].contains(reply.enforcement) else { throw Problem("agent_unknown") }
        return reply
    }
    func awaitPeer(_ hash: String) throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 8
        repeat { if (try? peerStatus(hash)) != nil { return }; usleep(100_000) } while ProcessInfo.processInfo.systemUptime < deadline
        throw Problem("agent_unknown")
    }
    func waitStopped() throws {
        let deadline = ProcessInfo.processInfo.systemUptime + 8
        repeat { let running = try loaded(p.label); if !running && !exists(p.socket) { return }; usleep(100_000) } while ProcessInfo.processInfo.systemUptime < deadline
        throw Problem("stop_unconfirmed")
    }
    func stop() throws { _ = try run("/bin/launchctl", ["bootout",p.service]); try waitStopped() }
    func start() throws { _ = try run("/bin/launchctl", ["bootstrap",p.domain,p.job.path]) }
    func ledgerBytes() throws -> Data? {
        let file = p.ledger.appendingPathComponent("protection-ledger.json")
        if !exists(file.path) { return nil }
        try checkFile(p.ledger.path, kind: S_IFDIR, privateMode: true); try checkFile(file.path, kind: S_IFREG, privateMode: true)
        return try Data(contentsOf: file)
    }
    // Inspect durable state before launching the agent: its scan loop can act
    // before any status reply. Never mutate or reset this ledger here.
    func preStartLedger() throws -> Data? {
        var info = stat()
        if lstat(p.ledger.path, &info) != 0 {
            guard errno == ENOENT else { throw Problem("agent_unknown") }
            return nil
        }
        try checkFile(p.ledger.path, kind: S_IFDIR, privateMode: true)
        let before = try ledgerBytes()
        guard before != nil else { return nil }
        let store = UserProtectionLedgerStore(directory: p.ledger, owner: getuid())
        #if USER_AGENT_MANAGER_TEST
        let ledger = try store.load(allowSynthetic: true)
        #else
        let ledger = try store.load()
        #endif
        guard let ledger, try ledgerBytes() == before else { throw Problem("agent_unknown") }
        guard ledger.clockUncertain != true else { throw Problem("clock_unknown") }
        let now = Date(), uptime = ProcessInfo.processInfo.systemUptime
        switch ledger.policy.state {
        case .active, .limitedRelief, .rest, .exitPending:
            throw Problem("window_active")
        case .inactive:
            guard now < ledger.policy.startsAt, now < ledger.policy.endsAt else { throw Problem("window_active") }
            if scheduleStartIsNear(ledger.policy.startsAt, now: now) { throw Problem("schedule_boundary") }
        case .exit, .expired: break
        }
        if let schedule = ledger.schedule {
            guard let anchor = ledger.clockAnchor, let boot = WindowPolicy.bootID(), boot == anchor.boot,
                  uptime.isFinite, uptime >= anchor.uptime,
                  abs(now.timeIntervalSince(anchor.wall) - (uptime - anchor.uptime)) <= 3,
                  let issued = ledger.lastIssuedLocalDate else { throw Problem("clock_unknown") }
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
            for day in [now, calendar.date(byAdding: .day, value: 1, to: now)!] {
                if let window = schedule.window(on: day), window.endsAt > now {
                    if scheduleStartIsNear(window.startsAt, now: now) { throw Problem("schedule_boundary") }
                    // An unissued window that has already started would be
                    // activated by the agent before the first status reply.
                    if window.startsAt <= now && window.localDate > issued { throw Problem("schedule_would_start") }
                }
            }
        }
        return before
    }
    func safeWindow(_ reply: WindowGuardReply) throws {
        guard reply.enforcement == "released", reply.policy == nil || [.exit, .expired].contains(reply.policy!.state) else { throw Problem("window_active") }
        if reply.scheduleStatus == "scheduled" {
            let store = UserProtectionLedgerStore(directory: p.ledger, owner: getuid())
            #if USER_AGENT_MANAGER_TEST
            let state = try store.load(allowSynthetic: true)
            #else
            let state = try store.load()
            #endif
            guard let state, let schedule = state.schedule, state.clockUncertain != true else { throw Problem("agent_unknown") }
            let now = Date(); var c = Calendar(identifier: .gregorian); c.timeZone = TimeZone(identifier: "Europe/Minsk")!
            for day in [now, c.date(byAdding: .day, value: 1, to: now)!] {
                if let window = schedule.window(on: day), window.endsAt > now, scheduleStartIsNear(window.startsAt, now: now) { throw Problem("schedule_boundary") }
            }
        }
    }
    func status() throws -> Answer {
        if exists(p.journal.path) { throw Problem("recovery_required") }
        let running = try loaded(p.label)
        guard exists(p.record.path) || exists(p.job.path) || running else { return Answer(ok:true,state:"absent",reason:"not_installed") }
        let (record, _) = try readRecord()
        let smRunning = try loaded(p.smLabel)
        guard running, !smRunning else { throw Problem("agent_unknown") }
        let reply = try peerStatus(record.agentHash)
        let active = reply.policy.map { ![.exit, .expired].contains($0.state) } ?? false
        return Answer(ok:true,state:active ? "active" : "ready",reason:reply.scheduleStatus == "scheduled" ? "schedule_enabled" : "connected")
    }
    func install() throws -> Answer {
        let running = try loaded(p.label), smRunning = try loaded(p.smLabel)
        guard !exists(p.journal.path), !exists(p.record.path), !exists(p.job.path), !running, !smRunning, !exists(p.socket) else { throw Problem("service_conflict") }
        let hash = try verifyCandidate()
        guard !exists(p.chrome.path) else { throw Problem("chrome_host_registered") }
        let snapshot = try preStartLedger()
        let agent = p.agent.standardizedFileURL.resolvingSymlinksInPath().path
        let bytes = try plist(agent), rec = Record(version:1,agent:agent,agentHash:hash,plistHash:digest(bytes))
        try FileManager.default.createDirectory(at:p.job.deletingLastPathComponent(),withIntermediateDirectories:true)
        do {
            try saveJob(bytes); try saveRecord(rec)
            guard try preStartLedger() == snapshot else { throw Problem("ledger_changed") }
            guard try !loaded(p.smLabel), !exists(p.socket) else { throw Problem("service_conflict") }
            try start(); try awaitPeer(hash)
            guard try !loaded(p.smLabel) else { throw Problem("service_conflict") }
            return Answer(ok:true,state:"ready",reason:"installed")
        }
        catch {
            let original = (error as? Problem)?.reason
            if try loaded(p.label) { try? stop() }
            guard try !loaded(p.label), !exists(p.socket), (try? Data(contentsOf:p.job)) == bytes else { throw Problem("recovery_required") }
            try? FileManager.default.removeItem(at:p.job); try? FileManager.default.removeItem(at:p.record)
            if let original, ["window_active","clock_unknown","schedule_boundary","schedule_would_start","ledger_changed","agent_unknown","service_conflict"].contains(original) { throw Problem(original) }
            throw Problem("install_failed")
        }
    }
    func update() throws -> Answer {
        guard !exists(p.journal.path), try !loaded(p.smLabel) else { throw Problem("service_conflict") }
        guard !exists(p.chrome.path) else { throw Problem("chrome_host_registered") }
        let (old, oldPlist) = try readRecord()
        guard try loaded(p.label) else { throw Problem("agent_unknown") }
        try safeWindow(peerStatus(old.agentHash))
        let newHash = try verifyCandidate(), agent = p.agent.standardizedFileURL.resolvingSymlinksInPath().path
        guard agent != old.agent else { throw Problem("new_bundle_required") }
        let newPlist = try plist(agent), next = Record(version:1,agent:agent,agentHash:newHash,plistHash:digest(newPlist))
        let snapshot = try ledgerBytes()
        try safeWindow(peerStatus(old.agentHash))
        // A surviving marker makes interruption explicit; no silent recovery.
        try Data("update in progress\n".utf8).write(to:p.journal,options:.withoutOverwriting)
        var changed = false
        do {
            try stop()
            guard try ledgerBytes() == snapshot else { throw Problem("ledger_changed") }
            try saveJob(newPlist,replacing:oldPlist); changed = true; try saveRecord(next)
            try start(); try awaitPeer(newHash)
            guard try ledgerBytes() == snapshot else { throw Problem("ledger_changed") }
            try FileManager.default.removeItem(at:p.journal)
            return Answer(ok:true,state:"ready",reason:"updated")
        } catch {
            if try loaded(p.label) { try? stop() }
            do {
                guard !exists(p.socket), try !loaded(p.label) else { throw Problem("recovery_required") }
                if changed { try saveJob(oldPlist,replacing:newPlist); try saveRecord(old) }
                try start(); try awaitPeer(old.agentHash)
                try FileManager.default.removeItem(at:p.journal)
            } catch { throw Problem("recovery_required") }
            throw Problem("update_rolled_back")
        }
    }
    func uninstall() throws -> Answer {
        guard !exists(p.journal.path), try !loaded(p.smLabel) else { throw Problem("service_conflict") }
        let (record, bytes) = try readRecord()
        guard try loaded(p.label) else { throw Problem("agent_unknown") }
        try safeWindow(peerStatus(record.agentHash))
        let snapshot = try ledgerBytes()
        try safeWindow(peerStatus(record.agentHash))
        try stop()
        guard try ledgerBytes() == snapshot else {
            do { try start(); try awaitPeer(record.agentHash) } catch { throw Problem("recovery_required") }
            throw Problem("ledger_changed")
        }
        guard try Data(contentsOf:p.job) == bytes else { throw Problem("ownership_mismatch") }
        try FileManager.default.removeItem(at:p.job); try FileManager.default.removeItem(at:p.record)
        return Answer(ok:true,state:"absent",reason:"uninstalled_ledger_retained")
    }
}
@main enum UserAgentManager {
 static func main() {
    do {
        guard CommandLine.arguments.count == 2 else { throw Problem("invalid_command") }
        let manager = try Manager(Paths()), answer: Answer
        switch CommandLine.arguments[1] {
        case "status": answer = try manager.status()
        case "install": answer = try manager.install()
        case "update": answer = try manager.update()
        case "uninstall": answer = try manager.uninstall()
        default: throw Problem("invalid_command")
        }
        print(String(decoding: try JSONEncoder().encode(answer), as: UTF8.self))
    } catch {
        let reason = (error as? Problem)?.reason ?? "agent_unknown"
        let answer = Answer(ok:false,state:"unknown",reason:reason)
        if let data = try? JSONEncoder().encode(answer) { print(String(decoding:data,as:UTF8.self)) }
        exit(1)
    }
 }
}

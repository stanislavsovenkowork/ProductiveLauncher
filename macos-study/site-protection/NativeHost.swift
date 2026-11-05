import Foundation
import Darwin

// Chrome native messaging endpoint. Only status is exposed; it never receives
// page URLs, and it binds each reply to the bundled agent's exact code hash.
@main enum SiteNativeHost {
    static func main() {
        signal(SIGPIPE, SIG_IGN)
        if CommandLine.arguments.count == 2 && CommandLine.arguments[1] == "--agent-hash" { print(SiteHostBuild.agentHash); return }
        if CommandLine.arguments.count == 2 && ["--install", "--uninstall"].contains(CommandLine.arguments[1]) {
            do { try manage(CommandLine.arguments[1]); print("ok"); exit(0) }
            catch { fputs("Unable to change Chrome host registration: \(error)\n", stderr); exit(1) }
        }
        while let message = readMessage() {
            let response: [String: Any]
            if let object = try? JSONSerialization.jsonObject(with: message) as? [String: Any],
               object.count == 1, object["kind"] as? String == "status" {
                response = status()
            } else { response = ["phase": "unknown"] }
            guard let data = try? JSONSerialization.data(withJSONObject: response), data.count <= 65536 else { break }
            var length = UInt32(data.count).littleEndian
            let header = withUnsafeBytes(of: &length) { Data($0) }
            do { try FileHandle.standardOutput.write(contentsOf: header + data) } catch { break }
        }
    }
    enum RegistrationFailure: Error { case invalid, otherBuild }
    static func manage(_ command: String) throws {
        guard command == "--install" || command == "--uninstall" else { throw RegistrationFailure.invalid }
        let own = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL.resolvingSymlinksInPath().path
        #if SITE_HOST_TEST
        guard let dataDir = ProcessInfo.processInfo.environment["FOCUSDECK_SITE_TEST_CHROME_DATA"], dataDir.hasPrefix("/tmp/focusdeck-") else { throw RegistrationFailure.invalid }
        let dir = URL(fileURLWithPath: dataDir, isDirectory: true).appendingPathComponent("NativeMessagingHosts", isDirectory: true)
        #else
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Google/Chrome/NativeMessagingHosts", isDirectory: true)
        #endif
        let file = dir.appendingPathComponent("dev.stanislav.focusdeck.sites.json")
        if FileManager.default.fileExists(atPath: file.path) {
            guard let object = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any],
                  object["path"] as? String == own, object["name"] as? String == "dev.stanislav.focusdeck.sites" else { throw RegistrationFailure.otherBuild }
            if command == "--uninstall" { try FileManager.default.removeItem(at: file); return }
        } else if command == "--uninstall" { return }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let manifest: [String: Any] = ["name": "dev.stanislav.focusdeck.sites", "description": "FocusDeck site status", "path": own, "type": "stdio", "allowed_origins": ["chrome-extension://dcfilodddhkflkekabenoekoaeoblhao/"]]
        let data = try JSONSerialization.data(withJSONObject: manifest, options: [.sortedKeys])
        try data.write(to: file, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
    }
    static func readExactly(_ count: Int) -> Data? {
        var result = Data()
        while result.count < count {
            guard let chunk = try? FileHandle.standardInput.read(upToCount: count - result.count), !chunk.isEmpty else { return nil }
            result.append(chunk)
        }
        return result
    }
    static func readMessage() -> Data? {
        guard let h = readExactly(4) else { return nil }
        let n = h.enumerated().reduce(UInt32(0)) { $0 | (UInt32($1.element) << (8 * $1.offset)) }
        guard n > 0 && n <= 65536 else { return nil }
        return readExactly(Int(n))
    }
    static func status() -> [String: Any] {
        do {
            let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
            #if SITE_HOST_TEST
            guard let path = ProcessInfo.processInfo.environment["FOCUSDECK_SITE_TEST_SOCKET"] else { throw RootSocketFailure.invalidPath }
            #else
            let path = "/tmp/focusdeck-user-protection-\(getuid()).sock"
            #endif
            var address = try guardAddress(path)
            let connected = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
            var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
            guard connected == 0, getpeereid(fd, &uid, &gid) == 0, uid == getuid(),
                  getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
                  RootSocketServer.verifiedHash(pid: pid)?.lowercased() == SiteHostBuild.agentHash else { throw RootSocketFailure.invalidResponse }
            let now = Date()
            let p = WindowPolicy(windowId: UUID(), localDate: "status", startsAt: now, endsAt: now.addingTimeInterval(60), reliefBudgetSeconds: 0, reliefRemainingSeconds: 0, reliefMaxGrantSeconds: 1, targetRefs: [])
            let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
            try writeRootFrame(encoder.encode(WindowGuardRequest(policy: p, command: "status")), fd: fd, timeout: 1.5)
            let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
            let reply = try decoder.decode(WindowGuardReply.self, from: readRootFrame(fd, timeout: 1.5))
            guard reply.accepted, reply.scheduleStatus != "clock_unknown", ["selected_apps_requested", "released"].contains(reply.enforcement) else { throw RootSocketFailure.invalidResponse }
            guard let policy = reply.policy else { return ["phase": "released", "domains": [], "includeSubdomains": false, "windowState": "none"] }
            let includeSubdomains = policy.siteSubdomains == true
            guard SiteDomainPolicy.validList(policy.siteDomains, includeSubdomains: includeSubdomains) else { throw RootSocketFailure.invalidResponse }
            return ["phase": reply.enforcement == "selected_apps_requested" ? "active" : "released", "domains": policy.siteDomains ?? [], "includeSubdomains": includeSubdomains, "windowState": policy.state.rawValue]
        } catch { return ["phase": "unknown"] }
    }
}

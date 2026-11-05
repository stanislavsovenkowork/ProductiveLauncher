import Foundation
import Darwin
import Security

struct BrowserIdentity: Codable, Equatable, Hashable {
    let pid: Int32
    let seconds: UInt64
    let micros: UInt64
    init(_ value: ProcessIdentity) { pid = value.pid; seconds = value.startSeconds; micros = value.startMicros }
    func matches(_ value: ProcessIdentity) -> Bool { pid == value.pid && seconds == value.startSeconds && micros == value.startMicros }
}

final class ManagedBrowser {
    private let uid: UInt32
    private var launcher: Process?
    private var identity: BrowserIdentity?
    private var pacing = BrowserLaunchPacing()
    private var authorization: BrowserExecution?
    private var infrastructure = [Int32: BrowserExecution]()
    private var profilePath: String? {
        guard let user = getpwuid(uid), let home = user.pointee.pw_dir else { return nil }
        return String(cString: home) + "/Library/Application Support/FocusDeckStudyBrowser"
    }
    private var policyArguments: [String]? {
        guard let profilePath else { return nil }
        return ["--user-data-dir=" + profilePath,
            "--proxy-server=http://127.0.0.1:" + String(BrowserProxy.port), "--proxy-bypass-list=<-loopback>",
            "--disable-extensions", "--disable-quic", "--disable-http2", "--disable-sync",
            "--force-webrtc-ip-handling-policy=disable_non_proxied_udp"]
    }
    private func arguments(_ number: Int32) -> [String]? {
        var mib: [Int32] = [CTL_KERN, KERN_PROCARGS2, number]
        var size = 0
        guard sysctl(&mib, 3, nil, &size, nil, 0) == 0, size > 4, size <= 1_048_576 else { return nil }
        var data = [UInt8](repeating: 0, count: size)
        guard sysctl(&mib, 3, &data, &size, nil, 0) == 0 else { return nil }
        let count = data.withUnsafeBytes { $0.loadUnaligned(as: Int32.self) }
        guard count > 0, count < 4096 else { return nil }
        var offset = 4
        while offset < size && data[offset] != 0 { offset += 1 } // executable path
        while offset < size && data[offset] == 0 { offset += 1 }
        var result = [String]()
        for _ in 0..<count {
            guard offset < size else { return nil }
            let start = offset
            while offset < size && data[offset] != 0 { offset += 1 }
            result.append(String(decoding: data[start..<offset], as: UTF8.self)); offset += 1
        }
        return result
    }
    private func authorized(_ process: ProcessIdentity, path: String) -> Bool {
        guard process.uid == uid, let execution = BrowserExecution.read(process, path: path) else { return false }
        if authorization == execution { return true }
        guard let required = policyArguments, let argv = arguments(process.pid),
              BrowserRuntimePolicy.permits(arguments: argv, required: required) else { return false }
        // A look-alike ad-hoc executable with Chrome's identifier is not Chrome.
        let chrome = TrustedSigningRequirement(signingID: "com.google.Chrome", teamID: "EQHXZ8M8AV",
            requirement: "identifier \"com.google.Chrome\" and anchor apple generic and certificate leaf[subject.OU] = \"EQHXZ8M8AV\"")
        guard ProcessSignatureRequirement.matches(pid: process.pid, expected: chrome),
              let current = ProcessIdentity.basic(process.pid), current.uid == uid,
              BrowserExecution.read(current, path: path) == execution else { return false }
        authorization = execution; return true
    }
    private var deniedSince = [BrowserIdentity:Double]()
    private struct SigningEntry { let execution: BrowserExecution; let identifier: String }
    private var signingEntries = [Int32: SigningEntry]()
    private let record: URL
    static let known = Set(["com.apple.Safari", "com.apple.SafariTechnologyPreview", "com.google.Chrome", "com.google.Chrome.canary", "org.mozilla.firefox", "org.mozilla.firefoxdeveloperedition", "org.torproject.torbrowser", "com.brave.Browser", "com.microsoft.edgemac", "com.operasoftware.Opera", "company.thebrowser.Browser"])
    init(uid: UInt32, record: URL = URL(fileURLWithPath: SystemProtectionPaths.directory + "/browser.json")) {
        self.uid = uid
        self.record = record
        if let data = try? Data(contentsOf: record) { identity = try? JSONDecoder().decode(BrowserIdentity.self, from: data) }
    }
    var pid: Int32? {
        guard let identity, let process = ProcessIdentity.basic(identity.pid), process.uid == uid,
              identity.matches(process), let path = executablePath(process.pid), authorized(process, path: path) else { return nil }
        return process.pid
    }
    func open() throws {
        if pid != nil { return }
        let now = ProcessInfo.processInfo.systemUptime
        guard now >= pacing.nextAttempt else { return }
        pacing.attempted(at: now)
        guard let flags = policyArguments else { throw POSIXError(.ENOENT) }
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = ["asuser", String(uid), "/usr/bin/sudo", "-n", "-u", "#" + String(uid),
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"] + flags +
            ["--no-first-run", "--no-default-browser-check", "https://www.google.com/"]
        process.environment = ["PATH":"/usr/bin:/bin:/usr/sbin:/sbin", "LANG":"C"]
        process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
        try process.run(); launcher = process
        event("launch", pid: process.processIdentifier)
    }
    private func executablePath(_ pid: Int32) -> String? {
        var bytes = [UInt8](repeating: 0, count: Int(MAXPATHLEN) * 4)
        guard proc_pidpath(pid, &bytes, UInt32(bytes.count)) > 0 else { return nil }
        return String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
    }
    private func event(_ name: String, pid: Int32, identifier: String = "") {
        // No URLs, window text, profile data, or command-line arguments.
        let line = "FocusDeckBrowser \(Date().ISO8601Format()) \(name) pid=\(pid) id=\(identifier)\n"
        FileHandle.standardError.write(Data(line.utf8))
    }
    private func isInfrastructure(_ process: ProcessIdentity, execution: BrowserExecution) -> Bool {
        if infrastructure[process.pid] == execution { return true }
        guard let id = process.signingID, StudyPolicy.isInfrastructure(id) else { return false }
        let trusted: Bool
        if id.hasPrefix("com.openai.") {
            let requirement = TrustedSigningRequirement(signingID: id, teamID: "2DC432GLL2",
                requirement: "identifier \"\(id)\" and anchor apple generic and certificate leaf[subject.OU] = \"2DC432GLL2\"")
            trusted = ProcessSignatureRequirement.matches(pid: process.pid, expected: requirement)
        } else {
            trusted = ProcessIdentity.inspect(process.pid)?.signingID == id
        }
        guard trusted, let current = ProcessIdentity.basic(process.pid), current.uid == uid,
              BrowserExecution.read(current, path: execution.path) == execution else { return false }
        infrastructure[process.pid] = execution
        event("infrastructure-exempt", pid: process.pid, identifier: id)
        return true
    }
    private func declaredSigningID(_ pid: Int32) -> String? {
        // Classification for a deny decision does not grant trust. Reading the
        // declared identifier avoids certificate/timestamp trust evaluation for
        // every unrelated running application on every scan.
        var code: SecCode?, staticCode: SecStaticCode?, details: CFDictionary?
        guard SecCodeCopyGuestWithAttributes(nil, [kSecGuestAttributePid: NSNumber(value: pid)] as CFDictionary, [], &code) == errSecSuccess,
              let code, SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode,
              SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &details) == errSecSuccess,
              let values = details as? [String: Any] else { return nil }
        return values[kSecCodeInfoIdentifier as String] as? String
    }
    func tick(enforce: Bool, discoverOnly: Bool = false) {
        if !enforce && !discoverOnly {
            deniedSince.removeAll()
            // Outside a window only discover a browser we just launched.
            // There is no reason to inspect every running app while idle.
            if pid != nil || launcher?.isRunning != true { return }
        }
        var seen = Set<Int32>()
        let scanTime = ProcessInfo.processInfo.systemUptime
        let backgroundDeadline = scanTime + 0.05
        for number in ProcessIdentity.pids() {
            guard let basic = ProcessIdentity.basic(number), basic.uid == uid else { continue }
            seen.insert(number)
            guard let path = executablePath(number), let execution = BrowserExecution.read(basic, path: path) else { continue }
            let bundle: Bundle?
            if let range = path.range(of: ".app/Contents/MacOS/", options: .backwards) {
                bundle = Bundle(url: URL(fileURLWithPath: String(path[..<range.lowerBound]) + ".app"))
            } else { bundle = nil }
            let id = bundle?.bundleIdentifier ?? ""
            let webBrowser = BrowserRuntimePolicy.claimsWebBrowser(bundle?.infoDictionary?["CFBundleURLTypes"] as? [[String:Any]] ?? [])
            let directCandidate = Self.known.contains(id) || webBrowser
            let declared: String?
            if let entry = signingEntries[number], entry.execution == execution { declared = entry.identifier }
            else {
                // Unknown executables are classified incrementally. Known
                // browsers remain first-class; IPC is never held behind a full
                // user-process signature sweep at startup.
                if !directCandidate && ProcessInfo.processInfo.systemUptime >= backgroundDeadline { continue }
                declared = declaredSigningID(number)
                // Missing metadata is retried; it must never overwrite a
                // verified running application's identity with a negative hit.
                if let declared { signingEntries[number] = SigningEntry(execution: execution, identifier: declared) }
            }
            let process = ProcessIdentity(pid: basic.pid, uid: basic.uid, parent: basic.parent,
                startSeconds: basic.startSeconds, startMicros: basic.startMicros, signingID: declared)
            let knownBrowser = Self.known.contains(id) || Self.known.contains(process.signingID ?? "")
            guard knownBrowser || webBrowser else { continue }
            if !knownBrowser, isInfrastructure(process, execution: execution) { continue }
            // Adopt any genuine Chrome carrying the complete enforced policy.
            // Parentage is not an authorization boundary: after a daemon
            // update/reparent/relaunch the correct browser has no live launcher.
            if (id == "com.google.Chrome" || process.signingID == "com.google.Chrome"), authorized(process, path: path) {
                let current = BrowserIdentity(process)
                if identity != current {
                    identity = current; try? JSONEncoder().encode(current).write(to: record, options: .atomic)
                    event("adopt", pid: number, identifier: "com.google.Chrome")
                }
                deniedSince.removeValue(forKey: current)
                pacing.healthy(at: scanTime)
                continue
            }
            if enforce, let current = ProcessIdentity.basic(number), current.uid == uid,
               BrowserExecution.read(current, path: path) == execution {
                // This denies unapproved browser processes by executable/start
                // identity; an invalid browser signature is not an exemption.
                let key = BrowserIdentity(process), now = ProcessInfo.processInfo.systemUptime
                let first = deniedSince[key] ?? now
                if deniedSince[key] == nil { event("deny", pid: number, identifier: process.signingID ?? id) }
                deniedSince[key] = first
                _ = kill(number, now - first >= 2 ? SIGKILL : SIGTERM)
            }
        }
        signingEntries = signingEntries.filter { seen.contains($0.key) }
        infrastructure = infrastructure.filter { seen.contains($0.key) }
        deniedSince = deniedSince.filter { seen.contains($0.key.pid) }
    }
}

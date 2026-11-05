import Foundation
import Darwin
import Security

// A deny classification is not a trust grant. Renaming a bundle or damaging
// its signature must not turn a selected application into an allowed one.
struct BlockedApplications {
    private struct Entry { let process: ProcessIdentity; let path: String; let ids: Set<String>; let until: Double }
    private var cache = [Int32: Entry]()
    private var firstDenied = [BrowserIdentity: Double]()

    private func idsFromBundleAreUnselected(_ found: Set<String>, denied: Set<String>) -> Bool {
        found.intersection(denied).isEmpty
    }
    mutating func tick(targets: [TrustedSigningRequirement], uid: UInt32, now: Double) {
        let denied = Set(targets.map(\.signingID))
        guard !denied.isEmpty else { cache.removeAll(); firstDenied.removeAll(); return }
        var seen = Set<Int32>()
        let backgroundDeadline = ProcessInfo.processInfo.systemUptime + 0.05
        for pid in ProcessIdentity.pids() {
            guard pid > 1, pid != getpid(), let basic = ProcessIdentity.basic(pid), basic.uid == uid else { continue }
            seen.insert(pid)
            var bytes = [UInt8](repeating: 0, count: Int(MAXPATHLEN) * 4)
            guard proc_pidpath(pid, &bytes, UInt32(bytes.count)) > 0 else { continue }
            let path = String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
            let ids: Set<String>
            if let entry = cache[pid], entry.process == basic, entry.path == path, now < entry.until { ids = entry.ids }
            else {
                var found = Set<String>()
                if let range = path.range(of: ".app/Contents/MacOS/", options: .backwards),
                   let id = Bundle(path: String(path[..<range.lowerBound]) + ".app")?.bundleIdentifier { found.insert(id) }
                if idsFromBundleAreUnselected(found, denied: denied), ProcessInfo.processInfo.systemUptime >= backgroundDeadline { continue }
                var code: SecStaticCode?, details: CFDictionary?
                if SecStaticCodeCreateWithPath(URL(fileURLWithPath: path) as CFURL, [], &code) == errSecSuccess, let code,
                   SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &details) == errSecSuccess,
                   let info = details as? [String: Any], let id = info[kSecCodeInfoIdentifier as String] as? String { found.insert(id) }
                ids = found
                // Long-lived negative entries let the bounded sweep reach
                // every executable, including a renamed app late in PID order.
                cache[pid] = Entry(process: basic, path: path, ids: ids,
                    until: now + (ids.intersection(denied).isEmpty ? 30 : 1))
            }
            guard !ids.intersection(denied).isEmpty else { continue }
            let key = BrowserIdentity(basic)
            let first = firstDenied[key] ?? now; firstDenied[key] = first
            guard ProcessIdentity.basic(pid) == basic else { continue }
            _ = kill(pid, now - first >= 2 ? SIGKILL : SIGTERM)
        }
        cache = cache.filter { seen.contains($0.key) }
        // Retain escalation while a classification is deferred by the scan
        // budget. A PID/start identity remains unique; a new process gets no
        // inherited attempt. All entries are cleared at the end of the window.
        firstDenied = firstDenied.filter { seen.contains($0.key.pid) }
    }
}

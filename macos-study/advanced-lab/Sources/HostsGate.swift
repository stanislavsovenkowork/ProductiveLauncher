import Foundation
import Darwin

// Exact-name system-resolver gate only. No wildcard/subresource/proxy guarantee.
enum HostsGate {
    static let prefix = "# BEGIN FocusDeckGuard v1 "
    static func transform(_ original: String, session: GuardSession?) throws -> String {
        let buffer = NSMutableString(string: original)
        let headerPattern = try NSRegularExpression(pattern: #"(?m)^# BEGIN FocusDeckGuard v1 ([0-9A-Fa-f-]{36}) separator=(added|existing)\r?\n"#)
        while true {
            let whole = NSRange(location: 0, length: buffer.length)
            let begin = buffer.range(of: prefix)
            if begin.location == NSNotFound { break }
            guard let header = headerPattern.firstMatch(in: buffer as String, range: whole), header.range.location == begin.location,
                  let id = UUID(uuidString: buffer.substring(with: header.range(at: 1))) else { throw Failure.malformedManagedSection }
            let separator = buffer.substring(with: header.range(at: 2))
            let endPattern = try NSRegularExpression(pattern: "(?m)^# END FocusDeckGuard v1 " + id.uuidString + #"(?:\r?\n|$)"#)
            let afterHeader = NSMaxRange(header.range)
            guard let end = endPattern.firstMatch(in: buffer as String, range: NSRange(location: afterHeader, length: buffer.length - afterHeader)) else { throw Failure.malformedManagedSection }
            let body = buffer.substring(with: NSRange(location: afterHeader, length: end.range.location - afterHeader))
            guard !body.contains(prefix) else { throw Failure.malformedManagedSection }
            var lower = begin.location
            let upper = NSMaxRange(end.range)
            if separator == "added", upper == buffer.length, lower > 0, buffer.character(at: lower - 1) == 10 {
                lower -= 1
                if lower > 0, buffer.character(at: lower - 1) == 13 { lower -= 1 }
            }
            buffer.deleteCharacters(in: NSRange(location: lower, length: upper - lower))
        }
        var text = buffer as String
        guard let session, session.hostsEnabled else { return text }
        let policy = StudyPolicy(blockedDomains: session.blockedDomains, protectedDomains: session.protectedDomains, blockedAppBundleIds: [])
        try policy.validate()
        let domains = Array(Set(session.blockedDomains.map(StudyPolicy.normalized))).sorted().filter { host in
            !session.protectedDomains.contains(where: { StudyPolicy.matches(host, $0) })
        }
        guard !domains.isEmpty else { return text }
        let selected = Set(domains)
        for line in text.components(separatedBy: .newlines) {
            let content = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
            let fields = content.split(whereSeparator: { $0 == " " || $0 == "\t" })
            guard fields.count > 1 else { continue }
            if fields.dropFirst().contains(where: { selected.contains(StudyPolicy.normalized(String($0))) }) {
                // Unmanaged host precedence is unknown: refuse rather than overwrite it.
                throw Failure.unmanagedConflict
            }
        }
        let separatorAdded = !text.isEmpty && !text.hasSuffix("\n")
        let newline = text.contains("\r\n") ? "\r\n" : "\n"
        if separatorAdded { text += newline }
        text += prefix + session.sessionId.uuidString + " separator=" + (separatorAdded ? "added" : "existing") + newline
        for domain in domains { text += "0.0.0.0 \(domain)" + newline + ":: \(domain)" + newline }
        text += "# END FocusDeckGuard v1 " + session.sessionId.uuidString + newline
        return text
    }
    static func reconcile(session: GuardSession?) throws -> Bool {
        guard geteuid() == 0 else { throw Failure.requiresRoot }
        let url = URL(fileURLWithPath: "/private/etc/hosts")
        var info = stat()
        guard lstat(url.path, &info) == 0, info.st_mode & S_IFMT == S_IFREG, info.st_uid == 0, info.st_size <= 1024 * 1024 else { throw Failure.unsafeFile }
        let original = try String(contentsOf: url, encoding: .utf8)
        let updated = try transform(original, session: session)
        guard updated != original else { return session?.hostsEnabled == true }
        // Re-read before replacement; never restore a stale backup over later edits.
        guard try String(contentsOf: url, encoding: .utf8) == original else { throw Failure.concurrentEdit }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let temporary = url.deletingLastPathComponent().appendingPathComponent(".focusdeck-hosts-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temporary) }
        // Copy the current file's metadata/extended attributes, then change only its
        // content in a sibling temporary file before an atomic same-directory rename.
        try FileManager.default.copyItem(at: url, to: temporary)
        let handle = try FileHandle(forWritingTo: temporary)
        do { try handle.truncate(atOffset: 0); try handle.write(contentsOf: Data(updated.utf8)); try handle.synchronize(); try handle.close() }
        catch { try? handle.close(); throw error }
        try FileManager.default.setAttributes([.posixPermissions: attributes[.posixPermissions]!,
                                              .ownerAccountID: attributes[.ownerAccountID]!,
                                              .groupOwnerAccountID: attributes[.groupOwnerAccountID]!], ofItemAtPath: temporary.path)
        guard try String(contentsOf: url, encoding: .utf8) == original else { throw Failure.concurrentEdit }
        guard rename(temporary.path, url.path) == 0 else { throw Failure.unsafeFile }
        return session?.hostsEnabled == true
    }
    enum Failure: Error { case malformedManagedSection, unmanagedConflict, unsafeFile, requiresRoot, concurrentEdit }
}

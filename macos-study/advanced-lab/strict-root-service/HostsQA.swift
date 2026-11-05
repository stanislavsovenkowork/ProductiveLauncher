import Foundation

// Pure QA-only transformation. Never writes /etc/hosts, changes DNS settings,
// or operates on a user domain. The caller must enforce file ownership and
// compare current bytes before any write.
enum HostsQA {
    enum Failure: Error { case invalid, conflict, changed }
    enum Phase { case loopback, blocked }
    struct Owned: Equatable {
        let domain: String
        let id: UUID
        let addedSeparator: Bool
        func block(_ phase: Phase) -> String {
            let ip4 = phase == .loopback ? "127.0.0.1" : "127.0.0.2"
            let ip6 = phase == .loopback ? "::1" : "::"
            return "# BEGIN FocusDeck strict QA \(id.uuidString) separator=\(addedSeparator ? "added" : "existing")\n" +
                "\(ip4) \(domain)\n\(ip6) \(domain)\n# END FocusDeck strict QA \(id.uuidString)\n"
        }
    }
    static func begin(_ original: Data, id: UUID) throws -> (Owned, Data) {
        guard let text = String(data: original, encoding: .utf8), !text.contains("# BEGIN FocusDeck strict QA"),
              !text.contains("# END FocusDeck strict QA") else { throw Failure.conflict }
        let domain = "focusdeck-qa-" + id.uuidString.replacingOccurrences(of: "-", with: "").lowercased() + ".invalid"
        for line in text.components(separatedBy: .newlines) {
            let active = line.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
            if active.split(whereSeparator: { $0 == " " || $0 == "\t" }).dropFirst().contains(where: { $0.lowercased() == domain }) { throw Failure.conflict }
        }
        let added = !text.isEmpty && !text.hasSuffix("\n")
        let owned = Owned(domain: domain, id: id, addedSeparator: added)
        return (owned, Data((text + (added ? "\n" : "") + owned.block(.loopback)).utf8))
    }
    static func replace(current: Data, expected: Data, owned: Owned, from: Phase, to: Phase?) throws -> Data {
        guard current == expected else { throw Failure.changed }
        guard let text = String(data: current, encoding: .utf8),
              let range = text.range(of: owned.block(from)), text.range(of: owned.block(from), range: range.upperBound..<text.endIndex) == nil,
              range.upperBound == text.endIndex else { throw Failure.conflict }
        var prefix = String(text[..<range.lowerBound])
        if to == nil && owned.addedSeparator {
            guard prefix.hasSuffix("\n") else { throw Failure.conflict }; prefix.removeLast()
        }
        return Data((prefix + (to.map(owned.block) ?? "")).utf8)
    }
}

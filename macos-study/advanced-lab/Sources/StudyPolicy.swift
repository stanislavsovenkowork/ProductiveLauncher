import Foundation

struct StudyPolicy: Codable {
    let blockedDomains: [String]
    let protectedDomains: [String]
    let blockedAppBundleIds: [String]

    enum CodingKeys: String, CodingKey {
        case blockedDomains = "blocked_domains"
        case protectedDomains = "protected_domains"
        case blockedAppBundleIds = "blocked_app_bundle_ids"
    }

    static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "."))
    }

    static func matches(_ host: String, _ domain: String) -> Bool {
        let host = normalized(host), domain = normalized(domain)
        return host == domain || host.hasSuffix("." + domain)
    }

    static let workCommunicationApps: Set<String> = ["ru.keepcoder.Telegram", "com.tdesktop.Telegram", "com.microsoft.Outlook", "com.microsoft.teams2", "com.microsoft.teams"]
    static let workCommunicationDomains = ["web.telegram.org", "outlook.office.com", "outlook.office365.com", "teams.microsoft.com"]
    func profile(category: String) -> StudyPolicy {
        guard category == "work" else { return self }
        return StudyPolicy(blockedDomains: blockedDomains.filter { host in
            !Self.workCommunicationDomains.contains { Self.matches(host, $0) }
        }, protectedDomains: protectedDomains + Self.workCommunicationDomains,
        blockedAppBundleIds: blockedAppBundleIds.filter { !Self.workCommunicationApps.contains($0) })
    }

    // Infrastructure names are also protected by prefix to cover vendor variants.
    static func isInfrastructure(_ bundle: String) -> Bool {
        let name = bundle.lowercased()
        return ["hossin.asaadi.v2box", "com.openai.", "com.v2box.", "com.hossinasaadi.v2box", "net.openvpn.",
                "org.openvpn.", "com.tunnelblick.", "net.tunnelblick.", "com.apple."].contains { name.hasPrefix($0) }
    }

    func shouldTerminate(bundleID: String) -> Bool {
        !Self.isInfrastructure(bundleID) && blockedAppBundleIds.contains(bundleID)
    }

    func validate() throws {
        for domain in blockedDomains + protectedDomains {
            let normalized = Self.normalized(domain)
            guard !normalized.isEmpty, normalized.contains("."),
                  normalized.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789-.").contains($0) }),
                  !normalized.contains("..") else { throw ValidationError.invalidDomain(domain) }
        }
        guard !blockedAppBundleIds.contains(where: Self.isInfrastructure) else {
            throw ValidationError.infrastructureBlocked
        }
    }
    enum ValidationError: Error { case invalidDomain(String), infrastructureBlocked }

    // nil means coverage is missing. The caller must not label this protected.
    func shouldBlock(host: String?, bundleID: String?) -> Bool? {
        if let bundleID, Self.isInfrastructure(bundleID) { return false }
        if let bundleID, shouldTerminate(bundleID: bundleID) { return true }
        guard let host, !host.isEmpty else { return nil }
        if protectedDomains.contains(where: { Self.matches(host, $0) }) { return false }
        return blockedDomains.contains(where: { Self.matches(host, $0) })
    }
}

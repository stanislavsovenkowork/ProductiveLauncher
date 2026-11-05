import Foundation
import NetworkExtension
import Security
import Network

// Compilable provider prototype, NOT an installed or complete protection engine.
// No traffic contents, URLs, audit tokens or app activity are persisted.
@objc(StudyFilter)
final class StudyFilter: NEFilterDataProvider {
    private var policy: StudyPolicy?
    private var expires = Date.distantPast
    private var monotonicExpiry: TimeInterval = 0

    override func startFilter(completionHandler: @escaping (Error?) -> Void) {
        policy = nil
        expires = .distantPast
        monotonicExpiry = 0
        // Signed host supplies a frozen policy + bounded expiry.
        // No config file or personal vault is read by the extension.
        guard let config = filterConfiguration.vendorConfiguration,
              let data = config["policy"] as? Data,
              let expiry = config["expires"] as? Date,
              expiry > Date(), expiry.timeIntervalSinceNow <= 25 * 60,
              let decoded = try? JSONDecoder().decode(StudyPolicy.self, from: data) else {
            completionHandler(NSError(domain: "FocusDeckStudy", code: 1,
                                     userInfo: [NSLocalizedDescriptionKey: "Missing bounded study policy"]))
            return
        }
        do { try decoded.validate() } catch { completionHandler(error); return }
        policy = decoded
        expires = expiry
        monotonicExpiry = ProcessInfo.processInfo.systemUptime + expiry.timeIntervalSinceNow
        // Explicit rules are required: defaultAction never covers loopback (Apple SDK).
        // A loopback proxy still hides its upstream hostname; matching does not solve that.
        let rules = [("127.0.0.1", 8), ("::1", 128)].map { address, prefix in
            NEFilterRule(networkRule: NENetworkRule(
                remoteNetworkEndpoint: NWEndpoint.hostPort(host: .init(address), port: .any), remotePrefix: prefix,
                localNetworkEndpoint: nil, localPrefix: 0, protocol: .any, direction: .outbound), action: .filterData)
        }
        apply(NEFilterSettings(rules: rules, defaultAction: .filterData), completionHandler: completionHandler)
    }

    override func handleNewFlow(_ flow: NEFilterFlow) -> NEFilterNewFlowVerdict {
        guard Date() < expires, ProcessInfo.processInfo.systemUptime < monotonicExpiry, let policy else { return .allow() }
        let hostname = flow.url?.host ?? (flow as? NEFilterSocketFlow)?.remoteHostname
        let bundleID = Self.signingIdentifier(flow.sourceAppAuditToken)
        // Intentionally fail open on missing metadata to preserve VPN/Codex.
        // This is a known coverage gap: the host must refuse strict activation.
        return policy.shouldBlock(host: hostname, bundleID: bundleID) == true ? .drop() : .allow()
    }

    private static func signingIdentifier(_ auditToken: Data?) -> String? {
        guard let auditToken else { return nil }
        var code: SecCode?
        let attributes = [kSecGuestAttributeAudit: auditToken] as CFDictionary
        guard SecCodeCopyGuestWithAttributes(nil, attributes, [], &code) == errSecSuccess,
              let code else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess,
              let staticCode else { return nil }
        var information: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, [], &information) == errSecSuccess,
              let dict = information as? [String: Any] else { return nil }
        return dict[kSecCodeInfoIdentifier as String] as? String
    }
}

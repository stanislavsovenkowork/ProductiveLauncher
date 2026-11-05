import Foundation

struct ProtectionStatus: Equatable, Sendable {
    enum Connection: String, Sendable { case notConnected, connected, unavailable }
    enum Enforcement: String, Sendable { case unknown, active, inactive }
    let connection: Connection
    let enforcement: Enforcement
    let providerName: String?
    static let notConnected = ProtectionStatus(connection: .notConnected, enforcement: .unknown, providerName: nil)
}
protocol ExternalProtectionAdapter: Sendable {
    func status() async -> ProtectionStatus
}
struct UnconfiguredProtectionAdapter: ExternalProtectionAdapter {
    func status() async -> ProtectionStatus { .notConnected }
}

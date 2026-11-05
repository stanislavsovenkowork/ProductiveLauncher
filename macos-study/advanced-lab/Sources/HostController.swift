import Foundation
import NetworkExtension
import SystemExtensions

@MainActor
protocol FilterPreferences {
    func load() async throws
    var owner: String? { get }
    var hasConfiguration: Bool { get }
    var sessionID: String? { get }
    func save(policy: Data, expiry: Date, sessionID: UUID, category: String) async throws
    func remove() async throws
}

@MainActor
final class AppleFilterPreferences: FilterPreferences {
    private let manager = NEFilterManager.shared()
    var sessionID: String? { manager.providerConfiguration?.vendorConfiguration?["sessionId"] as? String }
    var hasConfiguration: Bool { manager.providerConfiguration != nil }
    var owner: String? { manager.providerConfiguration?.filterDataProviderBundleIdentifier }
    func load() async throws { try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            manager.loadFromPreferences { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        } }
    func save(policy: Data, expiry: Date, sessionID: UUID, category: String) async throws {
        let config = NEFilterProviderConfiguration()
        config.filterSockets = true
        config.filterPackets = false
        config.filterDataProviderBundleIdentifier = HostController.extensionID
        config.vendorConfiguration = ["policy": policy, "expires": expiry,
                                      "sessionId": sessionID.uuidString, "category": category]
        manager.providerConfiguration = config
        manager.localizedDescription = "FocusDeck bounded laboratory filter"
        manager.isEnabled = true
        // Never disable encrypted DNS or modify another VPN configuration.
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            manager.saveToPreferences { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }
    func remove() async throws { try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            manager.removeFromPreferences { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        } }
}

@MainActor
final class HostController {
    static let extensionID = "dev.stanislav.focusdeck.study.filter"
    let preferences: any FilterPreferences
    init(preferences: any FilterPreferences) { self.preferences = preferences }
    func start(policy: StudyPolicy, minutes: Double, sessionID: UUID, category: String) async throws {
        guard minutes.isFinite, minutes > 0, minutes <= 25,
              ["work", "study"].contains(category) else { throw HostError.invalidSession }
        try policy.validate()
        try await preferences.load()
        guard !preferences.hasConfiguration || preferences.owner == Self.extensionID else { throw HostError.foreignConfiguration }
        try await preferences.save(policy: JSONEncoder().encode(policy),
                                   expiry: Date().addingTimeInterval(minutes * 60), sessionID: sessionID, category: category)
    }
    func recover(sessionID: UUID? = nil) async throws {
        try await preferences.load()
        guard preferences.hasConfiguration else { return }
        let owner = preferences.owner
        guard owner == Self.extensionID else { throw HostError.foreignConfiguration }
        if let sessionID, preferences.sessionID != sessionID.uuidString { throw HostError.staleSession }
        try await preferences.remove()
    }
    enum HostError: Error { case invalidSession, foreignConfiguration, staleSession, requestPendingReboot, unsignedContainer, approvalPending }
}

@MainActor
final class ExtensionActivation: NSObject, @preconcurrency OSSystemExtensionRequestDelegate {
    private var continuation: CheckedContinuation<Void, Error>?
    func activate() async throws {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let request = OSSystemExtensionRequest.activationRequest(forExtensionWithIdentifier: HostController.extensionID, queue: .main)
            request.delegate = self
            OSSystemExtensionManager.shared.submitRequest(request)
        }
    }
    func request(_ request: OSSystemExtensionRequest, didFinishWithResult result: OSSystemExtensionRequest.Result) {
        if result == .completed { continuation?.resume() }
        else { continuation?.resume(throwing: HostController.HostError.requestPendingReboot) }
        continuation = nil
    }
    func request(_ request: OSSystemExtensionRequest, didFailWithError error: Error) {
        continuation?.resume(throwing: error); continuation = nil
    }
    func requestNeedsUserApproval(_ request: OSSystemExtensionRequest) {
        // Do not hold an unnoticed activation indefinitely. User can approve in Settings,
        // then explicitly rerun the lab command. No filter preference is written here.
        continuation?.resume(throwing: HostController.HostError.approvalPending); continuation = nil
    }
    func request(_ request: OSSystemExtensionRequest, actionForReplacingExtension existing: OSSystemExtensionProperties,
                 withExtension ext: OSSystemExtensionProperties) -> OSSystemExtensionRequest.ReplacementAction { .replace }
}

import Foundation

// These are the actual persisted service models, shared with the service's
// narrowly scoped clock checks. No wall-clock value authorizes early release.
struct ProtectionWindow: Codable {
    let id: UUID
    var domains: [String]
    var applications: [TrustedSigningRequirement]
    var remaining: Double
    var boot: String
    var lastTick: Double
    var addresses: Set<String>
    var validation: Bool?
    var diagnostic: Bool?
    var exitRemaining: Double?
    var bootResumes: Int?

    mutating func advance(boot currentBoot: String, tick: Double) {
        guard tick.isFinite, tick >= 0 else { return }
        // A different boot has a different monotonic epoch. Preserve the saved
        // balance, including an exit request. Powered-off time is not credited.
        let elapsed = boot == currentBoot && tick >= lastTick ? tick - lastTick : 0
        if boot != currentBoot { bootResumes = min(1_000_000, (bootResumes ?? 0) + 1) }
        remaining = max(0, remaining - elapsed)
        if let pending = exitRemaining { exitRemaining = max(0, pending - elapsed) }
        boot = currentBoot; lastTick = tick
    }
    var valid: Bool {
        remaining.isFinite && (0...2_592_000).contains(remaining) && lastTick.isFinite && lastTick >= 0 &&
        !boot.isEmpty && boot.utf8.count < 256 && domains.count <= SiteDomainPolicy.maximumDomains &&
        SiteDomainPolicy.validList(domains.isEmpty ? nil : domains, includeSubdomains: true) &&
        applications.count <= 40 && applications.allSatisfy(ProcessSignatureRequirement.valid) &&
        (!domains.isEmpty || !applications.isEmpty) &&
        (exitRemaining.map { $0.isFinite && (0...3600).contains($0) } ?? true) &&
        (bootResumes.map { (0...1_000_000).contains($0) } ?? true)
    }
    var ended: Bool { remaining <= 0 || exitRemaining == 0 }
    mutating func requestExit() {
        // Repeated requests never restart or shorten an existing wait.
        if exitRemaining == nil { exitRemaining = 3600 }
    }
}

struct ProtectionLedger: Codable {
    var validationUsed = false
    var window: ProtectionWindow?
    var resolverDomains: [String]?
    var lastBootRecovery: SystemProtectionRecovery?
}

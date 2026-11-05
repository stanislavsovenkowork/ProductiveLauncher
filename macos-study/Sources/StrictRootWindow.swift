import Foundation

// Strict mode is a separate wire/storage schema. Legacy V2/user-agent state is
// never promoted or rewritten implicitly. A caller must authenticate the peer,
// authorize enrollment and durably commit records before acknowledging.
struct StrictRootPlan: Codable, Equatable, Sendable {
    let schemaVersion: Int
    let revisionId: UUID
    let windowId: UUID
    let startsAt: Date
    let endsAt: Date
    let appRequirements: [TrustedSigningRequirement]
    let siteDomains: [String]
    let siteSubdomains: Bool

    func valid(requirement: (TrustedSigningRequirement) -> Bool) -> Bool {
        let duration = endsAt.timeIntervalSince(startsAt)
        guard schemaVersion == 1 && duration.isFinite && duration > 0 && duration <= 9 * 3600 &&
            startsAt.timeIntervalSince1970.rounded(.towardZero) == startsAt.timeIntervalSince1970 &&
            endsAt.timeIntervalSince1970.rounded(.towardZero) == endsAt.timeIntervalSince1970 &&
            (!appRequirements.isEmpty || !siteDomains.isEmpty) && appRequirements.count <= 64 &&
            Set(appRequirements.map(\.signingID)).count == appRequirements.count &&
            appRequirements.allSatisfy({ WindowGuardV2.validSigningID($0.signingID) && !WindowGuardV2.isProtected($0.signingID) && requirement($0) }) &&
            SiteDomainPolicy.validList(siteDomains.isEmpty ? nil : siteDomains, includeSubdomains: siteSubdomains) else { return false }
        // Leave room for reply metadata under the shared 4096-byte frame.
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        return (try? encoder.encode(self).count).map { $0 <= 3000 } ?? false
    }
}

struct StrictRootEnrollment: Codable, Sendable {
    let schemaVersion: Int
    let plan: StrictRootPlan
}

enum StrictRootPhase: String, Codable, Sendable { case inactive, active, rest, exitPending = "exit-pending", exit, expired, emergencyExit = "emergency-exit" }
enum StrictRootClockStatus: String, Codable, Sendable { case verified, unknown }
enum StrictRootCoverageState: String, Codable, Sendable { case unknown, requested, confirmed, degraded }
struct StrictRootCoverage: Codable, Equatable, Sendable {
    var apps: StrictRootCoverageState = .unknown
    var sites: StrictRootCoverageState = .unknown
}
struct StrictRootClock: Sendable {
    let wall: Date
    let continuous: Double  // Must include sleep; root samples mach_continuous_time.
    let bootID: String
}
struct StrictRootSnapshot: Codable, Equatable, Sendable {
    let plan: StrictRootPlan
    let phase: StrictRootPhase
    let clock: StrictRootClockStatus
    let windowRemainingSeconds: Int?
    let exitRemainingSeconds: Int?
    // Requested state is not evidence that apps/sites are actually covered.
    let enforcementRequested: Bool
    let coverage: StrictRootCoverage
}
enum StrictRootCommand: String, Codable, Sendable { case status, rest, `return`, requestExit = "request-exit", cancelExit = "cancel-exit", confirmExit = "confirm-exit" }
struct StrictRootRequest: Codable, Sendable {
    let schemaVersion: Int
    let revisionId: UUID
    let windowId: UUID
    let command: StrictRootCommand
}
enum StrictRootEnvelopeKind: String, Codable, Sendable { case discover, enroll, command }
struct StrictRootEnvelope: Codable, Sendable {
    let schemaVersion: Int
    let kind: StrictRootEnvelopeKind
    let enrollment: StrictRootEnrollment?
    let request: StrictRootRequest?
    func validShape() -> Bool {
        guard schemaVersion == 1 else { return false }
        switch kind {
        case .discover: return enrollment == nil && request == nil
        case .enroll: return enrollment?.schemaVersion == 1 && request == nil
        case .command: return request?.schemaVersion == 1 && enrollment == nil
        }
    }
}
struct StrictRootReply: Codable, Sendable {
    let schemaVersion: Int
    let accepted: Bool
    let reason: String
    let snapshot: StrictRootSnapshot?
    enum Failure: Error { case oversized }
    func frame() throws -> Data {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let encoded = try encoder.encode(self)
        guard encoded.count < 4096 else { throw Failure.oversized }
        return encoded
    }
}
struct StrictRootDecision: Sendable { let accepted: Bool; let reason: String }

// Persist only in a separately enrolled root-owned authority. Identity, exact
// targets, original end and 3600-second exit delay are never client updates.
struct StrictRootRecord: Codable, Equatable, Sendable {
    let plan: StrictRootPlan
    var phase: StrictRootPhase
    let bootID: String
    let startTick: Double
    let endTick: Double
    var lastTick: Double
    var exitTick: Double?
    var exitRequestedTick: Double?
    var priorPhase: StrictRootPhase?
    var clockStatus: StrictRootClockStatus

    static let exitDelay = 3600.0
    enum Failure: Error { case invalidPlan, invalidClock, identityMismatch, transitionDenied }

    static func enroll(_ plan: StrictRootPlan, at clock: StrictRootClock,
                       requirement: (TrustedSigningRequirement) -> Bool) throws -> StrictRootRecord {
        guard plan.valid(requirement: requirement), plan.endsAt > clock.wall else { throw Failure.invalidPlan }
        guard clock.continuous.isFinite, clock.continuous >= 0, !clock.bootID.isEmpty else { throw Failure.invalidClock }
        let startDelta = max(0, plan.startsAt.timeIntervalSince(clock.wall))
        let endDelta = plan.endsAt.timeIntervalSince(clock.wall)
        guard endDelta.isFinite, endDelta > 0, endDelta <= 24 * 3600 else { throw Failure.invalidPlan }
        let start = clock.continuous + startDelta, end = clock.continuous + endDelta
        guard start.isFinite, end.isFinite, start <= end else { throw Failure.invalidClock }
        return StrictRootRecord(plan: plan, phase: startDelta == 0 ? .active : .inactive,
                                bootID: clock.bootID, startTick: start, endTick: end,
                                lastTick: clock.continuous, exitTick: nil, exitRequestedTick: nil, priorPhase: nil, clockStatus: .verified)
    }
    mutating func refresh(at clock: StrictRootClock) {
        guard clockStatus == .verified else { return }
        guard !clock.bootID.isEmpty, clock.bootID == bootID, clock.continuous.isFinite,
              clock.continuous >= lastTick else { clockStatus = .unknown; return }
        lastTick = clock.continuous
        guard phase != .exit && phase != .expired && phase != .emergencyExit else { return }
        if clock.continuous >= endTick { phase = .expired; exitTick = nil; exitRequestedTick = nil; priorPhase = nil; return }
        if phase == .inactive && clock.continuous >= startTick { phase = .active }
    }
    // The caller must persist the returned record before replying, including
    // refusals: refresh may have committed expiry or an unknown clock state.
    mutating func apply(_ request: StrictRootRequest, at clock: StrictRootClock) -> StrictRootDecision {
        func accepted(_ reason: String) -> StrictRootDecision { .init(accepted:true, reason:reason) }
        func denied(_ reason: String) -> StrictRootDecision { .init(accepted:false, reason:reason) }
        guard request.schemaVersion == 1, request.revisionId == plan.revisionId, request.windowId == plan.windowId else { return denied("identity_mismatch") }
        refresh(at: clock)
        guard clockStatus == .verified else { return denied("clock_unknown") }
        switch request.command {
        case .status: return accepted("observed")
        case .rest:
            guard phase == .active else { return denied("transition_denied") }
            phase = .rest; return accepted("rest")
        case .return:
            guard phase == .rest else { return denied("transition_denied") }
            phase = .active; return accepted("returned")
        case .requestExit:
            if phase == .exitPending { return accepted("exit_already_requested") }
            guard phase == .active || phase == .rest || phase == .inactive else { return denied("transition_denied") }
            let deadline = clock.continuous + Self.exitDelay
            guard deadline.isFinite else { return denied("clock_unknown") }
            priorPhase = phase; phase = .exitPending; exitRequestedTick = clock.continuous; exitTick = deadline
            return accepted("exit_requested")
        case .cancelExit:
            guard phase == .exitPending, let previous = priorPhase else { return denied("transition_denied") }
            phase = previous == .inactive && clock.continuous >= startTick ? .active : previous
            priorPhase = nil; exitTick = nil; exitRequestedTick = nil
            return accepted("exit_cancelled")
        case .confirmExit:
            guard phase == .exitPending, let deadline = exitTick, clock.continuous >= deadline else { return denied("exit_not_ready") }
            phase = .exit; priorPhase = nil
            return accepted("exited")
        }
    }
    // Called only by the root daemon after same-socket root peer authentication;
    // the user-facing command enum intentionally cannot request this action.
    mutating func recoverByAdministrator(verifiedPeerUID: UInt32) -> StrictRootDecision {
        guard verifiedPeerUID == 0 else { return .init(accepted:false,reason:"unauthorized") }
        guard phase != .exit && phase != .expired else { return .init(accepted:false,reason:"terminal") }
        if phase == .emergencyExit { return .init(accepted:true,reason:"emergency_already_recorded") }
        phase = .emergencyExit; priorPhase = nil; exitTick = nil; exitRequestedTick = nil
        return .init(accepted:true,reason:"emergency_exit_recorded")
    }
    func valid(requirement: (TrustedSigningRequirement) -> Bool) -> Bool {
        guard plan.valid(requirement: requirement), !bootID.isEmpty,
              startTick.isFinite, endTick.isFinite, lastTick.isFinite,
              startTick >= 0, endTick > startTick, lastTick >= 0,
              endTick - startTick <= plan.endsAt.timeIntervalSince(plan.startsAt) + 0.01 else { return false }
        if phase == .exitPending || phase == .exit {
            guard let requested = exitRequestedTick, let deadline = exitTick,
                  requested.isFinite, deadline.isFinite, requested >= 0, requested <= lastTick,
                  abs(deadline - requested - Self.exitDelay) < 0.01 else { return false }
            if phase == .exitPending {
                guard let prior = priorPhase, [.inactive, .active, .rest].contains(prior), lastTick < endTick else { return false }
            } else if priorPhase != nil || lastTick < deadline || deadline >= endTick { return false }
        } else if exitTick != nil || exitRequestedTick != nil || priorPhase != nil { return false }
        switch phase {
        case .inactive: return lastTick < startTick
        case .active, .rest: return lastTick >= startTick && lastTick < endTick
        case .expired: return lastTick >= endTick
        case .exitPending, .exit, .emergencyExit: return true
        }
    }
    func snapshot(coverage: StrictRootCoverage = .init()) -> StrictRootSnapshot {
        guard clockStatus == .verified else { return .init(plan: plan, phase: phase, clock: .unknown, windowRemainingSeconds: nil, exitRemainingSeconds: nil, enforcementRequested: enforcementRequested, coverage: .init()) }
        func remaining(_ deadline: Double) -> Int { Int(max(0, min(24 * 3600 + 1, ceil(deadline - lastTick)))) }
        let window = phase == .exit || phase == .expired || phase == .emergencyExit ? 0 : remaining(endTick)
        return .init(plan: plan, phase: phase, clock: .verified, windowRemainingSeconds: window,
                     exitRemainingSeconds: phase == .exitPending ? exitTick.map(remaining) : nil, enforcementRequested: enforcementRequested, coverage: coverage)
    }
    var enforcementRequested: Bool {
        if phase == .exit || phase == .expired || phase == .emergencyExit { return false }
        if clockStatus == .unknown { return true }
        return [.active, .rest, .exitPending].contains(phase) && lastTick >= startTick && lastTick < endTick
    }
}

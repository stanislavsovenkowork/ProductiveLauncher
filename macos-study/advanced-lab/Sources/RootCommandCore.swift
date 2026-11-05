import Foundation

struct TrustedSigningRequirement: Codable, Sendable, Equatable {
    let signingID: String
    let teamID: String
    let requirement: String
}
struct RootTrust: Codable, Sendable {
    let schemaVersion: Int
    let allowedUID: UInt32
    let uiCDHash: String
    let blockedSigningIDs: [String]
    let blockedDomains: [String]
    let protectedDomains: [String]
    var signingRequirements: [TrustedSigningRequirement]? = nil
    func validate() throws {
        guard schemaVersion == 1, allowedUID >= 500, uiCDHash.count == 40,
              uiCDHash.allSatisfy({ $0.isHexDigit }) else { throw Failure.invalidTrust }
        try StudyPolicy(blockedDomains: blockedDomains, protectedDomains: protectedDomains, blockedAppBundleIds: blockedSigningIDs).validate()
    }
    enum Failure: Error { case invalidTrust }
}
struct RootRequest: Codable, Sendable {
    let schemaVersion: Int
    let command: String
    let sessionId: UUID
    let seconds: Double?
    let hostsEnabled: Bool?
    var category: String? = nil
}
struct RootReply: Codable, Sendable {
    let schemaVersion = 1
    let accepted: Bool
    let reason: String
    let expiresAt: Date?
    var pauseUntil: Date? = nil
    // Local kernel observation, deliberately excluded from JSON decoding/encoding.
    var verifiedPeerPID: Int32? = nil
    var policyState: String? = nil
    var policySessionId: UUID? = nil
    enum CodingKeys: String, CodingKey { case schemaVersion, accepted, reason, expiresAt, pauseUntil, policyState, policySessionId }
}
enum RootCommandCore {
    static func apply(_ request: RootRequest, trust: RootTrust, peerUID: UInt32, peerHash: String?,
                      current: GuardSession?, now: Date) throws -> (GuardSession?, RootReply) {
        try trust.validate()
        func refusal(_ reason: String) -> (GuardSession?, RootReply) { (nil, RootReply(accepted: false, reason: reason, expiresAt: nil)) }
        guard peerUID == trust.allowedUID, peerHash?.lowercased() == trust.uiCDHash.lowercased() else { return refusal("unauthorized_peer") }
        guard request.schemaVersion == 1 else { return refusal("unsupported_request") }
        switch request.command {
        case "status": return (nil, RootReply(accepted: true, reason: "authenticated_service_observed", expiresAt: current?.expiresAt, pauseUntil: current?.pauseUntil))
        case "start":
            guard let seconds = request.seconds, seconds.isFinite, seconds > 0, seconds <= 1500 else { return refusal("invalid_duration") }
            if let current, current.expiresAt > now { return refusal("existing_session_cannot_be_reset") }
            let category = request.category ?? "study"
            guard ["work", "study"].contains(category) else { return refusal("invalid_category") }
            let policy = StudyPolicy(blockedDomains: trust.blockedDomains, protectedDomains: trust.protectedDomains, blockedAppBundleIds: trust.blockedSigningIDs).profile(category: category)
            var session = GuardSession(schemaVersion: 1, sessionId: request.sessionId, targetUID: trust.allowedUID,
                                       createdAt: now, expiresAt: now.addingTimeInterval(seconds), blockedSigningIDs: policy.blockedAppBundleIds, pauseUntil: nil)
            session.blockedDomains = policy.blockedDomains; session.protectedDomains = policy.protectedDomains
            session.hostsEnabled = request.hostsEnabled == true
            try session.validate(now: now)
            return (session, RootReply(accepted: true, reason: "bounded_session_created", expiresAt: session.expiresAt))
        case "pause":
            guard var session = current, session.sessionId == request.sessionId, session.targetUID == trust.allowedUID,
                  !session.pauseUsed, session.expiresAt > now, let seconds = request.seconds,
                  seconds.isFinite, seconds > 0, seconds <= 300 else { return refusal("pause_not_available") }
            session.pauseUsed = true; session.pauseGrantSeconds = seconds
            session.expiresAt = session.expiresAt.addingTimeInterval(seconds)
            session.pauseUntil = min(session.expiresAt, now.addingTimeInterval(seconds))
            try session.validate(now: now)
            return (session, RootReply(accepted: true, reason: "one_bounded_pause_created", expiresAt: session.expiresAt, pauseUntil: session.pauseUntil))
        case "resume":
            guard var session = current, session.sessionId == request.sessionId, session.targetUID == trust.allowedUID,
                  session.pauseUsed, session.expiresAt > now else { return refusal("resume_not_available") }
            session.pauseUntil = now
            return (session, RootReply(accepted: true, reason: "pause_shortened", expiresAt: session.expiresAt, pauseUntil: now))
        default: return refusal("administrator_required_for_emergency_recovery")
        }
    }
}

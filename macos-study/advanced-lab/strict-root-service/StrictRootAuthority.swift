import Foundation

// The transport must derive uid/hash from the same accepted socket. This type
// never accepts a UID or hash from JSON. It does not install or launch anything.
final class StrictRootAuthority {
    enum Failure: Error { case invalidTrust, stateUnknown, invalidRequest }
    private let store: StrictRootStore
    private let allowedUID: UInt32
    private let allowedHash: String
    private let requirement: (TrustedSigningRequirement) -> Bool
    private var expected: Data?
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(store: StrictRootStore, allowedUID: UInt32, allowedHash: String,
         requirement: @escaping (TrustedSigningRequirement) -> Bool) throws {
        guard allowedUID >= 500, allowedHash.count == 40, allowedHash.allSatisfy(\.isHexDigit) else { throw Failure.invalidTrust }
        self.store = store; self.allowedUID = allowedUID; self.allowedHash = allowedHash.lowercased(); self.requirement = requirement
        encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601; encoder.outputFormatting = [.sortedKeys]
        decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        expected = try store.read()
        if let bytes = expected { let record = try decoder.decode(StrictRootRecord.self, from: bytes); guard record.valid(requirement: requirement) else { throw Failure.stateUnknown } }
    }
    private func readVerified() throws -> StrictRootRecord? {
        guard try store.read() == expected else { throw Failure.stateUnknown }
        guard let expected else { return nil }
        let record = try decoder.decode(StrictRootRecord.self, from: expected)
        guard record.valid(requirement: requirement) else { throw Failure.stateUnknown }
        return record
    }
    private func persist(_ record: StrictRootRecord) throws {
        guard record.valid(requirement: requirement) else { throw Failure.stateUnknown }
        let bytes = try encoder.encode(record)
        if bytes != expected { try store.commit(bytes, matching: expected); expected = bytes }
    }
    private func reply(_ accepted: Bool, _ reason: String, _ record: StrictRootRecord?) -> StrictRootReply {
        StrictRootReply(schemaVersion: 1, accepted: accepted, reason: reason, snapshot: record?.snapshot())
    }

    func handle(_ envelope: StrictRootEnvelope, peerUID: UInt32, peerHash: String?, at clock: StrictRootClock) throws -> StrictRootReply {
        guard peerUID == allowedUID, peerHash?.lowercased() == allowedHash else { throw Failure.invalidTrust }
        guard envelope.validShape() else { throw Failure.invalidRequest }
        let record = try readVerified()
        let result: StrictRootReply
        switch envelope.kind {
        case .discover:
            if var current = record {
                current.refresh(at: clock); result = reply(true, "observed", current); _ = try result.frame(); try persist(current)
            } else { result = reply(true, "not_enrolled", nil) }
        case .enroll:
            guard let enrollment = envelope.enrollment else { throw Failure.invalidRequest }
            if var current = record {
                current.refresh(at: clock)
                result = current.plan == enrollment.plan ? reply(true, "already_enrolled", current) : reply(false, "existing_window", current)
                _ = try result.frame(); try persist(current)
            } else {
                let current = try StrictRootRecord.enroll(enrollment.plan, at: clock, requirement: requirement)
                result = reply(true, "enrolled", current); _ = try result.frame(); try persist(current)
            }
        case .command:
            guard let request = envelope.request, var current = record else { throw Failure.invalidRequest }
            let decision = current.apply(request, at: clock)
            result = reply(decision.accepted, decision.reason, current); _ = try result.frame(); try persist(current)
        }
        return result
    }

    // Must be called by the daemon even when there is no client. The caller
    // may signal targets only after this durable update returns successfully.
    func tick(at clock: StrictRootClock) throws -> StrictRootRecord? {
        guard var record = try readVerified() else { return nil }
        record.refresh(at: clock); try persist(record); return record
    }
}

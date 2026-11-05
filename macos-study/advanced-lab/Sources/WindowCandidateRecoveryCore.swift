import Foundation

enum WindowCandidateRecoveryCore {
    static let command = Data("focusdeck-v2-emergency-recover".utf8)
    static let reply = Data("candidate-stopped-and-tombstone-saved".utf8)
    static let directory = "/Library/Application Support/FocusDeckGuardV2Candidate"
    static let socket = "/var/run/dev.stanislav.focusdeck.guard-v2-candidate.sock"
    static func authorized(uid: UInt32, payload: Data) -> Bool { uid == 0 && payload == command }
}

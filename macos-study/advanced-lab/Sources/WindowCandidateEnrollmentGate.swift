import Foundation

// Until an authenticated, durable never-enrolled sentinel exists, an absent
// ledger is indistinguishable from loss/recovery. Never recreate it implicitly.
enum WindowCandidateEnrollmentGate {
    static func permits(existing: WindowPolicy?, command: String) -> Bool {
        existing != nil || command == "status"
    }
}

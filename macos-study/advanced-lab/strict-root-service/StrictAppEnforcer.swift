import Foundation
import Darwin

// Mechanism only: caller must supply a durably committed root-authoritative
// enforcement decision. This is not a daemon or a service installation.
struct StrictAppEnforcer {
    private struct Key: Hashable { let pid: Int32; let seconds: UInt64; let micros: UInt64 }
    private var attempts: [Key: Double] = [:]

    mutating func tick(enforcing: Bool, requirements: [TrustedSigningRequirement], targetUID: uid_t, now: Double,
                       scan: () -> [ProcessIdentity], inspect: (Int32) -> ProcessIdentity?,
                       verify: (Int32, TrustedSigningRequirement) -> Bool,
                       send: (Int32, Int32) -> Int32) -> Int {
        guard enforcing, now.isFinite, now >= 0, targetUID >= 500 else { attempts.removeAll(); return 0 }
        var allowed: [String: TrustedSigningRequirement] = [:]
        for requirement in requirements {
            guard !WindowGuardV2.isProtected(requirement.signingID), allowed[requirement.signingID] == nil,
                  ProcessSignatureRequirement.valid(requirement) else { attempts.removeAll(); return 0 }
            allowed[requirement.signingID] = requirement
        }
        guard !allowed.isEmpty else { attempts.removeAll(); return 0 }
        var found = Set<Key>(); var sent = 0
        for process in scan() {
            guard process.pid > 1, process.pid != getpid(), process.uid == targetUID,
                  let id = process.signingID, !WindowGuardV2.isProtected(id), let expected = allowed[id] else { continue }
            let key = Key(pid: process.pid, seconds: process.startSeconds, micros: process.startMicros)
            found.insert(key)
            let force: Bool
            if let first = attempts[key] {
                guard now >= first, now - first >= 2 else { continue }; force = true
            } else { force = false }
            // Identity and designated requirement are both checked again at the
            // signal boundary; a reused PID or foreign signature is refused.
            guard inspect(process.pid) == process, verify(process.pid, expected) else { continue }
            if send(process.pid, force ? SIGKILL : SIGTERM) == 0 { sent += 1; if !force { attempts[key] = now } }
        }
        attempts = attempts.filter { found.contains($0.key) }
        return sent
    }
}

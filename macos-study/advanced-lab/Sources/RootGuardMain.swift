import Foundation
import Darwin

@main
enum RootGuardMain {
    static func main() throws {
        guard CommandLine.arguments == [CommandLine.arguments[0], "--daemon"], geteuid() == 0 else { throw Failure.requiresRootDaemon }
        let trust: RootTrust
        var ledger: RootDeadlineLedger
        do { trust = try RootSessionDisk.loadTrust(); ledger = try RootDeadlineLedger.load() }
        catch { _ = try? HostsGate.reconcile(session: nil); throw error }
        let server = try RootSocketServer()
        var current: GuardSession?
        var engine: RootGuardCore?
        var scanner = GuardProcessScanner()
        var attempted: [String: TimeInterval] = [:]
        var statusAt: TimeInterval = 0
        var hostsApplied = false
        var hostsState = "not_requested"
        while true {
            server.drain { request, uid, hash in
                let existing = try RootSessionDisk.load()
                let (updated, originalReply) = try RootCommandCore.apply(request, trust: trust, peerUID: uid, peerHash: hash, current: existing, now: Date())
                if let updated { try RootSessionDisk.write(updated); statusAt = 0 }
                var reply = originalReply
                let observed = updated ?? existing
                reply.policySessionId = observed?.sessionId
                if let observed {
                    let now = Date(), uptime = ProcessInfo.processInfo.systemUptime
                    let cap = ledger.deadlines[observed.sessionId.uuidString].map { $0 + observed.pauseGrantSeconds }
                    if now >= observed.expiresAt || cap.map({ uptime >= $0 }) == true { reply.policyState = "expired" }
                    else if let observer = try? RootGuardCore(session: observed, now: now, uptime: uptime, maximumDeadline: cap) {
                        reply.policyState = observer.enforcing(now: now, uptime: uptime) ? "enforcing" : "paused"
                    } else { reply.policyState = "invalid" }
                } else { reply.policyState = "idle" }
                return reply
            }
            do {
                if let session = try RootSessionDisk.load() {
                    if current != session {
                        engine = try RootGuardCore(session: session, now: Date(), uptime: ProcessInfo.processInfo.systemUptime, maximumDeadline: ledger.deadlines[session.sessionId.uuidString].map { $0 + session.pauseGrantSeconds })
                        ledger.deadlines[session.sessionId.uuidString] = engine.map { $0.deadline - session.pauseGrantSeconds }
                        ledger.prune(uptime: ProcessInfo.processInfo.systemUptime, protecting: session.sessionId)
                        try ledger.save()
                        current = session; attempted = [:]
                    }
                    let uptime = ProcessInfo.processInfo.systemUptime
                    if let engine, engine.enforcing(now: Date(), uptime: uptime) {
                        for target in engine.targets(supplying: { scanner.scan(uid: engine.session.targetUID, targets: Set(engine.session.blockedSigningIDs), uptime: uptime) }, now: Date(), uptime: uptime) {
                            let key = "\(target.pid):\(target.startSeconds):\(target.startMicros)"
                            if let prior = attempted[key] {
                                if uptime - prior < 2 { continue }
                                _ = RootGuardCore.terminate(target, force: true)
                            } else {
                                attempted[key] = uptime
                                _ = RootGuardCore.terminate(target)
                            }
                        }
                    }
                } else { engine = nil; current = nil }
            } catch {
                // Missing/invalid/expired policy never causes global denial.
                engine = nil; current = nil
            }
            let uptime = ProcessInfo.processInfo.systemUptime
            if uptime - statusAt >= 1 {
                statusAt = uptime
                let valid = engine?.enforcing(now: Date(), uptime: uptime) ?? false
                do {
                    hostsApplied = try HostsGate.reconcile(session: valid ? current : nil)
                    hostsState = hostsApplied ? "exact_entries_applied" : "not_requested_or_paused"
                } catch HostsGate.Failure.unmanagedConflict { hostsApplied = false; hostsState = "uncovered_unmanaged_hosts_conflict" }
                catch { hostsApplied = false; hostsState = "uncovered_write_or_managed_section_error" }
                let snapshot: [String: Any] = ["service": "dev.stanislav.focusdeck.guard", "pid": getpid(),
                    "sessionId": current?.sessionId.uuidString as Any? ?? NSNull(),
                    "targetUID": current?.targetUID as Any? ?? NSNull(),
                    "expiresAt": current.map { ISO8601DateFormatter().string(from: $0.expiresAt) } as Any? ?? NSNull(),
                    "blockedSigningIDs": current?.blockedSigningIDs ?? [],
                    "blockedDomains": current?.blockedDomains ?? [], "hostsState": hostsState,
                    "pauseUntil": current?.pauseUntil.map { ISO8601DateFormatter().string(from: $0) } as Any? ?? NSNull(),
                    "active": valid, "hostsApplied": hostsApplied, "domainCoverage": "exact_system_resolver_names_only",
                    "updatedAt": ISO8601DateFormatter().string(from: Date())]
                if let data = try? JSONSerialization.data(withJSONObject: snapshot, options: [.sortedKeys]) {
                    let url = URL(fileURLWithPath: "/var/run/dev.stanislav.focusdeck.guard.status.json")
                    try? data.write(to: url, options: .atomic)
                    try? FileManager.default.setAttributes([.posixPermissions: 0o644], ofItemAtPath: url.path)
                }
            }
            Thread.sleep(forTimeInterval: 0.25)
        }
    }
    enum Failure: Error { case requiresRootDaemon, unsafeOwnership }
}

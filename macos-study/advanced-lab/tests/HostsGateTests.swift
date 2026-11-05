import Foundation
@main enum HostsGateTests {
    static func main() throws {
        let now = Date()
        var session = GuardSession(schemaVersion: 1, sessionId: UUID(), targetUID: 501, createdAt: now, expiresAt: now.addingTimeInterval(60), blockedSigningIDs: ["dev.fixture"], pauseUntil: nil)
        session.blockedDomains = ["youtube.com", "chatgpt.com"]; session.hostsEnabled = true
        for baseline in ["127.0.0.1 localhost\n# private unrelated entry\n10.10.2.77 gitlab.eskhata.com\n", "127.0.0.1 localhost", "127.0.0.1 localhost\r\n", ""] {
            let applied = try HostsGate.transform(baseline, session: session)
            precondition(applied.contains("0.0.0.0 youtube.com") && applied.contains(":: youtube.com"))
            precondition(!applied.contains("0.0.0.0 chatgpt.com"))
            let reapplied = try HostsGate.transform(applied, session: session)
            let removed = try HostsGate.transform(applied, session: nil)
            precondition(reapplied == applied && removed == baseline)
            let laterEdit = applied + "10.1.2.3 added-after-session.example\n"
            let recovered = try HostsGate.transform(laterEdit, session: nil)
            precondition(recovered.contains("10.1.2.3 added-after-session.example"))
            precondition(!recovered.contains("youtube.com"))
        }
        do { _ = try HostsGate.transform("# BEGIN FocusDeckGuard v1 malformed\n", session: nil); preconditionFailure() } catch {}
        session.blockedDomains = ["www.youtube.com"]
        do { _ = try HostsGate.transform("1.2.3.4 www.youtube.com # unmanaged user mapping\n", session: session); preconditionFailure() } catch HostsGate.Failure.unmanagedConflict {}
        print("Tagged hosts IPv4/IPv6/idempotence/protected-domain/newline/later-edit/recovery tests passed. Actual hosts file untouched.")
    }
}

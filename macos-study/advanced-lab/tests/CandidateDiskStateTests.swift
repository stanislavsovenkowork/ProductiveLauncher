import Foundation
import Darwin
import CryptoKit
private struct Binding: Decodable { let approvedWindow: WindowPolicy }
private struct Ready: Decodable { let trustDigest: String; let policyRevisionId: UUID }
@main enum CandidateDiskStateTests {
    static func main() throws {
        if CommandLine.arguments.count == 2 {
            let dir = URL(fileURLWithPath: CommandLine.arguments[1]), data = try Data(contentsOf: dir.appendingPathComponent("root-config.json"))
            let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
            let approved = try decoder.decode(Binding.self, from: data).approvedWindow
            let ready = try JSONDecoder().decode(Ready.self, from: Data(contentsOf: dir.appendingPathComponent("ready.json")))
            let store = CandidateDiskStore(directory: dir, expectedOwner: getuid()); var disk = try store.load()
            precondition(ready.trustDigest == digest && ready.policyRevisionId == approved.policyRevisionId)
            precondition(disk.matches(approved: approved, trustDigest: digest) && disk.bootID == "prepared" && disk.policy.reliefRemainingSeconds == approved.reliefBudgetSeconds)
            disk.bootID = "synthetic-boot"; disk.uptimeDeadline = 600; try store.save(disk)
            let reloaded = try store.load(); precondition(reloaded.policy.windowId == approved.windowId)
            print("Python prepared ledger → Swift ready/trust/window decode and durable update passed on temporary files."); return
        }
        let base = ISO8601DateFormatter().date(from: "2026-10-29T12:00:00Z")!
        let target = "dev.stanislav.focusdeck.guard-fixture-two", digest = String(repeating: "a", count: 64)
        let approved = try WindowPolicy.create(start: base, end: base.addingTimeInterval(3600), targets: [target], budget: 300, maxGrant: 300, now: base.addingTimeInterval(-60))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("focusdeck-disk-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700]); defer { try? FileManager.default.removeItem(at: dir) }
        let store = CandidateDiskStore(directory: dir, expectedOwner: getuid())
        do { _ = try store.load(); fatalError("missing ledger treated as new") } catch { }
        var disk = CandidateDiskState(policy: approved, bootID: "prepared", uptimeDeadline: 0, reliefUptimeDeadline: nil, trustDigest: digest)
        do { try store.save(disk); fatalError("missing ledger recreated") } catch { }
        try store.save(disk, requireExisting: false); disk = try store.load(); precondition(disk.matches(approved: approved, trustDigest: digest))
        try FileManager.default.removeItem(at: store.file)
        do { try store.save(disk); fatalError("lost ledger silently recreated") } catch { }
        precondition(!FileManager.default.fileExists(atPath: store.file.path))
        try store.save(disk, requireExisting: false) // explicit temporary-test bootstrap only
        let core = WindowGuardV2(allowedTargets: [target], trustedUID: 501, trustedHash: "test", requireCalendar: true)
        disk.policy.state = .active; disk.bootID = "boot"; disk.uptimeDeadline = 3600; try store.save(disk)
        let original = disk.policy; var requested = original; precondition(requested.relief(now: base))
        let (next, reply) = core.apply(WindowGuardRequest(policy: requested, command: "update"), current: original, peerUID: 501, peerHash: "test", now: base, uptime: 100, boot: "boot")
        precondition(reply.accepted && next != nil); disk.policy = next!; disk.reliefUptimeDeadline = 400
        try store.save(disk) // simulate durable commit followed by lost acknowledgement
        let restarted = try store.load(); precondition(restarted.matches(approved: approved, trustDigest: digest))
        precondition(restarted.policy.windowId == approved.windowId && restarted.policy.reliefRemainingSeconds == 0)
        let status = core.apply(WindowGuardRequest(policy: original, command: "status"), current: restarted.policy, peerUID: 501, peerHash: "test", now: base, uptime: 100, boot: "boot").1
        precondition(status.policy?.windowId == approved.windowId && status.policy?.reliefRemainingSeconds == 0)
        let repeatOld = core.apply(WindowGuardRequest(policy: original, command: "update"), current: restarted.policy, peerUID: 501, peerHash: "test", now: base, uptime: 100, boot: "boot").1
        precondition(!repeatOld.accepted)
        disk = restarted; disk.policy.state = .exit; disk.policy.reliefUntil = nil; disk.reliefUptimeDeadline = nil; try store.save(disk)
        let tombstone = try store.load(); precondition(tombstone.policy.state == .exit && tombstone.policy.reliefRemainingSeconds == 0)
        var tamper = tombstone; tamper.policy.windowId = UUID(); precondition(!tamper.matches(approved: approved, trustDigest: digest))
        tamper = tombstone; tamper.policy.reliefRemainingSeconds = 301; precondition(!tamper.matches(approved: approved, trustDigest: digest))
        precondition(!tombstone.matches(approved: approved, trustDigest: String(repeating: "b", count: 64)))
        let saved = try Data(contentsOf: store.file); try Data("corrupt".utf8).write(to: store.file)
        do { _ = try store.load(); fatalError("corrupt ledger accepted") } catch { }
        let corrupt = try Data(contentsOf: store.file); precondition(corrupt == Data("corrupt".utf8)); try saved.write(to: store.file)
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        defer { try? FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path) }
        do { try store.save(disk); fatalError("write failure acknowledged") } catch { }
        print("Prepared first ledger: missing/corrupt/tamper refusal, commit+lost ACK, duplicate, restart, terminal tombstone and pre-rename write failure passed in temporary files; no root.")
    }
}

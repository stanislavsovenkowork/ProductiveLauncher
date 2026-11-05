import Foundation

@main enum HostsQATests {
    static func main() throws {
        for initial in ["127.0.0.1 localhost\n# other\n", "127.0.0.1 localhost", ""] {
            let base = Data(initial.utf8), (owned, loopback) = try HostsQA.begin(base, id: UUID())
            let blocked = try HostsQA.replace(current: loopback, expected: loopback, owned: owned, from: .loopback, to: .blocked)
            precondition(String(decoding: blocked, as: UTF8.self).contains("127.0.0.2 \(owned.domain)"))
            let restored = try HostsQA.replace(current: blocked, expected: blocked, owned: owned, from: .blocked, to: nil)
            precondition(restored == base)
            do { _ = try HostsQA.replace(current: blocked + Data("# unrelated edit\n".utf8), expected: blocked, owned: owned, from: .blocked, to: nil); fatalError("concurrent edit accepted") } catch HostsQA.Failure.changed {}
            do { _ = try HostsQA.begin(loopback, id: UUID()); fatalError("existing marker accepted") } catch HostsQA.Failure.conflict {}
        }
        let id = UUID(), domain = "focusdeck-qa-" + id.uuidString.replacingOccurrences(of: "-", with: "").lowercased() + ".invalid"
        do { _ = try HostsQA.begin(Data("127.0.0.1 \(domain)\n".utf8), id: id); fatalError("foreign mapping accepted") } catch HostsQA.Failure.conflict {}
        print("PASS: own QA marker, baseline→deny→exact restore, conflict and concurrent-edit refusal; temporary bytes only")
    }
}

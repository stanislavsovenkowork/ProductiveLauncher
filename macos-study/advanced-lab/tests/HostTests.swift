import Foundation

@MainActor
final class FakePreferences: FilterPreferences {
    var unidentifiedConfiguration = false
    var hasConfiguration: Bool { owner != nil || unidentifiedConfiguration }
    var sessionID: String?
    var owner: String?
    var calls: [String] = []
    var expiry: Date?
    var frozen: StudyPolicy?
    var failSave = false
    func load() async throws { calls.append("load") }
    func save(policy: Data, expiry: Date, sessionID: UUID, category: String) async throws {
        calls.append("save"); owner = HostController.extensionID
        self.sessionID = sessionID.uuidString
        self.expiry = expiry
        frozen = try JSONDecoder().decode(StudyPolicy.self, from: policy)
        if failSave { throw Failure.save }
    }
    func remove() async throws { calls.append("remove"); owner = nil }
    enum Failure: Error { case save }
}

@main
@MainActor
enum HostTests {
    static func main() async throws {
        let policy = StudyPolicy(blockedDomains: ["youtube.com"], protectedDomains: ["openai.com"], blockedAppBundleIds: [])
        let fake = FakePreferences()
        let host = HostController(preferences: fake)
        try await host.start(policy: policy, minutes: 1, sessionID: UUID(), category: "study")
        precondition(fake.calls == ["load", "save"])
        precondition(fake.expiry!.timeIntervalSinceNow > 59 && fake.expiry!.timeIntervalSinceNow <= 60)
        precondition(fake.frozen!.blockedDomains == policy.blockedDomains)
        do { try await host.recover(sessionID: UUID()); preconditionFailure() } catch {}
        precondition(fake.owner == HostController.extensionID)
        try await host.recover()
        precondition(fake.calls.suffix(2) == ["load", "remove"] && fake.owner == nil)
        for minutes in [-1, 0, 26, Double.infinity, Double.nan] {
            let invalid = FakePreferences()
            do { try await HostController(preferences: invalid).start(policy: policy, minutes: minutes, sessionID: UUID(), category: "study"); preconditionFailure() } catch {}
            precondition(invalid.calls.isEmpty)
        }
        let failed = FakePreferences()
        failed.failSave = true
        let failedHost = HostController(preferences: failed)
        do { try await failedHost.start(policy: policy, minutes: 1, sessionID: UUID(), category: "study"); preconditionFailure() } catch {}
        try await failedHost.recover()
        precondition(failed.calls == ["load", "save", "load", "remove"])
        fake.owner = "foreign.filter"
        fake.calls = []
        do { try await host.start(policy: policy, minutes: 1, sessionID: UUID(), category: "study"); preconditionFailure() } catch {}
        do { try await host.recover(); preconditionFailure() } catch {}
        precondition(fake.calls == ["load", "load"] && fake.owner == "foreign.filter")
        fake.owner = nil
        fake.unidentifiedConfiguration = true
        fake.calls = []
        do { try await host.start(policy: policy, minutes: 1, sessionID: UUID(), category: "study"); preconditionFailure() } catch {}
        do { try await host.recover(); preconditionFailure() } catch {}
        precondition(fake.calls == ["load", "load"])
        print("Host save/expiry/removal, invalid-session and foreign-owner isolation checks passed (fake preferences only).")
    }
}

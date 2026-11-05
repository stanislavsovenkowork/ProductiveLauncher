import Foundation

@main enum CommitmentChecks {
    static func main() throws {
        var value = ProtectionWindow(id: UUID(), domains: ["info.cern.ch"], applications: [], remaining: 7200,
            boot: "first", lastTick: 100, addresses: [], validation: nil, diagnostic: nil)
        precondition(value.valid)
        let fullList = (0..<SiteDomainPolicy.maximumDomains).map { "site\($0).example" }.sorted()
        precondition(SiteDomainPolicy.validList(fullList, includeSubdomains: true))
        precondition(!SiteDomainPolicy.validList((fullList + ["extra.example"]).sorted(), includeSubdomains: true))
        value.domains = fullList; precondition(value.valid)
        value.domains.append("extra.example"); value.domains.sort(); precondition(!value.valid)
        value.domains = ["info.cern.ch"]
        value.requestExit(); value.advance(boot: "first", tick: 130)
        precondition(value.remaining == 7170 && value.exitRemaining == 3570)
        value.requestExit(); precondition(value.exitRemaining == 3570)
        let restored = try JSONDecoder().decode(ProtectionWindow.self, from: JSONEncoder().encode(value))
        value = restored; value.advance(boot: "second", tick: 20)
        precondition(value.remaining == 7170 && value.exitRemaining == 3570 && value.bootResumes == 1)
        value.advance(boot: "second", tick: 80)
        precondition(value.remaining == 7110 && value.exitRemaining == 3510)
        value.advance(boot: "second", tick: 3590)
        precondition(value.ended && value.remaining == 3600)
        value.exitRemaining = nil; precondition(!value.ended)
        value.advance(boot: "second", tick: 7190); precondition(value.ended)
        value.remaining = .infinity; precondition(!value.valid)
        let legacy = "{\"id\":\"00000000-0000-0000-0000-000000000001\",\"domains\":[\"info.cern.ch\"],\"applications\":[],\"remaining\":600,\"boot\":\"old\",\"lastTick\":42,\"addresses\":[]}"
        value = try JSONDecoder().decode(ProtectionWindow.self, from: Data(legacy.utf8))
        value.advance(boot: "new", tick: 12)
        precondition(value.valid && value.remaining == 600 && value.exitRemaining == nil)
        print("PASS: persisted exit wait, repeat request, new boot, monotonic countdown, natural expiry, invalid state, 0.9 migration, consistent total-domain limit")
    }
}

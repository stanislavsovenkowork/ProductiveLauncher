import Foundation
import Darwin

@main enum SiteDomainPolicyTests {
 static func main() throws {
  let normalized = try SiteDomainPolicy.parse(" NEWS.Example, videos.example ")
  precondition(normalized == ["news.example", "videos.example"])
  for bad in ["https://news.example/path", "*.example.com", "1.1.1.1", "foo:443", "gitlab.eskhata.com", "eskhata.com", "api.openai.com", "chatgpt.com", "bad..example", "-bad.example", "x.example, x.example"] {
   precondition((try? SiteDomainPolicy.parse(bad)) == nil, "invalid domain accepted: \(bad)")
  }
  let oversized = (0..<32).map { String(repeating: "a", count: 60) + "." + String(repeating: "b", count: 60) + "\($0).example" }.joined(separator: ",")
  precondition((try? SiteDomainPolicy.parse(oversized)) == nil)
  precondition(SiteDomainPolicy.validList(["eskhata.com"]) && !SiteDomainPolicy.validList(["eskhata.com"], includeSubdomains: true))
  let now = Date(), revision = UUID()
  var p = WindowPolicy(policyRevisionId: revision, windowId: UUID(), localDate: "2026-11-02", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(300), reliefBudgetSeconds: 60, reliefRemainingSeconds: 60, reliefMaxGrantSeconds: 30, targetRefs: [], siteDomains: ["news.example"], state: .active)
  p.exitCoolingOffSeconds = 1; p.siteSubdomains = true
  let ledger = UserProtectionLedger(schemaVersion: 1, policy: p, signingRequirements: [])
  precondition(ledger.valid(allowSynthetic: true))
  let core = WindowGuardV2(allowedTargets: [], trustedUID: getuid(), trustedHash: "aa")
  let publish = core.apply(WindowGuardRequest(policy: p, command: "publish"), current: nil, peerUID: getuid(), peerHash: "aa", now: now)
  precondition(publish.1.accepted)
  var edited = p; edited.siteDomains = ["other.example"]
  precondition(!core.apply(WindowGuardRequest(policy: edited, command: "update"), current: p, peerUID: getuid(), peerHash: "aa", now: now).1.accepted)
  edited = p; edited.siteSubdomains = false
  precondition(!core.apply(WindowGuardRequest(policy: edited, command: "update"), current: p, peerUID: getuid(), peerHash: "aa", now: now).1.accepted)
  var relief = p; precondition(relief.relief(now: now))
  let accepted = core.apply(WindowGuardRequest(policy: relief, command: "update"), current: p, peerUID: getuid(), peerHash: "aa", now: now)
  precondition(accepted.1.accepted && accepted.0?.reliefRemainingSeconds == 30 && accepted.0?.siteDomains == ["news.example"])
  var schedule = WindowSchedule(policyRevisionId: revision, weekdays: [1,2,3,4,5,6,7], startMinute: 900, endMinute: 1200, targetRefs: [], reliefBudgetSeconds: 60, reliefMaxGrantSeconds: 30, exitCoolingOffSeconds: 1)
  schedule.siteDomains = p.siteDomains
  schedule.siteSubdomains = p.siteSubdomains
  let scheduled = UserProtectionLedger(schemaVersion: 1, policy: p, signingRequirements: [], schedule: schedule, lastIssuedLocalDate: p.localDate, clockAnchor: .init(wall: now, uptime: 10, boot: "qa"))
  precondition(scheduled.valid(allowSynthetic: true))
  schedule.siteDomains = ["other.example"]; var mismatched = scheduled; mismatched.schedule = schedule
  precondition(!mismatched.valid(allowSynthetic: true))
  let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
  let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
  var old = try JSONSerialization.jsonObject(with: encoder.encode(p)) as! [String:Any]
  old.removeValue(forKey: "siteDomains"); old.removeValue(forKey: "siteSubdomains"); old["targetRefs"] = ["dev.stanislav.focusdeck.guard-fixture"]
  let decoded = try decoder.decode(WindowPolicy.self, from: JSONSerialization.data(withJSONObject: old))
  precondition(decoded.siteDomains == nil && decoded.reliefRemainingSeconds == p.reliefRemainingSeconds && decoded.windowId == p.windowId)
  var legacy = p; legacy.siteDomains = ["eskhata.com"]; legacy.siteSubdomains = nil
  precondition(UserProtectionLedger(schemaVersion: 1, policy: legacy, signingRequirements: []).valid(allowSynthetic: true))
  print("PASS: exact domain validation, protected hosts, legacy decode, site-only window, immutable targets, budget and schedule binding")
 }
}

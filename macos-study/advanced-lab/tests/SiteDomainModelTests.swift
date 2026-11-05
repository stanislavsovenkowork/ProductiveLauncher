import Foundation
import Darwin
@MainActor final class SiteFakeService: CalendarServiceManaging {
 var state: CalendarServiceState = .enabled
 var unregisterCalls = 0
 func register() throws { state = .enabled }
 func unregister() throws { unregisterCalls += 1; state = .absent }
}
@main @MainActor enum SiteDomainModelTests {
 static func main() async throws {
  let a = CommandLine.arguments
  guard a.count == 4, let root = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], root.hasPrefix("/tmp/focusdeck-") else { fatalError("temporary paths required") }
  let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[2], "FOCUSDECK_USER_TEST_SOCKET": a[3]]
  try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; try await Task.sleep(for: .milliseconds(20)) }
  let bridge = WindowCandidateBridge(path: a[3], expectedUID: getuid())
  let model = SessionModel(candidateBridge: bridge)
  var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
  let tomorrow = cal.date(byAdding: .day, value: 1, to: Date())!
  model.windowStart = cal.date(bySettingHour: 16, minute: 0, second: 0, of: tomorrow)!
  model.windowEnd = cal.date(bySettingHour: 17, minute: 0, second: 0, of: tomorrow)!
  model.recurringWeekdays = Set(1...7); model.windowSites = " NEWS.example, videos.example "
  precondition(model.selectedProtectionTargets.isEmpty && model.canSaveProtectionSetup)
  model.saveWindow()
  for _ in 0..<150 { if model.candidateAuthoritative { break }; try await Task.sleep(for: .milliseconds(20)) }
  guard model.candidateAuthoritative, let policy = model.windowPolicy else { fatalError("site-only enrollment was not confirmed") }
  precondition(policy.targetRefs.isEmpty && policy.siteDomains == ["news.example", "videos.example"] && policy.siteSubdomains == true && model.windowSchedule?.siteDomains == policy.siteDomains && model.windowSchedule?.siteSubdomains == true)
  let response = await bridge.request(policy, command: "status")
  precondition(response?.policy?.windowId == policy.windowId && response?.policy?.siteDomains == policy.siteDomains && response?.scheduleStatus == "scheduled")
  let reopened = SessionModel(candidateBridge: bridge); await reopened.syncCandidate()
  precondition(reopened.candidateAuthoritative && reopened.windowPolicy?.windowId == policy.windowId && reopened.windowPolicy?.siteDomains == policy.siteDomains && reopened.windowPolicy?.reliefRemainingSeconds == policy.reliefRemainingSeconds)
  agent.terminate(); agent.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: a[3]))
  let activeSocket = a[3] + "-active", activeDir = a[2] + "-active"
  func launch() throws -> Process {
   let p = Process(); p.executableURL = URL(fileURLWithPath: a[1]); p.environment = ["FOCUSDECK_USER_TEST_DIR": activeDir, "FOCUSDECK_USER_TEST_SOCKET": activeSocket]; try p.run(); return p
  }
  var activeAgent = try launch()
  defer { if activeAgent.isRunning { activeAgent.terminate(); activeAgent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: activeSocket) { break }; try await Task.sleep(for: .milliseconds(20)) }
  let activeBridge = WindowCandidateBridge(path: activeSocket, expectedUID: getuid())
  let now = Date(); var active = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-11-02", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(300), reliefBudgetSeconds: 60, reliefRemainingSeconds: 60, reliefMaxGrantSeconds: 10, targetRefs: [], siteDomains: ["qa.example"], state: .active); active.exitCoolingOffSeconds = 1
  guard let agentHash = RootSocketServer.verifiedHash(pid: activeAgent.processIdentifier), RootSocketServer.verifiedHash(at: URL(fileURLWithPath: a[1])) == agentHash else { fatalError("agent signature unavailable") }
  let trustedBridge = WindowCandidateBridge(path: activeSocket, expectedUID: getuid(), expectedHash: agentHash)
  let wrongBridge = WindowCandidateBridge(path: activeSocket, expectedUID: getuid(), expectedHash: String(repeating: "0", count: 40))
  let trustedReply = await trustedBridge.request(active, command: "status")
  let rejectedReply = await wrongBridge.request(active, command: "status")
  precondition(trustedReply?.accepted == true && rejectedReply == nil)
  let cold = SessionModel(candidateBridge: activeBridge); cold.windowPolicy = nil
  let fake = SiteFakeService(), service = CalendarServiceControl(service: fake); var hostRemovals = 0
  precondition(!cold.removalStatusReady && !cold.removalRecoveryAvailable && !cold.canUnregisterUserService && !cold.canRemoveChromeHost)
  await cold.unregisterUserService(service); await cold.withVerifiedRemoval(siteOnly: true) { hostRemovals += 1 }
  precondition(fake.unregisterCalls == 0 && hostRemovals == 0 && !cold.removalRecoveryAvailable)
  await cold.refreshRemovalStatus()
  precondition(cold.removalStatusReady && cold.canUnregisterUserService && cold.canRemoveChromeHost)
  guard (await activeBridge.request(active, command: "publish", requirements: []))?.accepted == true else { fatalError("active site-only publish failed") }
  // The previous status was safe, but the actual action must fetch again.
  await cold.unregisterUserService(service); await cold.withVerifiedRemoval(siteOnly: true) { hostRemovals += 1 }
  precondition(fake.unregisterCalls == 0 && hostRemovals == 0)
  precondition(cold.removalStatusReady && cold.windowPolicy?.windowId == active.windowId && !cold.canUnregisterUserService && !cold.canRemoveChromeHost)
  let manual = ManualAgentControl { _ in ManualAgentReply(ok: true, state: "ready", reason: "connected") }
  fake.state = .absent
  await cold.refreshUserProtection(manual: manual, sm: service)
  precondition(service.state == .absent && manual.state == "ready" && cold.candidateAuthoritative && cold.removalStatusReady && cold.windowPolicy?.windowId == active.windowId)
  fake.state = .missing
  await cold.refreshUserProtection(manual: manual, sm: service)
  precondition(service.state == .missing && manual.state == "ready" && cold.candidateAuthoritative && cold.removalStatusReady && cold.windowPolicy?.windowId == active.windowId)
  for phase in ["active", "relief", "rest", "exit_pending"] {
   if phase == "relief" { precondition(active.relief(now: Date())) }
   if phase == "rest" { precondition(active.returnToWindow(now: Date())); guard (await activeBridge.request(active, command: "update"))?.accepted == true else { fatalError("return failed") }; precondition(active.rest(now: Date())) }
   if phase == "exit_pending" { precondition(active.requestExit(now: Date())) }
   if phase != "active" { guard (await activeBridge.request(active, command: "update"))?.accepted == true else { fatalError("update failed") } }
   await cold.refreshRemovalStatus()
   await cold.unregisterUserService(service); await cold.withVerifiedRemoval(siteOnly: true) { hostRemovals += 1 }
   precondition(fake.unregisterCalls == 0 && hostRemovals == 0 && !cold.removalRecoveryAvailable)
  }
  activeAgent.terminate(); activeAgent.waitUntilExit()
  // A failure of a fresh request, not merely a cold start, enables explicit recovery.
  await cold.unregisterUserService(service)
  precondition(cold.removalRecoveryAvailable && !cold.removalStatusReady && fake.unregisterCalls == 0)
  cold.recoverUserService(service); precondition(fake.unregisterCalls == 1)
  activeAgent = try launch()
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: activeSocket) { break }; try await Task.sleep(for: .milliseconds(20)) }
  try await Task.sleep(for: .milliseconds(1200)); precondition(active.exitWindow(now: Date()))
  guard (await activeBridge.request(active, command: "update"))?.accepted == true else { fatalError("exit failed") }
  await cold.refreshRemovalStatus(); precondition(cold.canUnregisterUserService && cold.canRemoveChromeHost)
  await cold.withVerifiedRemoval(siteOnly: true) { hostRemovals += 1 }; await cold.unregisterUserService(service)
  precondition(hostRemovals == 1 && fake.unregisterCalls == 2)
  activeAgent.terminate(); activeAgent.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: activeSocket))
  let expiredSocket = a[3] + "-expired", expiredDir = a[2] + "-expired"
  let expiringAgent = Process(); expiringAgent.executableURL = URL(fileURLWithPath: a[1]); expiringAgent.environment = ["FOCUSDECK_USER_TEST_DIR": expiredDir, "FOCUSDECK_USER_TEST_SOCKET": expiredSocket]; try expiringAgent.run()
  defer { if expiringAgent.isRunning { expiringAgent.terminate(); expiringAgent.waitUntilExit() } }
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: expiredSocket) { break }; try await Task.sleep(for: .milliseconds(20)) }
  let expiredBridge = WindowCandidateBridge(path: expiredSocket, expectedUID: getuid())
  var expiring = active; expiring.windowId = UUID(); expiring.policyRevisionId = UUID(); expiring.state = .active; expiring.reliefRemainingSeconds = expiring.reliefBudgetSeconds; expiring.reliefUntil = nil; expiring.exitAvailableAt = nil; expiring.exitBootID = nil; expiring.exitUptimeDeadline = nil; expiring.stateBeforeExitPending = nil; expiring.startsAt = Date().addingTimeInterval(-1); expiring.endsAt = Date().addingTimeInterval(2)
  guard (await expiredBridge.request(expiring, command: "publish", requirements: []))?.accepted == true else { fatalError("expiring publish failed") }
  try await Task.sleep(for: .milliseconds(2500))
  let expiredModel = SessionModel(candidateBridge: expiredBridge); expiredModel.windowPolicy = nil; await expiredModel.refreshRemovalStatus()
  precondition(expiredModel.windowPolicy?.state == .expired && expiredModel.canUnregisterUserService && expiredModel.canRemoveChromeHost)
  await expiredModel.unregisterUserService(service); precondition(fake.unregisterCalls == 3)
  expiringAgent.terminate(); expiringAgent.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: expiredSocket))
  print("PASS: site-only enrollment, pinned peer, cold status gate; active/relief/rest/pending block, failure recovery, exit/expiry removal")
 }
}

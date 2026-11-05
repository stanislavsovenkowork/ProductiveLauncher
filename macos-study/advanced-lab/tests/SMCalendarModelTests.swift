import Foundation
import Darwin
@main @MainActor enum SMCalendarModelTests {
 static func main() async throws {
    let args = CommandLine.arguments; guard args.count == 4, let root = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], root.hasPrefix("/tmp/focusdeck-") else { fatalError("temporary data and daemon required") }
    let daemon = Process(); daemon.executableURL = URL(fileURLWithPath: args[1]); daemon.environment = ["FOCUSDECK_SM_FAKE_DIR": args[2], "FOCUSDECK_SM_FAKE_SOCKET": args[3]]; try daemon.run()
    defer { if daemon.isRunning { daemon.terminate(); daemon.waitUntilExit() } }
    for _ in 0..<100 { if FileManager.default.fileExists(atPath: args[3]) { break }; try await Task.sleep(for: .milliseconds(10)) }
    let now = Date(); var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(120), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active); p.exitCoolingOffSeconds = 1
    try WindowPolicyStore(directory: URL(fileURLWithPath: root)).save(p, event: "configured")
    let model = SessionModel(fixtureBridge: WindowFixtureBridge(path: args[3], expectedUID: getuid()))
    await model.syncFixture(); precondition(model.fixtureAcknowledgedState == .active)
    model.windowAction("relief"); try await Task.sleep(for: .milliseconds(500)); precondition(model.fixtureAcknowledgedState == .limitedRelief && model.windowPolicy!.reliefRemainingSeconds < 30)
    model.windowAction("returned"); try await Task.sleep(for: .milliseconds(500)); precondition(model.fixtureAcknowledgedState == .active)
    model.windowAction("exit_requested"); try await Task.sleep(for: .milliseconds(500)); precondition(model.fixtureAcknowledgedState == .exitPending)
    try await Task.sleep(for: .seconds(1)); model.windowAction("exit"); try await Task.sleep(for: .milliseconds(500)); precondition(model.windowPolicy?.state == .exit && model.fixtureAcknowledgedState == .exit)
    daemon.terminate(); daemon.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: args[3]))
    print("SessionModel → fake helper → synthetic child: window, relief, return and exit passed; no root registration.")
 }
}

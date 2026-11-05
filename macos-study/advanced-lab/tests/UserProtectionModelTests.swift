import Foundation
import Darwin
@main @MainActor enum UserProtectionModelTests {
 static func main() async throws {
    let a = CommandLine.arguments; guard a.count == 4, ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"]?.hasPrefix("/tmp/focusdeck-") == true else { fatalError("isolated paths required") }
    let bridge = WindowCandidateBridge(path: a[3], expectedUID: getuid())
    let model = SessionModel(candidateBridge: bridge)
    let target = ProtectionTarget(id: "dev.stanislav.focusdeck.guard-fixture", name: "Synthetic", teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
    model.selectedProtectionTargets = [target]; model.windowTargets = target.id
    var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
    let now = Date(); var start = cal.date(bySettingHour: 15, minute: 30, second: 0, of: now)!
    if start <= now { start = cal.date(byAdding: .day, value: 1, to: start)! }
    model.windowStart = start; model.windowEnd = start.addingTimeInterval(1800)
    model.recurringWeekdays = [((cal.component(.weekday, from: start) + 5) % 7) + 1]
    model.saveWindow(); try await Task.sleep(for: .milliseconds(500))
    precondition(!model.candidateAuthoritative && model.canRetryUserEnrollment && FileManager.default.fileExists(atPath: model.userPendingURL.path))
    let agent = Process(); agent.executableURL = URL(fileURLWithPath: a[1]); agent.environment = ["FOCUSDECK_USER_TEST_DIR": a[2], "FOCUSDECK_USER_TEST_SOCKET": a[3]]; try agent.run(); defer { if agent.isRunning { agent.terminate(); agent.waitUntilExit() } }
    for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; try await Task.sleep(for: .milliseconds(10)) }
    await model.retryUserEnrollment()
    precondition(model.candidateAuthoritative && !model.canRetryUserEnrollment && model.windowPolicy?.targetRefs == [target.id])
    let id = model.windowPolicy!.windowId, remaining = model.windowPolicy!.reliefRemainingSeconds
    agent.terminate(); agent.waitUntilExit(); await model.syncCandidate(); precondition(!model.candidateAuthoritative)
    let restarted = Process(); restarted.executableURL = agent.executableURL; restarted.environment = agent.environment; try restarted.run(); defer { if restarted.isRunning { restarted.terminate(); restarted.waitUntilExit() } }
    for _ in 0..<100 { if FileManager.default.fileExists(atPath: a[3]) { break }; try await Task.sleep(for: .milliseconds(10)) }
    let restored = SessionModel(candidateBridge: bridge); await restored.syncCandidate()
    precondition(restored.candidateAuthoritative && restored.windowPolicy?.windowId == id && restored.windowPolicy?.reliefRemainingSeconds == remaining)
    restarted.terminate(); restarted.waitUntilExit()
    print("Main SessionModel: explicit selected synthetic target, offline pending, retry, committed window and restart status passed.")
 }
}

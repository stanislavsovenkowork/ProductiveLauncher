import Foundation
@main @MainActor enum BrowserCoordinatorTests {
    static func main() async throws {
        let worker = URL(fileURLWithPath: CommandLine.arguments[1])
        let fakePolicy = URL(fileURLWithPath: "/tmp/focusdeck-browser-fixture-policy-not-read")
        let timed = BrowserCoordinator(worker: worker, policy: fakePolicy, timeout: 0.15)
        timed.start()
        let start = ProcessInfo.processInfo.systemUptime
        await timed.poll(enabled: true, active: true)
        precondition(ProcessInfo.processInfo.systemUptime - start < 2)
        precondition(timed.status.contains("не ответил"))
        let cancellable = BrowserCoordinator(worker: worker, policy: fakePolicy, timeout: 5)
        cancellable.start()
        let running = Task { await cancellable.poll(enabled: true, active: true) }
        try await Task.sleep(for: .milliseconds(100))
        let action = ProcessInfo.processInfo.systemUptime
        cancellable.cancelPoll()
        precondition(ProcessInfo.processInfo.systemUptime - action < 0.1)
        await running.value
        print("Unresponsive browser-worker timeout/cancel tests passed on own fixture only; main actor remains responsive, no AppleEvents.")
    }
}

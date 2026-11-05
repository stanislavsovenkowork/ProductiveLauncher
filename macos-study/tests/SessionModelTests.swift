import Foundation

@MainActor
final class SlowReceiver: ReceiverSyncing {
    var status = "fixture slow receiver"
    func sync() async { try? await Task.sleep(for: .seconds(4)) }
}

@main @MainActor enum SessionModelTests {
    static func main() async throws {
        guard let path = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"], path.contains("focusdeck") else { fatalError("Explicit fictional data directory required") }
        let model = SessionModel(receiver: SlowReceiver())
        model.state.taskTitle = "Synthetic timer isolation"
        model.start()
        let delivery = Task { await model.sync() }
        for _ in 0..<4 { try await Task.sleep(for: .seconds(1)); model.tick() }
        await delivery.value
        precondition(model.state.phase == .running && model.state.remainingSeconds < 1497 && model.state.remainingSeconds > 1494)
        let knownElapsed = model.state.segmentElapsed
        try await Task.sleep(for: .seconds(3.2))
        model.tick()
        precondition(model.state.phase == .returnReady)
        let lines = try String(contentsOf: model.store.eventsURL, encoding: .utf8).split(separator: "\n")
        let rows = try lines.map { try JSONSerialization.jsonObject(with: Data($0.utf8)) as! [String: Any] }
        let paused = rows.first { $0["type"] as? String == "paused" }!
        let seconds = (paused["metrics"] as! [String: Any])["focusSeconds"] as! Double
        precondition(abs(seconds - knownElapsed) < 0.01)
        let stateURL = model.store.stateURL
        try Data("corrupt-fixture".utf8).write(to: stateURL)
        let corrupt = SessionModel(receiver: SlowReceiver())
        precondition(corrupt.storageBlocked)
        corrupt.state.taskTitle = "Must not overwrite source"
        corrupt.persist()
        let unchanged = try String(contentsOf: stateURL, encoding: .utf8)
        precondition(unchanged == "corrupt-fixture")
        corrupt.archiveCorruptContext()
        precondition(!corrupt.storageBlocked)
        let backups = try FileManager.default.contentsOfDirectory(atPath: model.store.directory.path).filter { $0.hasPrefix("session-corrupt-") }
        precondition(backups.count == 1)
        print("Native model slow-receiver isolation, known-before-gap accounting and corrupt-context archive tests passed.")
    }
}

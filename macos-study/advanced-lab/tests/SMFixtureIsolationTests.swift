import Foundation
@main @MainActor enum SMFixtureIsolationTests {
 static func main() async throws {
    let env = ProcessInfo.processInfo.environment
    guard let path = env["FOCUSDECK_DATA_DIR"], path.hasPrefix("/tmp/focusdeck-"), env["FOCUSDECK_RECEIVER_CONFIG"] != nil, env["FOCUSDECK_REPORT_FILE"] != nil else { fatalError("isolated fixture paths required") }
    let model = SessionModel()
    precondition(model.isolated)
    model.state.taskTitle = "Synthetic isolation test"; model.start()
    await model.sync()
    precondition(model.connection == "Тестовая сборка · передача отключена")
    precondition(model.recoveryEnabled == nil)
    precondition(FileManager.default.fileExists(atPath: model.store.eventsURL.path))
    precondition(!FileManager.default.fileExists(atPath: model.store.directory.appendingPathComponent("receiver-cursor.json").path))
    await model.setRecovery(true)
    precondition(model.recoveryStatus.contains("отключено"))
    print("Fixture SessionModel wrote a synthetic event while receiver/report FIFOs were never opened; no receiver cursor or production recovery control.")
 }
}

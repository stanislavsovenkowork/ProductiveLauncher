import Foundation
@main @MainActor enum ReceiverIsolationTests {
    static func main() async {
        precondition(ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"] != nil)
        precondition(ProcessInfo.processInfo.environment["FOCUSDECK_RECEIVER_CONFIG"] == nil)
        let store = SessionStore(directory: URL(fileURLWithPath: CommandLine.arguments[1]))
        let receiver = LocalReceiver(journal: store)
        await receiver.sync()
        precondition(receiver.status == "Изолированная проверка · передача отключена")
        let control = await LocalCollectionControl.perform(start: false, isolated: true)
        precondition(!control.complete && control.message.contains("отключено"))
        print("Isolated data directory disables receiver before global configuration/token access.")
    }
}

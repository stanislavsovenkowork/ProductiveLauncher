import Foundation
@main @MainActor enum ReceiverClientTests {
    static func main() async {
        let store = SessionStore(directory: URL(fileURLWithPath: CommandLine.arguments[1]))
        let receiver = LocalReceiver(journal: store)
        await receiver.sync()
        print(receiver.status)
    }
}

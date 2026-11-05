import Foundation

enum RootFixtureStopCore {
    static let command = Data("focusdeck-fixture-stop-v2".utf8)
    static let reply = Data("fixture-stopped".utf8)
    static func authorized(uid: UInt32, payload: Data) -> Bool { uid == 0 && payload == command }
}

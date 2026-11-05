import Foundation
@main enum RootFixtureStopTests {
    static func main() {
        precondition(RootFixtureStopCore.authorized(uid: 0, payload: RootFixtureStopCore.command))
        precondition(!RootFixtureStopCore.authorized(uid: 501, payload: RootFixtureStopCore.command))
        precondition(!RootFixtureStopCore.authorized(uid: 0, payload: Data("other".utf8)))
        print("Dedicated fixture-stop command accepts only root UID and exact payload (fake peer).")
    }
}

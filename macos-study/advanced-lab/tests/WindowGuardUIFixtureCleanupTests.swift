import Foundation
import Darwin
@main enum WindowGuardUIFixtureCleanupTests {
    static func main() throws {
        let args = CommandLine.arguments
        guard args.count == 3 else { fatalError("expected socket and fixture data directory") }
        let store = WindowPolicyStore(directory: URL(fileURLWithPath: args[2])); var policy = try store.load()!
        let first = try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: "publish"), path: args[1], expectedUID: getuid())
        precondition(first.accepted); precondition(policy.relief(now: Date()))
        let second = try WindowGuardV2Client.send(WindowGuardRequest(policy: policy, command: "publish"), path: args[1], expectedUID: getuid())
        precondition(second.accepted)
        print("Synthetic child started in relief; fixture cleanup may now be tested.")
    }
}

import Foundation
import Darwin
@main enum RootFixtureHashTests {
    static func main() throws {
        guard CommandLine.arguments.count == 3 else { fatalError("fixture path and expected CDHash required") }
        let child = Process(); child.executableURL = URL(fileURLWithPath: CommandLine.arguments[1]); let out = Pipe(); child.standardOutput = out
        try child.run(); defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
        precondition(out.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        guard let identity = ProcessIdentity.inspect(child.processIdentifier) else { fatalError("fixture process identity missing") }
        precondition(identity.uid == getuid() && identity.parent == UInt32(getpid()) && identity.signingID == "dev.stanislav.focusdeck.guard-fixture")
        precondition(RootSocketServer.verifiedHash(pid: child.processIdentifier) == CommandLine.arguments[2])
        var delivered = 0
        let sent = RootGuardCore.terminate(identity, signal: { pid, signal in
            guard RootSocketServer.verifiedHash(pid: pid) == CommandLine.arguments[2] else { return -1 }
            delivered += 1; return Darwin.kill(pid, signal)
        })
        precondition(sent); child.waitUntilExit()
        precondition(delivered == 1 && child.terminationReason == .uncaughtSignal && child.terminationStatus == SIGTERM)
        print("Own synthetic child: live signing ID, CDHash recheck and SIGTERM passed as current user; no root or personal apps.")
    }
}

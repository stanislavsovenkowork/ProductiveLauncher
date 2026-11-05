import Foundation
import Darwin

@main
enum RootGuardTests {
    static func main() throws {
        let now = Date()
        let uid = getuid()
        let policy = GuardSession(schemaVersion: 1, sessionId: UUID(), targetUID: max(501, uid), createdAt: now,
                                  expiresAt: now.addingTimeInterval(60), blockedSigningIDs: ["dev.stanislav.focusdeck.guard-fixture"], pauseUntil: nil)
        let engine = try RootGuardCore(session: policy, now: now, uptime: 100)
        let fake = ProcessIdentity(pid: 55555, uid: max(501, uid), parent: 4, startSeconds: 1, startMicros: 2, signingID: policy.blockedSigningIDs[0])
        precondition(engine.targets([fake], now: now, uptime: 100) == [fake])
        precondition(engine.targets([fake], now: now, uptime: 161).isEmpty)
        precondition(engine.targets([fake], now: now.addingTimeInterval(61), uptime: 100).isEmpty)
        var signalled = false
        let reused = ProcessIdentity(pid: fake.pid, uid: fake.uid, parent: 4, startSeconds: 9, startMicros: 2, signingID: fake.signingID)
        precondition(!RootGuardCore.terminate(fake, inspect: { _ in reused }, signal: { _, _ in signalled = true; return 0 }))
        precondition(!signalled)
        let fixture = Process(); fixture.executableURL = URL(fileURLWithPath: CommandLine.arguments[1])
        let ready = Pipe(); fixture.standardOutput = ready
        try fixture.run()
        precondition(ready.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        defer { if fixture.isRunning { fixture.terminate() } }
        Thread.sleep(forTimeInterval: 0.15)
        let identity = ProcessIdentity.inspect(fixture.processIdentifier)!
        precondition(identity.parent == UInt32(getpid()) && identity.signingID == policy.blockedSigningIDs[0])
        precondition(RootGuardCore.terminate(identity))
        fixture.waitUntilExit()
        precondition(fixture.terminationReason == .uncaughtSignal && fixture.terminationStatus == SIGTERM)
        let resistant = Process(); resistant.executableURL = fixture.executableURL; resistant.arguments = ["--ignore-term"]
        let resistantReady = Pipe(); resistant.standardOutput = resistantReady
        try resistant.run()
        precondition(resistantReady.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
        defer { if resistant.isRunning { _ = Darwin.kill(resistant.processIdentifier, SIGKILL) } }
        Thread.sleep(forTimeInterval: 0.15)
        let resistantIdentity = ProcessIdentity.inspect(resistant.processIdentifier)!
        precondition(resistantIdentity.parent == UInt32(getpid()))
        precondition(RootGuardCore.terminate(resistantIdentity))
        Thread.sleep(forTimeInterval: 0.05)
        precondition(resistant.isRunning)
        precondition(RootGuardCore.terminate(resistantIdentity, force: true))
        resistant.waitUntilExit()
        precondition(resistant.terminationStatus == SIGKILL)
        print("Root guard selection/deadline/PID-reuse tests passed; only test-owned signed fixture process terminated, without root.")
    }
}

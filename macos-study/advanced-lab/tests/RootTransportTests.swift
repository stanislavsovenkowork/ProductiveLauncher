import Foundation
import Darwin
@main enum RootTransportTests {
    static func main() throws {
        let directory = "/tmp/fd-ipc-" + UUID().uuidString
        try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        defer { try? FileManager.default.removeItem(atPath: directory) }
        let path = directory + "/socket"
        let server = try RootSocketServer(path: path, ownerUID: getuid())
        let expected = RootSocketServer.verifiedHash(pid: getpid())!
        let finished = DispatchSemaphore(value: 0)
        Thread.detachNewThread {
            for _ in 0..<100 {
                server.drain { request, uid, hash in
                    RootReply(accepted: uid == getuid() && hash == expected && request.command == "fixture", reason: "fixture_peer_checked", expiresAt: nil)
                }
                Thread.sleep(forTimeInterval: 0.005)
            }
            finished.signal()
        }
        let response = try RootSocketClient.send(RootRequest(schemaVersion: 1, command: "fixture", sessionId: UUID(), seconds: nil, hostsEnabled: nil), path: path, expectedServerUID: getuid())
        precondition(response.accepted && response.verifiedPeerPID == getpid())
        finished.wait()
        var pair: [Int32] = [0, 0]
        precondition(socketpair(AF_UNIX, SOCK_STREAM, 0, &pair) == 0)
        defer { close(pair[0]); close(pair[1]) }
        let writer = pair[0]
        let chunksDone = DispatchSemaphore(value: 0)
        Thread.detachNewThread {
            for byte in Data("{\"fragmented\":true}\n".utf8) {
                var byte = byte; _ = Darwin.send(writer, &byte, 1, MSG_NOSIGNAL)
                Thread.sleep(forTimeInterval: 0.002)
            }
            chunksDone.signal()
        }
        let framed = try readRootFrame(pair[1], timeout: 1)
        precondition(String(decoding: framed, as: UTF8.self) == "{\"fragmented\":true}")
        chunksDone.wait()
        let began = ProcessInfo.processInfo.systemUptime
        do { _ = try readRootFrame(pair[1], timeout: 0.05); preconditionFailure() } catch {}
        precondition(ProcessInfo.processInfo.systemUptime - began < 0.2)
        print("Real Unix socket peer UID/PID/valid signature/CDHash transport fixture passed, under own UID in /tmp only.")
    }
}

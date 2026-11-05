import Foundation
import Darwin

enum FixtureClient {
    static func run() throws -> String {
        guard let fixture = Bundle.main.url(forAuxiliaryExecutable: "GuardFixture") else { throw CocoaError(.fileNoSuchFile) }
        let child = Process(); child.executableURL = fixture
        let output = Pipe(); child.standardOutput = output
        try child.run(); defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
        guard output.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8) else { throw CocoaError(.fileReadUnknown) }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw CocoaError(.fileReadUnknown) }; defer { close(fd) }
        var addr = sockaddr_un(); addr.sun_family = sa_family_t(AF_UNIX); addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        let bytes = Array("/var/run/dev.stanislav.focusdeck.smfixture.sock".utf8) + [0]
        withUnsafeMutableBytes(of: &addr.sun_path) { $0.copyBytes(from: bytes) }
        let connected = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard connected == 0 else { throw CocoaError(.fileReadUnknown) }
        var peerUID: uid_t = 1, peerGID: gid_t = 0
        guard getpeereid(fd, &peerUID, &peerGID) == 0, peerUID == 0 else { throw CocoaError(.fileReadNoPermission) }
        let request = String(child.processIdentifier); _ = request.withCString { send(fd, $0, strlen($0), MSG_NOSIGNAL) }
        var timeout = timeval(tv_sec: 2, tv_usec: 0); setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var buf = [UInt8](repeating: 0, count: 32); let count = recv(fd, &buf, buf.count, 0)
        guard count > 0, String(decoding: buf.prefix(count), as: UTF8.self) == "terminated" else { throw CocoaError(.fileReadUnknown) }
        child.waitUntilExit()
        guard child.terminationReason == .uncaughtSignal && child.terminationStatus == SIGTERM else { throw CocoaError(.fileReadUnknown) }
        return "Синтетический процесс завершён тестовой службой."
    }
}

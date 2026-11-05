import Foundation
import Darwin
import Security

func guardAddress(_ path: String) throws -> sockaddr_un {
    var address = sockaddr_un()
    address.sun_family = sa_family_t(AF_UNIX); address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
    let bytes = Array(path.utf8) + [0]
    guard bytes.count <= MemoryLayout.size(ofValue: address.sun_path) else { throw RootSocketFailure.invalidPath }
    withUnsafeMutableBytes(of: &address.sun_path) { target in target.copyBytes(from: bytes) }
    return address
}
enum RootSocketFailure: Error { case invalidPath, systemCall, invalidResponse }

// Newline framing over SOCK_STREAM, bounded in size and time on both sides.
func readRootFrame(_ fd: Int32, timeout: Double, maximum: Int = 4096) throws -> Data {
    let deadline = ProcessInfo.processInfo.systemUptime + timeout
    var result = Data()
    while ProcessInfo.processInfo.systemUptime < deadline {
        var ready = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
        let milliseconds = Int32(max(1, min(2000, (deadline - ProcessInfo.processInfo.systemUptime) * 1000)))
        guard poll(&ready, 1, milliseconds) > 0 else { throw RootSocketFailure.invalidResponse }
        var bytes = [UInt8](repeating: 0, count: 512)
        let count = recv(fd, &bytes, bytes.count, MSG_DONTWAIT)
        guard count > 0 else { throw RootSocketFailure.invalidResponse }
        result.append(contentsOf: bytes.prefix(count))
        guard result.count <= maximum else { throw RootSocketFailure.invalidResponse }
        if let newline = result.firstIndex(of: 10) {
            guard newline == result.index(before: result.endIndex) else { throw RootSocketFailure.invalidResponse }
            return result.prefix(upTo: newline)
        }
    }
    throw RootSocketFailure.invalidResponse
}
func writeRootFrame(_ value: Data, fd: Int32, timeout: Double) throws {
    guard value.count < 4096 else { throw RootSocketFailure.invalidResponse }
    var frame = value; frame.append(10)
    let deadline = ProcessInfo.processInfo.systemUptime + timeout
    var offset = 0
    while offset < frame.count, ProcessInfo.processInfo.systemUptime < deadline {
        let count = frame.withUnsafeBytes { send(fd, $0.baseAddress!.advanced(by: offset), frame.count - offset, MSG_DONTWAIT | MSG_NOSIGNAL) }
        if count > 0 { offset += count; continue }
        guard errno == EAGAIN || errno == EWOULDBLOCK else { throw RootSocketFailure.systemCall }
        var writable = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
        guard poll(&writable, 1, 10) > 0 else { throw RootSocketFailure.systemCall }
    }
    guard offset == frame.count else { throw RootSocketFailure.systemCall }
}

final class RootSocketServer: @unchecked Sendable {
    static let path = "/var/run/dev.stanislav.focusdeck.guard.sock"
    private let fd: Int32
    init(path: String = RootSocketServer.path, ownerUID: UInt32 = 0) throws {
        guard geteuid() == ownerUID else { throw RootSocketFailure.systemCall }
        var old = stat()
        if lstat(path, &old) == 0 {
            guard old.st_uid == ownerUID, old.st_mode & S_IFMT == S_IFSOCK else { throw RootSocketFailure.systemCall }
            // launchd owns the singleton service. A stale own socket after a crash is replaced.
            guard unlink(path) == 0 else { throw RootSocketFailure.systemCall }
        }
        fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw RootSocketFailure.systemCall }
        var address = try guardAddress(path)
        let result = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard result == 0, chmod(path, 0o666) == 0, listen(fd, 8) == 0,
              fcntl(fd, F_SETFL, O_NONBLOCK) == 0 else { close(fd); throw RootSocketFailure.systemCall }
    }
    deinit { close(fd) }
    func drain(handle: (RootRequest, UInt32, String?) throws -> RootReply) {
        for _ in 0..<4 {
            let client = accept(fd, nil, nil)
            guard client >= 0 else { return }
            defer { close(client) }
            var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0
            var size = socklen_t(MemoryLayout<Int32>.size)
            guard getpeereid(client, &uid, &gid) == 0,
                  getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0 else { continue }
            guard let data = try? readRootFrame(client, timeout: 0.05),
                  let request = try? JSONDecoder().decode(RootRequest.self, from: data) else { continue }
            do {
                let reply = try handle(request, uid, Self.verifiedHash(pid: pid))
                let data = try JSONEncoder().encode(reply)
                try writeRootFrame(data, fd: client, timeout: 0.05)
            } catch { continue }
        }
    }
    static func verifiedHash(pid: Int32) -> String? {
        var code: SecCode?
        guard SecCodeCopyGuestWithAttributes(nil, [kSecGuestAttributePid: NSNumber(value: pid)] as CFDictionary, [], &code) == errSecSuccess,
              let code, SecCodeCheckValidity(code, [], nil) == errSecSuccess else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        var details: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, [], &details) == errSecSuccess,
              let map = details as? [String: Any], let hash = map[kSecCodeInfoUnique as String] as? Data else { return nil }
        return hash.map { String(format: "%02x", $0) }.joined()
    }
    static func verifiedHash(at url: URL) -> String? {
        var code: SecStaticCode?, details: CFDictionary?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &code) == errSecSuccess, let code,
              SecStaticCodeCheckValidity(code, [], nil) == errSecSuccess,
              SecCodeCopySigningInformation(code, [], &details) == errSecSuccess,
              let map = details as? [String: Any], let hash = map[kSecCodeInfoUnique as String] as? Data else { return nil }
        return hash.map { String(format: "%02x", $0) }.joined()
    }
}

enum RootSocketClient {
    static func send(_ request: RootRequest, path: String = RootSocketServer.path, expectedServerUID: UInt32 = 0) throws -> RootReply {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
        var timeout = timeval(tv_sec: 2, tv_usec: 0)
        _ = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        _ = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var address = try guardAddress(path)
        let result = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard result == 0 else { throw RootSocketFailure.systemCall }
        var peerUID: uid_t = 0, peerGID: gid_t = 0, peerPID: Int32 = 0
        var peerLength = socklen_t(MemoryLayout<Int32>.size)
        guard getpeereid(fd, &peerUID, &peerGID) == 0, peerUID == expectedServerUID,
              getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &peerPID, &peerLength) == 0 else { throw RootSocketFailure.systemCall }
        let data = try JSONEncoder().encode(request)
        try writeRootFrame(data, fd: fd, timeout: 2)
        let reply = try readRootFrame(fd, timeout: 2)
        var decoded = try JSONDecoder().decode(RootReply.self, from: reply)
        decoded.verifiedPeerPID = peerPID
        return decoded
    }
}

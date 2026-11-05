import Foundation
import Darwin
import Security

#if FAKE_SERVICE_TEST
private let socketPath = "/tmp/focusdeck-smfixture-fake-501.sock"
private let serviceUID = getuid()
#else
private let socketPath = "/var/run/dev.stanislav.focusdeck.smfixture.sock"
private let serviceUID: uid_t = 0
#endif
private let targetHash = "3e481e3d08c534a55081e55313fa2768f44d57bc"
private let expectedUID: uid_t = 501
private final class Stop: @unchecked Sendable {
    private let lock = NSLock(); private var value = false
    func set() { lock.lock(); value = true; lock.unlock() }
    func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
}
private func signature(_ pid: Int32) -> (String, String)? {
    var code: SecCode?; var staticCode: SecStaticCode?; var info: CFDictionary?
    guard SecCodeCopyGuestWithAttributes(nil, [kSecGuestAttributePid: NSNumber(value: pid)] as CFDictionary, [], &code) == errSecSuccess,
          let code, SecCodeCheckValidity(code, [], nil) == errSecSuccess,
          SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode,
          SecCodeCopySigningInformation(staticCode, [], &info) == errSecSuccess,
          let map = info as? [String: Any], let id = map[kSecCodeInfoIdentifier as String] as? String,
          let hash = map[kSecCodeInfoUnique as String] as? Data else { return nil }
    return (id, hash.map { String(format: "%02x", $0) }.joined())
}
private func process(_ pid: Int32) -> proc_bsdinfo? {
    var b = proc_bsdinfo(); let size = Int32(MemoryLayout<proc_bsdinfo>.size)
    return proc_pidinfo(pid, PROC_PIDTBSDINFO, 0, &b, size) == size ? b : nil
}
@main enum SMFixtureDaemon {
    static func main() {
        guard geteuid() == serviceUID else { return }
        var old = stat(); guard lstat(socketPath, &old) != 0 && errno == ENOENT else { return }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { return }; defer { close(fd) }
        var address = sockaddr_un(); address.sun_family = sa_family_t(AF_UNIX); address.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        let bytes = Array(socketPath.utf8) + [0]; withUnsafeMutableBytes(of: &address.sun_path) { $0.copyBytes(from: bytes) }
        let bound = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        guard bound == 0 else { return }
        var own = stat(); guard lstat(socketPath, &own) == 0 else { return }
        defer { var current = stat(); if lstat(socketPath, &current) == 0 && current.st_dev == own.st_dev && current.st_ino == own.st_ino && current.st_uid == serviceUID && current.st_mode & S_IFMT == S_IFSOCK { unlink(socketPath) } }
        guard chmod(socketPath, 0o666) == 0, listen(fd, 4) == 0 else { return }
        let stop = Stop(); signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .global())
        source.setEventHandler { stop.set() }; source.resume(); defer { source.cancel() }
        while !stop.get() {
            var ready = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            guard poll(&ready, 1, 250) > 0 else { continue }
            let client = accept(fd, nil, nil); if client < 0 { continue }
            var uid: uid_t = 0, gid: gid_t = 0, peer: Int32 = 0; var size = socklen_t(MemoryLayout<Int32>.size)
            var timeout = timeval(tv_sec: 1, tv_usec: 0); setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var buf = [UInt8](repeating: 0, count: 32); let count = recv(client, &buf, buf.count, 0)
            var result = "refused"
            if count > 0, getpeereid(client, &uid, &gid) == 0, uid == expectedUID,
               getsockopt(client, SOL_LOCAL, LOCAL_PEERPID, &peer, &size) == 0,
               let pid = Int32(String(decoding: buf.prefix(count), as: UTF8.self)), pid > 1,
               let before = process(pid), before.pbi_uid == uid, before.pbi_ppid == UInt32(peer),
               let (id, hash) = signature(pid), id == "dev.stanislav.focusdeck.guard-fixture", hash == targetHash,
               let after = process(pid), after.pbi_start_tvsec == before.pbi_start_tvsec,
               after.pbi_start_tvusec == before.pbi_start_tvusec, after.pbi_ppid == UInt32(peer),
               kill(pid, SIGTERM) == 0 { result = "terminated" }
            _ = result.withCString { send(client, $0, strlen($0), MSG_NOSIGNAL) }; close(client)
        }
    }
}

import Foundation
import Darwin

// A local NXDOMAIN responder used only by the selected per-domain resolvers.
// It never changes the default DNS server, VPN configuration or routing table.
final class DomainResolver: @unchecked Sendable {
    static let port: UInt16 = 54
    static let directory = "/private/etc/resolver"
    private let fd: Int32
    private let lock = NSLock()
    private var queries = 0
    var deniedQueries: Int { lock.lock(); defer { lock.unlock() }; return queries }

    init() throws {
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { throw POSIXError(.ENOTSOCK) }
        _ = fcntl(fd, F_SETFD, FD_CLOEXEC)
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size); address.sin_family = sa_family_t(AF_INET)
        address.sin_port = Self.port.bigEndian; address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let rc = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        } }
        guard rc == 0 else { close(fd); throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EADDRINUSE) }
        Thread.detachNewThread { [self] in receive() }
    }

    private func receive() {
        while true {
            var packet = [UInt8](repeating: 0, count: 4096)
            var peer = sockaddr_storage(); var length = socklen_t(MemoryLayout<sockaddr_storage>.size)
            let count = withUnsafeMutablePointer(to: &peer) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                recvfrom(fd, &packet, packet.count, 0, $0, &length)
            } }
            guard count >= 17, packet[2] & 0x80 == 0, packet[4] == 0, packet[5] == 1 else { continue }
            var end = 12
            while end < count && packet[end] != 0 {
                let size = Int(packet[end]); guard size <= 63, end + size + 1 < count else { end = count; break }
                end += size + 1
            }
            guard end + 5 <= count else { continue }
            end += 5
            var reply = Array(packet.prefix(end))
            reply[2] = 0x81; reply[3] = 0x83 // response, recursion available, NXDOMAIN
            for index in 6..<12 { reply[index] = 0 }
            lock.lock(); queries += 1; lock.unlock()
            withUnsafePointer(to: &peer) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                _ = sendto(fd, reply, reply.count, 0, $0, length)
            } }
        }
    }

    static func expanded(_ domains: [String]) -> [String] {
        var result = Set(domains)
        let groups: [[String]] = [
            ["youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com"],
            ["instagram.com", "cdninstagram.com", "fbcdn.net"],
            ["tiktok.com", "tiktokcdn.com", "tiktokv.com", "tiktokcdn-us.com", "tiktokv.us", "byteoversea.com", "ibyteimg.com", "ibytedtos.com", "muscdn.com", "musical.ly"]
        ]
        for group in groups where domains.contains(where: { group.contains($0) || group.contains($0.hasPrefix("www.") ? String($0.dropFirst(4)) : $0) }) {
            result.formUnion(group)
        }
        return result.sorted()
    }

    static func contents(_ domain: String) -> Data {
        Data("# FocusDeckProtection: managed active window\ndomain \(domain)\nnameserver 127.0.0.1\nport \(port)\ntimeout 1\nsearch_order 1\n".utf8)
    }

    static func prepare(_ domains: [String]) throws {
        var info = stat()
        if lstat(directory, &info) != 0 {
            guard errno == ENOENT, mkdir(directory, 0o755) == 0 else { throw POSIXError(.EACCES) }
        }
        guard lstat(directory, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o022 == 0 else { throw POSIXError(.EACCES) }
        for domain in domains {
            let path = directory + "/" + domain
            if lstat(path, &info) == 0 {
                guard info.st_uid == 0, info.st_mode & S_IFMT == S_IFREG,
                      try Data(contentsOf: URL(fileURLWithPath: path)) == contents(domain) else { throw POSIXError(.EEXIST) }
            } else if errno != ENOENT { throw POSIXError(.EACCES) }
        }
    }

    static func apply(_ domains: [String], removing previous: [String]) throws -> Bool {
        try prepare(domains)
        var changed = false
        for domain in previous where !domains.contains(domain) {
            let url = URL(fileURLWithPath: directory + "/" + domain)
            if FileManager.default.fileExists(atPath: url.path) {
                guard try Data(contentsOf: url) == contents(domain) else { throw POSIXError(.EEXIST) }
                try FileManager.default.removeItem(at: url); changed = true
            }
        }
        for domain in domains {
            let url = URL(fileURLWithPath: directory + "/" + domain)
            if !FileManager.default.fileExists(atPath: url.path) {
                try contents(domain).write(to: url, options: .atomic)
                guard chmod(url.path, 0o644) == 0 else { throw POSIXError(.EACCES) }
                changed = true
            }
        }
        return changed
    }
}

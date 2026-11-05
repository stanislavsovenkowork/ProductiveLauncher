import Foundation
import Darwin
import Dispatch

// A CONNECT proxy, not a TLS interceptor. The managed Chrome process receives
// immutable proxy flags; destination names are checked before any TCP tunnel.
final class BrowserProxy: @unchecked Sendable {
    static let port = 55
    private struct Tunnel { let client: Int32; let remote: Int32; let host: String; let address: String }
    private let lock = NSLock()
    private var blocked = [String]()
    private var active = false
    private var blockedAddresses = Set<String>()
    private var tunnels = [UUID:Tunnel]()
    private let server: Int32
    private let workers = DispatchSemaphore(value: 512)

    init(domains: [String], addresses: Set<String>, active: Bool) throws {
        blocked = domains; blockedAddresses = addresses; self.active = active
        server = socket(AF_INET, SOCK_STREAM, 0)
        guard server >= 0 else { throw POSIXError(.ENOTSOCK) }
        _ = fcntl(server, F_SETFD, FD_CLOEXEC)
        var reuse: Int32 = 1; setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, 4)
        var address = sockaddr_in(); address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET); address.sin_port = UInt16(Self.port).bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        guard withUnsafePointer(to: &address, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.bind(server, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        } }) == 0, listen(server, 64) == 0 else { close(server); throw POSIXError(.EADDRINUSE) }
        Thread.detachNewThread { [self] in
            while true {
                workers.wait()
                let client = accept(server, nil, nil)
                if client >= 0 {
                    _ = fcntl(client, F_SETFD, FD_CLOEXEC)
                    var timeout = timeval(tv_sec: 15, tv_usec: 0)
                    _ = setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
                    Thread.detachNewThread { [self] in
                        defer { workers.signal() }
                        autoreleasepool { handle(client) }
                    }
                } else { workers.signal() }
            }
        }
    }

    private func deniedLocked(_ host: String) -> Bool {
        guard active else { return false }
        var ipv4 = in_addr(), ipv6 = in6_addr()
        if inet_pton(AF_INET, host, &ipv4) == 1 || inet_pton(AF_INET6, host, &ipv6) == 1 { return true }
        var numeric = addrinfo(); numeric.ai_flags = AI_NUMERICHOST; numeric.ai_family = AF_UNSPEC
        var address: UnsafeMutablePointer<addrinfo>?
        if getaddrinfo(host, nil, &numeric, &address) == 0 { freeaddrinfo(address); return true }
        return blocked.contains { host == $0 || host.hasSuffix("." + $0) }
    }
    func update(domains: [String], addresses: Set<String>, active: Bool) {
        lock.lock(); defer { lock.unlock() }
        blocked = domains; blockedAddresses = addresses; self.active = active
        for tunnel in tunnels.values where deniedLocked(tunnel.host) || (active && blockedAddresses.contains(tunnel.address)) {
            // Worker owns close(). Holding the registry lock prevents FD reuse
            // between identifying a tunnel and shutting its sockets down.
            shutdown(tunnel.client, SHUT_RDWR); shutdown(tunnel.remote, SHUT_RDWR)
        }
    }
    private func denied(_ host: String) -> Bool { lock.lock(); defer { lock.unlock() }; return deniedLocked(host) }
    private func sendAll(_ fd: Int32, _ data: Data) -> Bool {
        var sent = 0
        while sent < data.count {
            let count = data.withUnsafeBytes { Darwin.send(fd, $0.baseAddress!.advanced(by: sent), data.count-sent, MSG_NOSIGNAL) }
            if count < 0 && errno == EINTR { continue }
            if count <= 0 { return false }; sent += count
        }
        return true
    }
    private enum Connection { case connected(Int32, String), denied, unavailable }
    private func connectHost(_ host: String, port: Int) -> Connection {
        var hints = addrinfo(); hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM
        var list: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, String(port), &hints, &list) == 0 else { return .unavailable }
        defer { freeaddrinfo(list) }
        var item = list
        while let value = item {
            var bytes = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(value.pointee.ai_addr, value.pointee.ai_addrlen, &bytes, socklen_t(bytes.count), nil, 0, NI_NUMERICHOST) == 0 else {
                item = value.pointee.ai_next; continue
            }
            let numericAddress = String(decoding: bytes.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
            lock.lock(); let addressDenied = active && blockedAddresses.contains(numericAddress); lock.unlock()
            // An alternate DNS name must not tunnel to the address of an
            // already selected target. This remains effective if PF is late
            // during boot; it is also rechecked under the tunnel registry lock.
            if addressDenied { return .denied }
            let fd = socket(value.pointee.ai_family, SOCK_STREAM, 0)
            if fd >= 0 {
                _ = fcntl(fd, F_SETFD, FD_CLOEXEC); _ = fcntl(fd, F_SETFL, O_NONBLOCK)
                var timeout = timeval(tv_sec: 15, tv_usec: 0)
                _ = setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
                let rc = connect(fd, value.pointee.ai_addr, value.pointee.ai_addrlen)
                var ready = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                if rc == 0 || (errno == EINPROGRESS && poll(&ready, 1, 4000) > 0) {
                    var error: Int32 = 0, size = socklen_t(4)
                    if getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &size) == 0 && error == 0 {
                        _ = fcntl(fd, F_SETFL, 0); return .connected(fd, numericAddress)
                    }
                }
                close(fd)
            }
            item = value.pointee.ai_next
        }
        return .unavailable
    }
    private func handle(_ client: Int32) {
        var remote: Int32 = -1; let id = UUID()
        defer {
            lock.lock(); tunnels.removeValue(forKey: id)
            if remote >= 0 { close(remote) }; close(client); lock.unlock()
        }
        var header = Data()
        while header.count < 16384 && header.range(of: Data([13,10,13,10])) == nil {
            var ready = pollfd(fd: client, events: Int16(POLLIN), revents: 0)
            let polled = poll(&ready, 1, 5000)
            if polled < 0 && errno == EINTR { continue }
            guard polled > 0 else { return }
            var buffer = [UInt8](repeating: 0, count: 4096); let count = recv(client, &buffer, buffer.count, 0)
            guard count > 0 else { return }; header.append(contentsOf: buffer.prefix(count))
        }
        guard let boundary = header.range(of: Data([13,10,13,10])),
              let text = String(data: header[..<boundary.lowerBound], encoding: .utf8),
              let first = text.components(separatedBy: "\r\n").first else { return }
        let parts = first.split(separator: " ")
        guard parts.count == 3 else { return }
        let isConnect = parts[0] == "CONNECT"
        let url = URL(string: isConnect ? "https://" + parts[1] : String(parts[1]))
        guard let url, let name = url.host?.lowercased(), !name.isEmpty else { return }
        var host = name
        while host.hasSuffix(".") { host.removeLast() }
        guard !host.isEmpty, !host.contains("%"), !host.contains("\\"), !host.contains("\0") else { return }
        if denied(host) {
            _ = sendAll(client, Data("HTTP/1.1 403 FocusDeck protected window\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".utf8)); return
        }
        let connected: Int32, address: String
        switch connectHost(host, port: url.port ?? (isConnect ? 443 : 80)) {
        case .connected(let fd, let numeric): connected = fd; address = numeric
        case .denied:
            _ = sendAll(client, Data("HTTP/1.1 403 FocusDeck protected address\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".utf8)); return
        case .unavailable:
            _ = sendAll(client, Data("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".utf8)); return
        }
        remote = connected
        lock.lock(); let refused = deniedLocked(host) || (active && blockedAddresses.contains(address))
        if !refused { tunnels[id] = Tunnel(client: client, remote: remote, host: host, address: address) }
        lock.unlock()
        if refused {
            _ = sendAll(client, Data("HTTP/1.1 403 FocusDeck protected window\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".utf8)); return
        }
        if isConnect {
            guard sendAll(client, Data("HTTP/1.1 200 Connection established\r\n\r\n".utf8)) else { return }
        } else {
            var path = url.path.isEmpty ? "/" : url.path
            if let query = url.query { path += "?" + query }
            var lines = text.components(separatedBy: "\r\n")
            lines[0] = String(parts[0]) + " " + path + " " + parts[2]
            lines.removeAll { $0.lowercased().hasPrefix("proxy-connection:") || $0.lowercased().hasPrefix("connection:") }
            let forwarded = lines.joined(separator: "\r\n") + "\r\nConnection: close\r\n\r\n"
            guard sendAll(remote, Data(forwarded.utf8)) else { return }
        }
        if header.count > boundary.upperBound {
            guard sendAll(remote, header[boundary.upperBound...]) else { return }
        }
        var buffer = [UInt8](repeating: 0, count: 16384)
        while true {
            var ready = [pollfd(fd: client, events: Int16(POLLIN), revents: 0), pollfd(fd: remote, events: Int16(POLLIN), revents: 0)]
            let polled = poll(&ready, 2, 60000)
            if polled < 0 && errno == EINTR { continue }
            guard polled > 0 else { return }
            for index in 0..<2 where ready[index].revents != 0 {
                let source = index == 0 ? client : remote, target = index == 0 ? remote : client
                let count = recv(source, &buffer, buffer.count, 0)
                guard count > 0, sendAll(target, Data(buffer.prefix(count))) else { return }
            }
        }
    }
}

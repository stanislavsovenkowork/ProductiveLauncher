import Foundation
import Darwin

protocol StrictRootAuthorityTransport: Sendable {
    func send(_ envelope: StrictRootEnvelope) throws -> StrictRootReply
}

// Read and mutate only through the authenticated root authority. The expected
// daemon CDHash must come from the reviewed/pinned build, never from the peer.
struct StrictRootIPCClient: StrictRootAuthorityTransport, Sendable {
    let path: String
    let expectedDaemonHash: String
    #if STRICT_ROOT_IPC_TEST
    let expectedUID: uid_t
    init(path: String, expectedDaemonHash: String, expectedUID: uid_t) {
        self.path = path; self.expectedDaemonHash = expectedDaemonHash; self.expectedUID = expectedUID
    }
    #else
    private let expectedUID: uid_t = 0
    init(path: String = "/var/run/dev.stanislav.focusdeck.strict-root.sock", expectedDaemonHash: String) {
        self.path = path; self.expectedDaemonHash = expectedDaemonHash
    }
    #endif
    enum Failure: Error { case invalidEnvelope, invalidPeer, invalidResponse, timeout }
    func send(_ envelope: StrictRootEnvelope) throws -> StrictRootReply {
        guard envelope.validShape(), expectedDaemonHash.count == 40,
              expectedDaemonHash.unicodeScalars.allSatisfy({ CharacterSet(charactersIn:"0123456789abcdefABCDEF").contains($0) }) else { throw Failure.invalidEnvelope }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalidPeer }; defer { close(fd) }
        guard fcntl(fd, F_SETFL, O_NONBLOCK) == 0 else { throw Failure.invalidPeer }
        var address = try guardAddress(path)
        let rc = withUnsafePointer(to:&address) { $0.withMemoryRebound(to:sockaddr.self,capacity:1) { connect(fd,$0,socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        if rc != 0 {
            guard errno == EINPROGRESS || errno == EAGAIN else { throw Failure.invalidPeer }
            var ready = pollfd(fd:fd,events:Int16(POLLOUT),revents:0)
            guard poll(&ready,1,2000) > 0 else { throw Failure.timeout }
            var error: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
            guard getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&size) == 0, error == 0 else { throw Failure.invalidPeer }
        }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard getpeereid(fd,&uid,&gid) == 0, uid == expectedUID,
              getsockopt(fd,SOL_LOCAL,LOCAL_PEERPID,&pid,&size) == 0,
              RootSocketServer.verifiedHash(pid:pid)?.lowercased() == expectedDaemonHash.lowercased() else { throw Failure.invalidPeer }
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        try writeRootFrame(encoder.encode(envelope),fd:fd,timeout:2)
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let reply = try decoder.decode(StrictRootReply.self,from:readRootFrame(fd,timeout:2))
        guard reply.schemaVersion == 1 else { throw Failure.invalidResponse }
        return reply
    }
}

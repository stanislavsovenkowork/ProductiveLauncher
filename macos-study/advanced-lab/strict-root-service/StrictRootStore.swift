import Foundation
import Darwin

// A confined storage primitive, not an installer or running daemon. Production
// callers must use a root-owned private directory; QA uses a temporary owner.
final class StrictRootStore {
    enum Failure: Error { case unsafe, busy, missing, changed, io, tooLarge }
    private let directory: String
    private let owner: uid_t
    private let directoryFD: Int32
    private let lockFD: Int32
    private let maxBytes = 64 * 1024

    init(directory: String, owner: uid_t) throws {
        #if STRICT_ROOT_FIXTURE
        guard directory == "/Library/Application Support/FocusDeckStrictQA", owner == 0, geteuid() == 0 else { throw Failure.unsafe }
        #elseif STRICT_ROOT_QA
        guard directory.hasPrefix("/tmp/focusdeck-strict-root-qa."), owner == getuid() else { throw Failure.unsafe }
        #else
        guard owner == 0, geteuid() == 0, directory == "/Library/Application Support/FocusDeckStrictRoot" else { throw Failure.unsafe }
        #endif
        self.directory = directory; self.owner = owner
        let dir = open(directory, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard dir >= 0 else { throw Failure.unsafe }
        var info = stat()
        guard fstat(dir, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { close(dir); throw Failure.unsafe }
        let lock = openat(dir, "authority.lock", O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard lock >= 0 else { close(dir); throw Failure.unsafe }
        var lockInfo = stat()
        guard fstat(lock, &lockInfo) == 0, lockInfo.st_uid == owner, lockInfo.st_mode & S_IFMT == S_IFREG,
              lockInfo.st_mode & 0o077 == 0, lockInfo.st_nlink == 1 else { close(lock); close(dir); throw Failure.unsafe }
        guard flock(lock, LOCK_EX | LOCK_NB) == 0 else { close(lock); close(dir); throw Failure.busy }
        directoryFD = dir; lockFD = lock
        do { try checkDirectory() } catch { flock(lock, LOCK_UN); close(lock); close(dir); throw error }
    }
    deinit { flock(lockFD, LOCK_UN); close(lockFD); close(directoryFD) }

    private func checkDirectory() throws {
        var path = stat(), opened = stat()
        guard lstat(directory, &path) == 0, fstat(directoryFD, &opened) == 0,
              path.st_mode & S_IFMT == S_IFDIR, path.st_uid == owner, path.st_mode & 0o077 == 0,
              path.st_dev == opened.st_dev, path.st_ino == opened.st_ino else { throw Failure.unsafe }
    }

    func read() throws -> Data? {
        try checkDirectory()
        let fd = openat(directoryFD, "record.json", O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
        if fd < 0 { if errno == ENOENT { return nil }; throw Failure.unsafe }
        defer { close(fd) }
        var s = stat()
        guard fstat(fd, &s) == 0, s.st_uid == owner, s.st_mode & S_IFMT == S_IFREG,
              s.st_mode & 0o077 == 0, s.st_nlink == 1, s.st_size >= 0, s.st_size <= maxBytes else { throw Failure.unsafe }
        var bytes = Data(); var buffer = [UInt8](repeating: 0, count: 8192)
        while true {
            let count = Darwin.read(fd, &buffer, buffer.count)
            if count < 0 { if errno == EINTR { continue }; throw Failure.io }
            if count == 0 { break }
            bytes.append(contentsOf: buffer.prefix(count)); guard bytes.count <= maxBytes else { throw Failure.tooLarge }
        }
        try checkDirectory(); return bytes
    }

    // expected == nil permits first enrollment only. Every transition must use
    // the exact last bytes; a changed or missing ledger is never reconstructed.
    func commit(_ bytes: Data, matching expected: Data?) throws {
        guard !bytes.isEmpty, bytes.count <= maxBytes else { throw Failure.tooLarge }
        guard try read() == expected else { throw Failure.changed }
        let name = ".pending-" + UUID().uuidString
        let fd = openat(directoryFD, name, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw Failure.io }
        defer { close(fd); unlinkat(directoryFD, name, 0) }
        try bytes.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { throw Failure.io }
            var offset = 0
            while offset < raw.count {
                let n = Darwin.write(fd, base.advanced(by: offset), raw.count - offset)
                if n < 0 { if errno == EINTR { continue }; throw Failure.io }
                guard n > 0 else { throw Failure.io }; offset += n
            }
        }
        guard fsync(fd) == 0 else { throw Failure.io }
        try checkDirectory(); guard try read() == expected else { throw Failure.changed }
        guard renameat(directoryFD, name, directoryFD, "record.json") == 0 else { throw Failure.io }
        // A failure here is ambiguous: caller must return unknown, then reload.
        guard fsync(directoryFD) == 0 else { throw Failure.io }
    }
}

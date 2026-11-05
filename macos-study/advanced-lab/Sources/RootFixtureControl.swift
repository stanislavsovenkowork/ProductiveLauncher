import Foundation
import Darwin

// Root-only stop for the dedicated fixture daemon. Never sends signals to a PID.
@main enum RootFixtureControl {
    static func main() throws {
        guard geteuid() == 0, CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--stop" else { throw Failure.invalid }
        let dir = "/Library/Application Support/FocusDeckGuardV2Fixture", socketPath = WindowGuardV2Client.path
        var directoryInfo = stat(), markerInfo = stat(), socketInfo = stat()
        let markerPath = dir + "/socket-inode.json"
        guard lstat(dir, &directoryInfo) == 0, directoryInfo.st_uid == 0, directoryInfo.st_mode & S_IFMT == S_IFDIR, directoryInfo.st_mode & 0o077 == 0,
              lstat(markerPath, &markerInfo) == 0, markerInfo.st_uid == 0, markerInfo.st_mode & S_IFMT == S_IFREG, markerInfo.st_mode & 0o077 == 0, markerInfo.st_size <= 4096,
              lstat(socketPath, &socketInfo) == 0, socketInfo.st_uid == 0, socketInfo.st_mode & S_IFMT == S_IFSOCK else { throw Failure.invalid }
        let marker = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: markerPath))) as? [String: UInt64]
        guard marker?["device"] == UInt64(socketInfo.st_dev), marker?["inode"] == UInt64(socketInfo.st_ino) else { throw Failure.invalid }
        let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
        var address = try guardAddress(socketPath)
        let connected = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
        var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard connected == 0, getpeereid(fd, &uid, &gid) == 0, uid == 0,
              getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0, pid > 1 else { throw Failure.invalid }
        var bytes = [UInt8](repeating: 0, count: Int(MAXPATHLEN) * 4), executableInfo = stat(), currentSocket = stat()
        let expected = dir + "/FocusDeckV2FixtureGuard"
        guard proc_pidpath(pid, &bytes, UInt32(bytes.count)) > 0,
              URL(fileURLWithPath: String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)).resolvingSymlinksInPath().path == URL(fileURLWithPath: expected).resolvingSymlinksInPath().path,
              lstat(expected, &executableInfo) == 0, executableInfo.st_uid == 0, executableInfo.st_mode & S_IFMT == S_IFREG, executableInfo.st_mode & 0o077 == 0,
              lstat(socketPath, &currentSocket) == 0, currentSocket.st_dev == socketInfo.st_dev, currentSocket.st_ino == socketInfo.st_ino else { throw Failure.invalid }
        try writeRootFrame(RootFixtureStopCore.command, fd: fd, timeout: 2)
        guard try readRootFrame(fd, timeout: 2) == RootFixtureStopCore.reply else { throw Failure.invalid }
        print("Dedicated synthetic fixture stop acknowledged; daemon is exiting.")
    }
    enum Failure: Error { case invalid }
}

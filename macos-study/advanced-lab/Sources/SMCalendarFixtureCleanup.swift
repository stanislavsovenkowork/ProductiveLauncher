import Foundation
import Darwin
// This is only linked into the isolated calendar fixture. The caller must first
// validate the terminal policy; unknown content always causes refusal.
enum SMCalendarFixtureCleanup {
    static func clear(directory: URL, owner: uid_t, allowEmpty: Bool = false) throws {
        let path = directory.path
        var info = stat()
        guard lstat(path, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFDIR,
              info.st_mode & 0o077 == 0 else { throw Failure.unsafe }
        let fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { throw Failure.unsafe }; defer { close(fd) }
        var opened = stat(); guard fstat(fd, &opened) == 0, opened.st_dev == info.st_dev, opened.st_ino == info.st_ino else { throw Failure.unsafe }
        let allowed: Set<String> = ["window.json", "events.jsonl"]
        let names = try FileManager.default.contentsOfDirectory(atPath: path)
        guard (names.contains("window.json") || (allowEmpty && names.isEmpty)), Set(names).isSubset(of: allowed) else { throw Failure.unsafe }
        for name in names {
            var entry = stat()
            guard fstatat(fd, name, &entry, AT_SYMLINK_NOFOLLOW) == 0, entry.st_uid == owner,
                  entry.st_mode & S_IFMT == S_IFREG, entry.st_mode & 0o077 == 0, entry.st_nlink == 1 else { throw Failure.unsafe }
        }
        for name in names { guard unlinkat(fd, name, 0) == 0 else { throw Failure.unsafe } }
        guard fsync(fd) == 0 else { throw Failure.unsafe }
        var current = stat(); guard lstat(path, &current) == 0, current.st_dev == opened.st_dev, current.st_ino == opened.st_ino,
              rmdir(path) == 0 else { throw Failure.unsafe }
        let parent = open(directory.deletingLastPathComponent().path, O_RDONLY | O_DIRECTORY | O_CLOEXEC)
        guard parent >= 0 else { throw Failure.unsafe }; defer { close(parent) }
        guard fsync(parent) == 0 else { throw Failure.unsafe }
    }
    enum Failure: Error { case unsafe }
}

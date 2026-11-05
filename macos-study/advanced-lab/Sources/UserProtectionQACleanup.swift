import Foundation
import Darwin
#if USER_GUARD_QA
enum UserProtectionQACleanup {
 static func clear(directory: URL, owner: uid_t, allowEmpty: Bool) throws {
    var info = stat(); guard lstat(directory.path, &info) == 0, info.st_uid == owner, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.invalid }
    let fd = open(directory.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
    var opened = stat(); guard fstat(fd, &opened) == 0, opened.st_dev == info.st_dev, opened.st_ino == info.st_ino else { throw Failure.invalid }
    let names = try FileManager.default.contentsOfDirectory(atPath: directory.path)
    guard (names.contains("protection-ledger.json") || (allowEmpty && names.isEmpty)), Set(names).isSubset(of: ["protection-ledger.json", "events.jsonl"]) else { throw Failure.invalid }
    for name in names { var item = stat(); guard fstatat(fd, name, &item, AT_SYMLINK_NOFOLLOW) == 0, item.st_uid == owner, item.st_mode & S_IFMT == S_IFREG, item.st_mode & 0o077 == 0, item.st_nlink == 1 else { throw Failure.invalid } }
    for name in names { guard unlinkat(fd, name, 0) == 0 else { throw Failure.invalid } }
    guard fsync(fd) == 0 else { throw Failure.invalid }
    var current = stat(); guard lstat(directory.path, &current) == 0, current.st_dev == opened.st_dev, current.st_ino == opened.st_ino, rmdir(directory.path) == 0 else { throw Failure.invalid }
    let parent = open(directory.deletingLastPathComponent().path, O_RDONLY | O_DIRECTORY | O_CLOEXEC); guard parent >= 0 else { throw Failure.invalid }; defer { close(parent) }
    guard fsync(parent) == 0 else { throw Failure.invalid }
 }
 enum Failure: Error { case invalid }
}
#endif

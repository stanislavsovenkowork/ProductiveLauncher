import Foundation
import Darwin

// Recovery-only service for the unique calendar fixture; never edits any other path.
@main enum SMCalendarCleanupDaemon {
 static func main() {
    do { try clean() } catch { fputs("fixture cleanup refused\n", stderr); exit(1) }
 }
 static func clean() throws {
    #if SM_CALENDAR_CLEANUP_FAKE
    let owner = getuid()
    guard let root = ProcessInfo.processInfo.environment["FOCUSDECK_CLEANUP_FAKE_DIR"], root.hasPrefix("/tmp/focusdeck-") else { throw Failure.invalid }
    let socket = ProcessInfo.processInfo.environment["FOCUSDECK_CLEANUP_FAKE_SOCKET"] ?? "/tmp/focusdeck-invalid.sock"
    #else
    let owner: uid_t = 0
    let root = "/Library/Application Support/FocusDeckCalendarFixture"
    let socket = "/var/run/dev.stanislav.focusdeck.calendarfixture.sock"
    #endif
    guard geteuid() == owner else { throw Failure.invalid }
    var details = stat(); guard lstat(socket, &details) != 0, errno == ENOENT else { throw Failure.invalid }
    guard lstat(root, &details) == 0, details.st_uid == owner, details.st_mode & S_IFMT == S_IFDIR, details.st_mode & 0o077 == 0 else { throw Failure.invalid }
    let url = URL(fileURLWithPath: root, isDirectory: true); let file = url.appendingPathComponent("window.json")
    if lstat(file.path, &details) != 0 {
        guard errno == ENOENT else { throw Failure.invalid }
        try SMCalendarFixtureCleanup.clear(directory: url, owner: owner, allowEmpty: true); return
    }
    let fd = open(file.path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC); guard fd >= 0 else { throw Failure.invalid }; defer { close(fd) }
    guard fstat(fd, &details) == 0, details.st_uid == owner, details.st_mode & S_IFMT == S_IFREG,
          details.st_mode & 0o077 == 0, details.st_size <= 16384 else { throw Failure.invalid }
    let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
    let policy = try decoder.decode(WindowPolicy.self, from: FileHandle(fileDescriptor: fd, closeOnDealloc: false).readToEnd() ?? Data())
    guard policy.policyVersion == 2, policy.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"], policy.startsAt < policy.endsAt,
          policy.endsAt.timeIntervalSince(policy.startsAt) <= 180,
          policy.state == .exit || policy.state == .expired || Date() >= policy.endsAt else { throw Failure.invalid }
    try SMCalendarFixtureCleanup.clear(directory: url, owner: owner)
 }
 enum Failure: Error { case invalid }
}

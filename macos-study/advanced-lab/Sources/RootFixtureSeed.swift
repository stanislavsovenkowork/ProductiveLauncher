import Foundation
import Darwin

// Explicit local-only seed for the root fixture QA. It cannot enable the
// production backend and never targets a personal app or network name.
@main enum RootFixtureSeed {
    static func main() throws {
        guard geteuid() != 0, CommandLine.arguments.count == 2 else { throw Failure.unsafe }
        let path = CommandLine.arguments[1]
        guard path.hasPrefix("/tmp/focusdeck-root-ui.") else { throw Failure.unsafe }
        var info = stat()
        guard lstat(path, &info) == 0, info.st_uid == getuid(), info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.unsafe }
        let directory = URL(fileURLWithPath: path), store = WindowPolicyStore(directory: directory)
        guard !FileManager.default.fileExists(atPath: store.snapshotURL.path) else { throw Failure.unsafe }
        let now = Date(); var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let c = cal.dateComponents([.year, .month, .day], from: now)
        let day = String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
        var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: day, startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(150), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: [WindowFixtureBounds.target], state: .active)
        p.exitCoolingOffSeconds = 5
        guard WindowFixtureBounds.permits(p, now: now) else { throw Failure.unsafe }
        try store.save(p, event: "configured", now: now)
        print("Synthetic fixture-only window saved, expires in at most 150 seconds; no root, apps or network changed.")
    }
    enum Failure: Error { case unsafe }
}

import Foundation
import Darwin

// Explicit administrator tool. Never invoked automatically by the app or build.
@main
enum RootGuardAdmin {
    static let directory = URL(fileURLWithPath: "/Library/Application Support/FocusDeckGuard", isDirectory: true)
    static let sessionURL = directory.appendingPathComponent("session.json")
    static func write(_ session: GuardSession) throws {
        try session.validate(now: Date())
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var info = stat()
        guard lstat(directory.path, &info) == 0, info.st_uid == 0,
              info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw Failure.unsafeDirectory }
        let data = try JSONEncoder().encode(session)
        try data.write(to: sessionURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: sessionURL.path)
    }
    static func main() throws {
        let args = CommandLine.arguments
        if args.count == 2, args[1] == "--recover" {
            guard geteuid() == 0 else { throw Failure.requiresAdministrator }
            if FileManager.default.fileExists(atPath: sessionURL.path) { try FileManager.default.removeItem(at: sessionURL) }
            _ = try HostsGate.reconcile(session: nil)
            print("Root app guard policy removed; no further app termination will be requested.")
            return
        }
        if args.count == 4, args[1] == "--pause", let id = UUID(uuidString: args[2]),
           let seconds = Double(args[3]), seconds.isFinite, seconds > 0, seconds <= 300 {
            guard geteuid() == 0 else { throw Failure.requiresAdministrator }
            guard var session = try RootSessionDisk.load(), session.sessionId == id, !session.pauseUsed else { throw Failure.invalidPause }
            try session.validate(now: Date())
            session.pauseUsed = true; session.pauseGrantSeconds = seconds
            session.expiresAt = session.expiresAt.addingTimeInterval(seconds)
            session.pauseUntil = min(session.expiresAt, Date().addingTimeInterval(seconds))
            try write(session)
            print("One bounded pause recorded; app enforcement returns automatically without claiming work resumed.")
            return
        }
        guard (args.count == 6 && args[1] == "--plan") || ((args.count == 7 || (args.count == 8 && args[7] == "--enable-hosts")) && args[1] == "--publish" && args[6] == "--acknowledge-force-quit"), let uid = UInt32(args[3]),
              let minutes = Double(args[4]), minutes.isFinite, minutes > 0, minutes <= 25,
              let sessionID = UUID(uuidString: args[5]) else { throw Failure.usage }
        let policy = try JSONDecoder().decode(StudyPolicy.self, from: Data(contentsOf: URL(fileURLWithPath: args[2])))
        try policy.validate()
        print("Targets: " + policy.blockedAppBundleIds.joined(separator: ", "))
        print("WARNING: force termination can lose unsaved work. Save work before publishing. Websites are not enforced by this daemon.")
        if args[1] == "--plan" { return }
        guard geteuid() == 0 else { throw Failure.requiresAdministrator }
        if let active = try RootSessionDisk.load(), active.expiresAt > Date() { throw Failure.existingActiveSession }
        let now = Date()
        var session = GuardSession(schemaVersion: 1, sessionId: sessionID, targetUID: uid, createdAt: now,
                                   expiresAt: now.addingTimeInterval(minutes * 60), blockedSigningIDs: policy.blockedAppBundleIds, pauseUntil: nil)
        session.blockedDomains = policy.blockedDomains; session.protectedDomains = policy.protectedDomains
        session.hostsEnabled = args.contains("--enable-hosts")
        try write(session)
        print("Bounded app guard policy published. This does not install a daemon or enforce website domains.")
    }
    enum Failure: Error { case requiresAdministrator, unsafeDirectory, usage, invalidPause, existingActiveSession }
}

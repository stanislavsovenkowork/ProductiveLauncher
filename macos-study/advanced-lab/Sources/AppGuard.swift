import Foundation
import AppKit

// A user-session guard, not a security boundary. Default executable mode is dry run.
@main
enum AppGuard {
    static func main() throws {
        let args = CommandLine.arguments
        if args.contains("--check-strict") {
            print("{\"protected\":false,\"websiteCoverage\":\"missing\",\"appCoverage\":\"partial\",\"reason\":\"No installed signed filter, hostname proxy coverage unverified, no protected app launch enforcement\"}")
            exit(3)
        }
        guard args.count >= 2 else { throw GuardError.usage }
        let policy = try JSONDecoder().decode(StudyPolicy.self, from: Data(contentsOf: URL(fileURLWithPath: args[1])))
        try policy.validate()
        let enforce = args.contains("--enforce")
        let minutes: Double
        if let index = args.firstIndex(of: "--minutes"), index + 1 < args.count,
           let parsed = Double(args[index + 1]), parsed.isFinite, parsed > 0, parsed <= 25 {
            minutes = parsed
        } else if args.contains("--minutes") { throw GuardError.usage }
        else { minutes = 25 }
        let expires = ProcessInfo.processInfo.systemUptime + minutes * 60
        let sessionID = UUID()
        let category = args.contains("--work") ? "work" : "study"
        func emit(_ type: String, _ bundle: String? = nil, _ accepted: Bool? = nil) {
            let event: [String: Any] = ["schemaVersion": 1, "eventId": UUID().uuidString, "sessionId": sessionID.uuidString,
                "category": category, "occurredAt": ISO8601DateFormatter().string(from: Date()),
                "source": "mac", "type": type, "evidence": type == "health" ? "activity" : "timer",
                "coverage": "partial", "metrics": ["focusSeconds": NSNull(), "distractionSeconds": NSNull(), "maintenanceSeconds": NSNull()],
                "bundleId": bundle as Any? ?? NSNull(),
                "terminationRequested": accepted as Any? ?? NSNull(), "protected": false,
                "mode": enforce ? "enforce" : "dry-run"]
            if let data = try? JSONSerialization.data(withJSONObject: event, options: [.sortedKeys]),
               let line = String(data: data, encoding: .utf8) { print(line) }
        }
        func inspect(_ app: NSRunningApplication) {
            guard ProcessInfo.processInfo.systemUptime < expires, let bundle = app.bundleIdentifier,
                  policy.shouldTerminate(bundleID: bundle) else { return }
            // Graceful quit can be declined for unsaved work. Never force-kill.
            emit("health", bundle, enforce ? app.terminate() : nil)
        }
        emit("started")
        // Poll every 250 ms; no privileged launch hook or background installation.
        // Retry a declined quit at most every two seconds, without forcing data loss.
        var attempted: [pid_t: TimeInterval] = [:]
        while ProcessInfo.processInfo.systemUptime < expires {
            for app in NSWorkspace.shared.runningApplications {
                if attempted[app.processIdentifier].map({ ProcessInfo.processInfo.systemUptime - $0 < 2 }) == true { continue }
                if let bundle = app.bundleIdentifier, policy.shouldTerminate(bundleID: bundle) {
                    attempted[app.processIdentifier] = ProcessInfo.processInfo.systemUptime
                    inspect(app)
                }
            }
            RunLoop.current.run(until: Date().addingTimeInterval(min(0.25, max(0, expires - ProcessInfo.processInfo.systemUptime))))
        }
        emit("completed")
    }
    enum GuardError: Error { case usage }
}

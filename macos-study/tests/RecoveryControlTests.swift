import Foundation
import CryptoKit
@main enum RecoveryControlTests {
    static func main() async throws {
        let denied = await RecoveryControl.setEnabled(false, isolated: true)
        precondition(!denied.success)
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("focusdeck-recovery-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let runtime = root.appendingPathComponent("runtime"), reports = root.appendingPathComponent("reports")
        try FileManager.default.createDirectory(at: runtime, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: reports, withIntermediateDirectories: true)
        let script = runtime.appendingPathComponent("recovery.py")
        let data = Data("import sys,json\nprint(json.dumps({'enabled':sys.argv[1]=='enable'}))\n".utf8); try data.write(to: script)
        let hash = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        let config: [String: Any] = ["runtimeDirectory": runtime.path, "outputDirectory": reports.path, "runtimeHashes": ["recovery.py": hash]]
        try JSONSerialization.data(withJSONObject: config).write(to: root.appendingPathComponent("review-config.json"))
        let plist: [String: Any] = ["ProgramArguments": [CommandLine.arguments[1]]]
        try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0).write(to: root.appendingPathComponent("local-review.plist"))
        try JSONSerialization.data(withJSONObject: ["sourceIndex": 0, "staleAfterHours": 36]).write(to: reports.appendingPathComponent("recovery-control.json"))
        let stopped = await RecoveryControl.setEnabled(false, isolated: true, fixtureRoot: root); precondition(stopped.success)
        let started = await RecoveryControl.setEnabled(true, isolated: true, fixtureRoot: root); precondition(started.success)
        try Data("changed".utf8).write(to: script)
        let rejected = await RecoveryControl.setEnabled(false, isolated: true, fixtureRoot: root); precondition(!rejected.success)
        let reminderScript = runtime.appendingPathComponent("recovery_reminder.py")
        let source = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("harness/recovery_reminder.py")
        let reminderData = try Data(contentsOf: source); try reminderData.write(to: reminderScript)
        let reminderHash = SHA256.hash(data: reminderData).map { String(format: "%02x", $0) }.joined()
        let reminderConfig: [String: Any] = ["runtimeDirectory": runtime.path, "outputDirectory": reports.path, "runtimeHashes": ["recovery_reminder.py": reminderHash]]
        try JSONSerialization.data(withJSONObject: reminderConfig).write(to: root.appendingPathComponent("review-config.json"))
        let host = root.appendingPathComponent("fake-host"), app = root.appendingPathComponent("FocusDeck.app"), context = root.appendingPathComponent("session.json")
        try Data("#!/bin/sh\n[ \"$1\" = status ] && echo denied\nexit 0\n".utf8).write(to: host)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: host.path)
        try FileManager.default.createDirectory(at: app, withIntermediateDirectories: true)
        let configured = await ReminderControl.run("configure", root: root, host: host, context: context, app: app); precondition(configured.success)
        let enabled = await ReminderControl.run("enable", root: root, host: host, context: context, app: app); precondition(enabled.success && enabled.enabled == true)
        let status = await ReminderControl.run("status", root: root, host: host, context: context, app: app); precondition(status.success && status.permission == "denied")
        let movedApp = root.appendingPathComponent("Other.app")
        let stoppedReminder = await ReminderControl.run("stop", root: root, host: host, context: context, app: movedApp)
        precondition(stoppedReminder.success && stoppedReminder.enabled == false && stoppedReminder.message.contains("неизвестно"))
        let persisted = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("review-config.json"))) as! [String: Any]
        precondition((persisted["recoveryReminder"] as! [String: Any])["enabled"] as? Bool == false)
        print("Recovery control synthetic opt-out/re-enable, isolated refusal and runtime-integrity checks passed; production settings untouched.")
    }
}

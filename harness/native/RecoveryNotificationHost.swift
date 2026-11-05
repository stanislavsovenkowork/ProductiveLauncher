import AppKit
import Foundation
import Darwin
import UserNotifications

let center = UNUserNotificationCenter.current()
let command = CommandLine.arguments.dropFirst().first ?? ""
let notificationID = "focusdeck.recovery.return"
@MainActor func settings() -> String {
    let sem = DispatchSemaphore(value: 0)
    final class Box: @unchecked Sendable { var value = "unknown" }
    let box = Box()
    center.getNotificationSettings { @Sendable value in
        switch value.authorizationStatus {
        case .authorized: box.value = "authorized"
        case .denied: box.value = "denied"
        case .notDetermined: box.value = "not_determined"
        default: box.value = "unknown"
        }
        sem.signal()
    }
    return sem.wait(timeout: .now() + 5) == .success ? box.value : "unknown"
}
if command == "status" { print(settings()); exit(0) }
if command == "request-permission" {
    // This mode is invoked only by an explicit user action, never by scheduler.
    let sem = DispatchSemaphore(value: 0)
    center.requestAuthorization(options: [.alert, .sound]) { @Sendable _, _ in sem.signal() }
    if sem.wait(timeout: .now() + 120) != .success { print("unknown"); exit(1) }
    print(settings()); exit(0)
}
if command == "stop" {
    center.removePendingNotificationRequests(withIdentifiers: [notificationID])
    center.removeDeliveredNotifications(withIdentifiers: [notificationID])
    exit(0)
}
if command != "post" || settings() != "authorized" { exit(2) }
let input = FileHandle.standardInput.readDataToEndOfFile()
guard input.count <= 16384,
      let object = (try? JSONSerialization.jsonObject(with: input)) as? [String: String],
      let body = object["body"], !body.isEmpty, body.count <= 160,
      let appPath = object["appPath"], appPath.hasPrefix("/"), appPath.hasSuffix(".app"),
      let configPath = object["configPath"], configPath.hasPrefix("/"),
      let outputPath = object["outputPath"], outputPath.hasPrefix("/") else { exit(3) }
// Serialize posting against the explicit stop command before checking opt-in.
let lockFD = open(URL(fileURLWithPath: outputPath).appendingPathComponent(".recovery-reminder.lock").path, O_CREAT | O_RDWR, 0o600)
guard lockFD >= 0, flock(lockFD, LOCK_EX) == 0 else { exit(5) }
guard let configData = try? Data(contentsOf: URL(fileURLWithPath: configPath)),
      let config = (try? JSONSerialization.jsonObject(with: configData)) as? [String: Any],
      config["enabledByUser"] as? Bool == true,
      let configuredOutput = config["outputDirectory"] as? String, configuredOutput.hasPrefix("/"),
      URL(fileURLWithPath: configuredOutput).standardizedFileURL.resolvingSymlinksInPath().path == outputPath,
      let reminder = config["recoveryReminder"] as? [String: Any], reminder["enabled"] as? Bool == true,
      let controlData = try? Data(contentsOf: URL(fileURLWithPath: outputPath).appendingPathComponent("recovery-control.json")),
      let control = (try? JSONSerialization.jsonObject(with: controlData)) as? [String: Any], control["enabled"] as? Bool == true else { close(lockFD); exit(6) }
final class HostDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    let appPath: String
    let configPath: String
    init(appPath: String, configPath: String) { self.appPath = appPath; self.configPath = configPath }
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        defer { completionHandler() }
        guard response.notification.request.identifier == notificationID,
              response.actionIdentifier == "OPEN_CONTEXT" || response.actionIdentifier == UNNotificationDefaultActionIdentifier,
              let data = try? Data(contentsOf: URL(fileURLWithPath: configPath)),
              let config = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              config["enabledByUser"] as? Bool == true,
              let reminder = config["recoveryReminder"] as? [String: Any], reminder["enabled"] as? Bool == true,
              let outputPath = config["outputDirectory"] as? String, outputPath.hasPrefix("/"),
              let controlData = try? Data(contentsOf: URL(fileURLWithPath: outputPath).appendingPathComponent("recovery-control.json")),
              let control = (try? JSONSerialization.jsonObject(with: controlData)) as? [String: Any], control["enabled"] as? Bool == true,
              let selectedPath = reminder["openAppPath"] as? String,
              URL(fileURLWithPath: selectedPath).standardizedFileURL.resolvingSymlinksInPath().path == appPath else { return }
        let targetPath = appPath
        DispatchQueue.main.async {
            NSWorkspace.shared.openApplication(at: URL(fileURLWithPath: targetPath), configuration: NSWorkspace.OpenConfiguration()) { _, _ in }
        }
    }
}
let app = NSApplication.shared
let delegate = HostDelegate(appPath: appPath, configPath: configPath)
app.delegate = delegate
center.delegate = delegate
let action = UNNotificationAction(identifier: "OPEN_CONTEXT", title: "Открыть контекст", options: [])
center.setNotificationCategories([UNNotificationCategory(identifier: "RECOVERY", actions: [action], intentIdentifiers: [])])
let content = UNMutableNotificationContent()
content.title = "Можно вернуться к задаче"; content.body = body; content.categoryIdentifier = "RECOVERY"
let sem = DispatchSemaphore(value: 0)
final class ErrorBox: @unchecked Sendable { var error: Error? }
let box = ErrorBox()
center.add(UNNotificationRequest(identifier: notificationID, content: content, trigger: nil)) { @Sendable error in box.error = error; sem.signal() }
if sem.wait(timeout: .now() + 5) != .success || box.error != nil { close(lockFD); exit(4) }
flock(lockFD, LOCK_UN); close(lockFD)
// Remain available for a user action; absence of a click never opens the app.
DispatchQueue.main.asyncAfter(deadline: .now() + 24 * 3600) { NSApp.terminate(nil) }
app.run()

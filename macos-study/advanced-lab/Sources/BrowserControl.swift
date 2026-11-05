import Foundation
import AppKit

@MainActor
final class BrowserControl {
    private(set) var status = "Контроль вкладок выключен"
    private var deadline: TimeInterval = 0
    private var lastPoll: TimeInterval = 0
    func start() { deadline = ProcessInfo.processInfo.systemUptime + 1500 }
    func stop() { deadline = 0; status = "Контроль вкладок выключен" }
    func poll(policy: StudyPolicy, enabled: Bool, active: Bool) {
        guard enabled, active else { status = "Контроль вкладок выключен"; return }
        guard ProcessInfo.processInfo.systemUptime < deadline else { status = "Пробный контроль окончен · предел 25 мин"; return }
        let uptime = ProcessInfo.processInfo.systemUptime
        guard uptime - lastPoll >= 2 else { return }; lastPoll = uptime
        var available = 0, failed = 0
        for (bundle, safari) in [("com.apple.Safari", true), ("com.google.Chrome", false), ("com.operasoftware.OperaGX", false)] {
            guard NSRunningApplication.runningApplications(withBundleIdentifier: bundle).isEmpty == false else { continue }
            let script = Self.scanScript(bundle: bundle, safari: safari)
            var error: NSDictionary?
            guard let records = NSAppleScript(source: script)?.executeAndReturnError(&error), error == nil else { failed += 1; continue }
            available += 1
            for index in 1...max(1, records.numberOfItems) {
                guard let record = records.atIndex(index), record.numberOfItems == 3,
                      let url = record.atIndex(3)?.stringValue,
                      Self.blocks(url: url, policy: policy),
                      let window = record.atIndex(1)?.stringValue else { continue }
                let tab = record.atIndex(2)!.int32Value
                guard tab > 0, let windowID = Int(window) else { continue }
                let mutation = "tell application id \(Self.quote(bundle)) to tell tab \(tab) of window id \(windowID)\nif URL is \(Self.quote(url)) then set URL to \"about:blank\"\nend tell"
                var mutationError: NSDictionary?
                _ = NSAppleScript(source: mutation)?.executeAndReturnError(&mutationError)
                if mutationError != nil { failed += 1 }
            }
        }
        status = failed > 0 ? "Доступ к части вкладок отсутствует · защита неполная" : "Пробный контроль: \(available) браузера · защита неполная"
    }
    static func blocks(url: String, policy: StudyPolicy) -> Bool {
        guard let parsed = URL(string: url), ["http", "https"].contains(parsed.scheme?.lowercased() ?? ""),
              let host = parsed.host else { return false }
        return policy.shouldBlock(host: host, bundleID: nil) == true
    }
    static func quote(_ value: String) -> String { "\"" + value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"").replacingOccurrences(of: "\n", with: "\\n").replacingOccurrences(of: "\r", with: "\\r") + "\"" }
    static func scanScript(bundle: String, safari: Bool) -> String {
        // Browser dictionaries differ; only these candidates are attempted. No URLs
        // are persisted or sent to the receiver. Private-window coverage is unverified.
        """
        set results to {}
        tell application id \(quote(bundle))
            repeat with w in windows
                set positionIndex to 0
                repeat with t in tabs of w
                    set positionIndex to positionIndex + 1
                    try
                        set end of results to {(id of w) as text, positionIndex, (URL of t) as text}
                    end try
                end repeat
            end repeat
        end tell
        return results
        """
    }
}

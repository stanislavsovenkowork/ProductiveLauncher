import Foundation
@MainActor final class FocusCalls { var names: [String] = []; var output = "preserved_existing" }
@main @MainActor enum FocusBridgeTests {
    static func main() async throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let calls = FocusCalls()
        let bridge = FocusShortcutsBridge { name, _ in await MainActor.run { calls.names.append(name); return calls.output } }
        let id = UUID()
        _ = await bridge.enter(session: id, directory: root, isolated: true)
        _ = await bridge.enter(session: id, directory: root, isolated: false)
        precondition(calls.names.isEmpty)
        let config = FocusShortcutConfiguration(schemaVersion: 1, enterStudy: "FocusDeck Study Quiet", probe: "FocusDeck Focus Probe", verifiedOnThisMac: true)
        try JSONEncoder().encode(config).write(to: root.appendingPathComponent("focus-shortcuts.json"))
        let preserved = await bridge.enter(session: id, directory: root, isolated: false)
        precondition(preserved.outcome == .preservedExisting && preserved.until == nil)
        let until = Date().addingTimeInterval(60)
        calls.output = "applied_until|" + ISO8601DateFormatter().string(from: until)
        let requested = await bridge.enter(session: id, directory: root, isolated: false)
        precondition(requested.outcome == .requestedUntil && requested.until != nil)
        let before = calls.names.count; bridge.stopped(until: requested.until)
        precondition(calls.names.count == before) // Stop never calls off/restore
        calls.output = "applied_until|" + ISO8601DateFormatter().string(from: Date().addingTimeInterval(1900))
        let overlong = await bridge.enter(session: id, directory: root, isolated: false)
        precondition(overlong.outcome == .unverified)
        calls.output = "other"
        await bridge.observe(session: id, until: requested.until, priorOutcome: "requestedUntil", directory: root, isolated: false)
        precondition(bridge.status.contains("не меняет"))
        print("Safe Focus missing-setup/isolation/preserve/bounded-receipt/no-off-on-stop/changed-mode checks passed; fake runner only.")
    }
}

import Foundation
import Darwin

struct FocusShortcutConfiguration: Codable {
    let schemaVersion: Int
    let enterStudy: String
    let probe: String
    let verifiedOnThisMac: Bool
    func validate() throws {
        guard schemaVersion == 1, verifiedOnThisMac, !enterStudy.isEmpty, !probe.isEmpty, enterStudy != probe,
              [enterStudy, probe].allSatisfy({ $0.count <= 200 && !$0.contains("\0") && !$0.contains("\n") }) else { throw Failure.invalidConfiguration }
    }
    enum Failure: Error { case invalidConfiguration }
}
struct QuietReceipt: Equatable, Sendable {
    enum Outcome: String, Sendable { case missingSetup, isolated, preservedExisting, requestedUntil, unverified }
    let outcome: Outcome
    let until: Date?
}

@MainActor
final class FocusShortcutsBridge {
    typealias Runner = @Sendable (String, URL) async -> String?
    private let run: Runner
    private var busy = false
    private(set) var status = "Уведомления: настройка Focus ещё не связана"
    init(run: @escaping Runner = FocusShortcutsBridge.runShortcut) { self.run = run }
    func configuration(directory: URL) -> FocusShortcutConfiguration? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent("focus-shortcuts.json")),
              let configuration = try? JSONDecoder().decode(FocusShortcutConfiguration.self, from: data),
              (try? configuration.validate()) != nil else { return nil }
        return configuration
    }
    func enter(session: UUID, directory: URL, isolated: Bool, requestedSeconds: Int = 1800) async -> QuietReceipt {
        guard !isolated else { status = "Изолированная проверка: Focus не изменяется"; return QuietReceipt(outcome: .isolated, until: nil) }
        guard !busy, let configuration = configuration(directory: directory) else {
            status = "Уведомления: настройка Focus ещё не связана"; return QuietReceipt(outcome: .missingSetup, until: nil)
        }
        busy = true; defer { busy = false }
        do {
            let input = try makeInput(session: session, directory: directory, seconds: min(1800, max(1, requestedSeconds)))
            guard let text = await run(configuration.enterStudy, input) else { throw Failure.unknownResult }
            if text == "preserved_existing" {
                status = "Текущий Focus сохранён; его тишина не измерена"
                return QuietReceipt(outcome: .preservedExisting, until: nil)
            }
            let fields = text.split(separator: "|", maxSplits: 1)
            guard fields.count == 2, fields[0] == "applied_until", let until = Self.date(String(fields[1])),
                  until > Date(), until.timeIntervalSinceNow <= 1800 else { throw Failure.unknownResult }
            status = "Focus учёбы запрошен до \(until.formatted(date: .omitted, time: .shortened)); проверяется текущий режим"
            return QuietReceipt(outcome: .requestedUntil, until: until)
        } catch {
            status = "Запрос Focus не подтверждён; проверьте Пункт управления"
            return QuietReceipt(outcome: .unverified, until: nil)
        }
    }
    func observe(session: UUID?, until: Date?, priorOutcome: String?, directory: URL, isolated: Bool) async {
        guard !isolated, !busy, let configuration = configuration(directory: directory) else { return }
        busy = true; defer { busy = false }
        do {
            let input = try makeInput(session: session ?? UUID(), directory: directory, seconds: 1)
            guard let text = await run(configuration.probe, input) else { throw Failure.unknownResult }
            switch text {
            case "none": status = "Системный Focus сейчас не активен; приложение его само не повторяет"
            case "other": status = "Текущий Focus сохранён; приложение его не меняет"
            case "dedicated":
                if priorOutcome == QuietReceipt.Outcome.requestedUntil.rawValue, let until, until > Date() {
                    status = "Focus учёбы наблюдается; первоначально запрошен до \(until.formatted(date: .omitted, time: .shortened))"
                } else { status = "Focus учёбы наблюдается; его происхождение и срок не подтверждены" }
            default: throw Failure.unknownResult
            }
        } catch { status = "Текущий Focus не удалось проверить; автоматического переключения нет" }
    }
    // Stop deliberately performs no 'Focus off' or restore operation: a manual
    // re-selection of the same mode cannot be distinguished through public APIs.
    func stopped(until: Date?) {
        if let until, until > Date() { status = "Учёт остановлен. Focus запрошен до \(until.formatted(date: .omitted, time: .shortened)); изменить можно в Пункте управления" }
    }
    private func makeInput(session: UUID, directory: URL, seconds: Int) throws -> URL {
        let folder = directory.appendingPathComponent("focus-requests", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let input = folder.appendingPathComponent(UUID().uuidString + ".json")
        let context: [String: Any] = ["schemaVersion": 1, "sessionId": session.uuidString, "requestedSeconds": seconds]
        try JSONSerialization.data(withJSONObject: context).write(to: input, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: input.path)
        return input
    }
    static func date(_ text: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        if let date = formatter.date(from: text) { return date }
        formatter.formatOptions.insert(.withFractionalSeconds); return formatter.date(from: text)
    }
    nonisolated static func runShortcut(_ name: String, input: URL) async -> String? {
        await Task.detached {
            defer { try? FileManager.default.removeItem(at: input) }
            let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            process.arguments = ["run", name, "--input-path", input.path]
            let output = Pipe(); process.standardOutput = output; process.standardError = FileHandle.nullDevice
            do {
                try process.run()
                let deadline = ProcessInfo.processInfo.systemUptime + 30
                while process.isRunning && ProcessInfo.processInfo.systemUptime < deadline { try? await Task.sleep(for: .milliseconds(100)) }
                if process.isRunning {
                    process.terminate(); try? await Task.sleep(for: .milliseconds(200))
                    if process.isRunning { _ = kill(process.processIdentifier, SIGKILL) }
                    process.waitUntilExit(); return nil
                }
                process.waitUntilExit()
                guard process.terminationStatus == 0 else { return nil }
                let data = output.fileHandleForReading.readDataToEndOfFile()
                guard data.count <= 4096 else { return nil }
                return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            } catch { return nil }
        }.value
    }
    enum Failure: Error { case unknownResult }
}

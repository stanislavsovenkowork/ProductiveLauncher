import Foundation
import CryptoKit

struct RecoveryControl {
    struct Result: Sendable { let success: Bool; let message: String }
    static func setEnabled(_ enabled: Bool, isolated: Bool, fixtureRoot: URL? = nil) async -> Result {
        guard !isolated || fixtureRoot != nil else { return Result(success: false, message: "В тестовой среде управление восстановлением отключено.") }
        return await Task.detached {
            let root = fixtureRoot ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckHarness")
            let config = root.appendingPathComponent("review-config.json")
            let script = root.appendingPathComponent("runtime/recovery.py")
            let plistURL = root.appendingPathComponent("local-review.plist")
            guard let plistData = try? Data(contentsOf: plistURL),
                  let plist = (try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil)) as? [String: Any],
                  let args = plist["ProgramArguments"] as? [String], let python = args.first, python.hasPrefix("/"),
                  FileManager.default.isExecutableFile(atPath: python), FileManager.default.fileExists(atPath: config.path), FileManager.default.fileExists(atPath: script.path) else {
                return Result(success: false, message: "Средство локального восстановления недоступно; настройка не изменена.")
            }
            guard let configData = try? Data(contentsOf: config),
                  let configuration = (try? JSONSerialization.jsonObject(with: configData)) as? [String: Any],
                  let runtimePath = configuration["runtimeDirectory"] as? String,
                  URL(fileURLWithPath: runtimePath).standardizedFileURL == root.appendingPathComponent("runtime").standardizedFileURL,
                  let outputPath = configuration["outputDirectory"] as? String,
                  URL(fileURLWithPath: outputPath).standardizedFileURL == root.appendingPathComponent("reports").standardizedFileURL,
                  let hashes = configuration["runtimeHashes"] as? [String: String], !hashes.isEmpty else {
                return Result(success: false, message: "Не удалось проверить локальную конфигурацию; настройка не изменена.")
            }
            for (name, expected) in hashes {
                guard !name.hasPrefix("/"), !name.split(separator: "/").contains("..") else { return Result(success: false, message: "Небезопасный состав локального средства; настройка не изменена.") }
                let path = root.appendingPathComponent("runtime").appendingPathComponent(name)
                guard let data = try? Data(contentsOf: path), SHA256.hash(data: data).map({ String(format: "%02x", $0) }).joined() == expected.lowercased() else {
                    return Result(success: false, message: "Локальное средство изменилось; настройка не изменена.")
                }
            }
            var arguments = [script.path, enabled ? "enable" : "disable", "--config", config.path]
            if enabled {
                let controlPath = root.appendingPathComponent("reports/recovery-control.json")
                guard let data = try? Data(contentsOf: controlPath),
                      let saved = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                      let index = saved["sourceIndex"] as? Int, index >= 0,
                      let hours = saved["staleAfterHours"] as? NSNumber, (1...720).contains(hours.doubleValue) else {
                    return Result(success: false, message: "Ранее выбранный источник не найден; повторное включение недоступно.")
                }
                arguments += ["--source-index", String(index), "--stale-hours", hours.stringValue]
            }
            let process = Process(); process.executableURL = URL(fileURLWithPath: python); process.arguments = arguments
            let output = Pipe(); process.standardOutput = output; process.standardError = FileHandle.nullDevice
            do {
                try process.run(); let data = output.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
                guard process.terminationStatus == 0, let value = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], value["enabled"] as? Bool == enabled else {
                    return Result(success: false, message: "Не удалось подтвердить изменение. История сохранена.")
                }
                return Result(success: true, message: enabled ? "Локальная проверка включена для ранее выбранного источника." : "Проверка остановлена. История сохранена; внешние сообщения не отправляются.")
            } catch { return Result(success: false, message: "Не удалось изменить настройку. История сохранена.") }
        }.value
    }
}

struct ReminderControl {
    struct Result: Sendable { let success: Bool; let enabled: Bool?; let permission: String; let message: String }
    static func run(_ action: String, root: URL, host: URL, context: URL, app: URL) async -> Result {
        await Task.detached {
            let failure = Result(success: false, enabled: nil, permission: "unknown", message: "Напоминания недоступны; настройка и разрешение неизвестны.")
            guard ["configure", "status", "enable", "stop", "request-permission"].contains(action) else { return failure }
            let config = root.appendingPathComponent("review-config.json"), runtime = root.appendingPathComponent("runtime")
            let script = runtime.appendingPathComponent("recovery_reminder.py")
            let plistURL = root.appendingPathComponent("local-review.plist")
            guard let plistData = try? Data(contentsOf: plistURL),
                  let plist = (try? PropertyListSerialization.propertyList(from: plistData, options: [], format: nil)) as? [String: Any],
                  let arguments = plist["ProgramArguments"] as? [String], let python = arguments.first, python.hasPrefix("/"),
                  FileManager.default.isExecutableFile(atPath: python), (action == "stop" || action == "status" || FileManager.default.isExecutableFile(atPath: host.path)),
                  let data = try? Data(contentsOf: config), let settings = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let directory = settings["runtimeDirectory"] as? String,
                  URL(fileURLWithPath: directory).standardizedFileURL == runtime.standardizedFileURL,
                  let output = settings["outputDirectory"] as? String,
                  URL(fileURLWithPath: output).standardizedFileURL == root.appendingPathComponent("reports").standardizedFileURL,
                  let hashes = settings["runtimeHashes"] as? [String: String], let expected = hashes["recovery_reminder.py"],
                  let scriptData = try? Data(contentsOf: script), SHA256.hash(data: scriptData).map({ String(format: "%02x", $0) }).joined() == expected.lowercased() else { return failure }
            var safeRemoval = true
            if let existing = settings["recoveryReminder"] as? [String: Any] {
                let matches = existing["hostExecutable"] as? String == host.path &&
                    existing["contextPath"] as? String == context.path && existing["openAppPath"] as? String == app.path
                if !matches && action != "stop" { return failure }
                safeRemoval = matches
            } else if action != "configure" { return failure }
            let process = Process(), pipe = Pipe()
            if action == "request-permission" { process.executableURL = host; process.arguments = ["request-permission"] }
            else {
                process.executableURL = URL(fileURLWithPath: python)
                var args = [script.path, action, "--config", config.path]
                if action == "configure" { args += ["--host", host.path, "--context", context.path, "--app", app.path] }
                if action == "stop" && !safeRemoval { args.append("--no-host") }
                process.arguments = args
            }
            process.standardOutput = pipe; process.standardError = FileHandle.nullDevice
            do {
                try process.run(); let resultData = pipe.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
                guard process.terminationStatus == 0 else { return failure }
                if action == "request-permission" {
                    let permission = String(decoding: resultData, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                    return Result(success: true, enabled: nil, permission: permission, message: "Разрешение: \(permission).")
                }
                guard let object = (try? JSONSerialization.jsonObject(with: resultData)) as? [String: Any] else { return failure }
                let enabled = object["enabled"] as? Bool
                let permission = object["permission"] as? String ?? "unknown"
                let message = action == "stop" && (object["removal"] as? String) != "requested" ? "Отключено; удаление показанного уведомления неизвестно." : "Настройка сохранена; показ уведомления не подтверждён."
                return Result(success: true, enabled: enabled, permission: permission, message: message)
            } catch { return failure }
        }.value
    }
}

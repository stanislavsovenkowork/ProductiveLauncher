import Foundation

struct LocalCollectionControl {
    struct Result: Sendable { let complete: Bool; let message: String }
    static func perform(start: Bool, isolated: Bool) async -> Result {
        guard !isolated else { return Result(complete: false, message: "Изолированная проверка: управление системными службами отключено") }
        return await Task.detached {
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            let script = support.appendingPathComponent("FocusDeckHarness/control-runtime/" + (start ? "start-local.command" : "all-stop.command"))
            guard FileManager.default.fileExists(atPath: script.path) else { return Result(complete: false, message: "Средство управления не установлено; состояние служб не изменено") }
            let process = Process(); process.executableURL = URL(fileURLWithPath: "/bin/sh"); process.arguments = [script.path]
            let output = Pipe(); process.standardOutput = output; process.standardError = FileHandle.nullDevice
            do {
                try process.run()
                let data = output.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                guard process.terminationStatus == 0, let result = try JSONSerialization.jsonObject(with: data) as? [String: Any], result["complete"] as? Bool == true else {
                    return Result(complete: false, message: "Часть служб не подтвердила изменение. История сохранена; проверьте локальный отчёт.")
                }
                return Result(complete: true, message: start ? "Независимый локальный сбор включён по вашему выбору" : "Независимые обзор, приём и контекст остановлены. История сохранена; таймер приложения работает отдельно.")
            } catch { return Result(complete: false, message: "Не удалось изменить состояние служб. История сохранена.") }
        }.value
    }
}

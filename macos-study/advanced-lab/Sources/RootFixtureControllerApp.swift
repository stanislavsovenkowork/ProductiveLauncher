import SwiftUI
import AppKit

@MainActor
final class RootFixtureControllerModel: ObservableObject {
    @Published var busy = false
    @Published var message = "Ничего не установлено этой программой. Сначала проверьте назначение теста."
    @Published var uiRunning = false
    @Published var hasUILog = false
    private var uiProcess: Process?
    private var uiLog: URL?
    private var package: URL { Bundle.main.resourceURL!.appendingPathComponent("fixture-package", isDirectory: true) }

    func admin(_ action: String) {
        guard !busy, ["install", "run", "stop", "audit", "remove"].contains(action) else { return }
        busy = true; message = "Ожидается действие macOS. Пароль вводится только пользователем в системном окне."
        let script = package.appendingPathComponent("root-fixture-authorization.sh").path
        Task {
            let result = await Task.detached { () -> String in
                let process = Process(); process.executableURL = URL(fileURLWithPath: "/bin/sh"); process.arguments = [script, action]
                let pipe = Pipe(); process.standardOutput = pipe; process.standardError = pipe
                do {
                    try process.run(); let data = pipe.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
                    let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    return process.terminationStatus == 0 ? (text.isEmpty ? "Действие выполнено; проверьте состояние." : text) : "Не подтверждено: \(text.isEmpty ? "действие отменено или проверка не пройдена" : text)"
                } catch { return "Не удалось запустить системное разрешение: \(error.localizedDescription)" }
            }.value
            message = result; busy = false
        }
    }
    func openUI() {
        guard !uiRunning else { return }
        let script = package.appendingPathComponent("run-v2-root-ui-fixture.sh")
        let log = FileManager.default.temporaryDirectory.appendingPathComponent("focusdeck-fixture-ui-" + UUID().uuidString + ".log")
        do {
            FileManager.default.createFile(atPath: log.path, contents: nil, attributes: [.posixPermissions: 0o600])
            let output = try FileHandle(forWritingTo: log)
            let p = Process(); p.executableURL = URL(fileURLWithPath: "/bin/sh"); p.arguments = [script.path]
            p.standardOutput = output; p.standardError = output; try p.run()
            uiProcess = p; uiLog = log; uiRunning = true; hasUILog = true
            message = "Тестовое окно запускается отдельно. Оно использует только synthetic цель и временные данные."
            Task { [weak self] in
                await Task.detached { p.waitUntilExit() }.value
                try? output.close(); self?.uiProcess = nil; self?.uiRunning = false
                self?.message = p.terminationStatus == 0 ? "Тестовое окно закрыто. Локальный журнал сохранён." : "Тестовое окно не подтверждено; откройте локальный журнал и проверьте причину."
            }
        } catch { message = "Тестовое окно не запущено: \(error.localizedDescription)" }
    }
    func closeUI() {
        guard let p = uiProcess, p.isRunning else { return }
        p.interrupt(); message = "Запрошено закрытие только тестового окна."
    }
    func showUILog() { if let uiLog { NSWorkspace.shared.activateFileViewerSelecting([uiLog]) } }
    func testChild() {
        guard !busy else { return }; busy = true
        let script = package.appendingPathComponent("run-v2-root-fixture-process.sh").path
        Task {
            let result = await Task.detached { () -> String in
                let p = Process(); p.executableURL = URL(fileURLWithPath: "/bin/sh"); p.arguments = [script]
                let pipe = Pipe(); p.standardOutput = pipe; p.standardError = pipe
                do {
                    try p.run(); let data = pipe.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
                    let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    return p.terminationStatus == 0 ? text : "Проба не подтверждена: \(text)"
                } catch { return "Проба не запущена: \(error.localizedDescription)" }
            }.value
            message = result; busy = false
        }
    }
}

@main struct RootFixtureControllerApp: App {
    @StateObject var model = RootFixtureControllerModel()
    var body: some Scene {
        WindowGroup("FocusDeck · синтетическая root-проверка") {
            VStack(alignment: .leading, spacing: 16) {
                Text("Только синтетическая root-проверка").font(.title2.bold())
                Text("Цель: dev.stanislav.focusdeck.guard-fixture · отдельный подписанный тестовый процесс. Личные приложения и сеть не затрагиваются. Окно до 150 секунд; это не проверка календарной защиты или сайтов.").font(.callout)
                Text("Пароль вводит только пользователь в системном диалоге macOS. При отказе, неизвестном результате или занятом устройстве остановитесь.").font(.caption).foregroundStyle(.secondary)
                HStack {
                    Button("1 · Установить") { model.admin("install") }
                    Button("2 · Запустить") { model.admin("run") }
                    Button("3 · Открыть тестовое окно") { model.openUI() }.disabled(model.uiRunning)
                }.disabled(model.busy)
                HStack {
                    Button("Проверить свой процесс") { model.testChild() }
                    Button("События помощника") { model.admin("audit") }
                    Button("Журнал окна") { model.showUILog() }.disabled(!model.hasUILog)
                    Button("Закрыть тестовое окно") { model.closeUI() }.disabled(!model.uiRunning)
                }.disabled(model.busy)
                Divider()
                HStack {
                    Button("4 · Остановить помощника") { model.admin("stop") }
                    Button("5 · Удалить помощника") { model.admin("remove") }
                }.disabled(model.busy || model.uiRunning)
                Text(model.message).font(.callout).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                Spacer()
                Text("Установка и удаление не используют production helper. Неподтверждённый результат не означает успешную блокировку.").font(.caption).foregroundStyle(.secondary)
            }.padding(26).frame(minWidth: 760, minHeight: 420)
        }
    }
}

import SwiftUI
import Foundation
import Darwin

@MainActor
final class RootFixtureModel: ObservableObject {
    @Published var message = "Помощник не установлен. Ни одно личное приложение не является целью."
    @Published var busy = false
    @Published var acknowledged = false
    let guardService = PrivilegedAppGuard()
    let target = "dev.stanislav.focusdeck.guard-fixture"
    var sessionID: UUID?
    func install() async {
        guard acknowledged, !busy else { return }; busy = true; defer { busy = false }
        message = "Ожидается системное разрешение macOS. Установка не активирует ограничений."
        message = await guardService.administratorAction("--install-idle", isolated: false)
    }
    func remove() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        message = await guardService.administratorAction("--remove", isolated: false)
    }
    func runFixture() async {
        guard acknowledged, !busy, let executable = Bundle.main.url(forResource: "GuardFixture", withExtension: nil) else { return }
        busy = true; defer { busy = false }
        let id = UUID(); sessionID = id
        let start = RootRequest(schemaVersion: 1, command: "start", sessionId: id, seconds: 3, hostsEnabled: false)
        do {
            let reply = try await Task.detached { try RootSocketClient.send(start) }.value
            guard reply.accepted else { message = "Тест не начат: \(reply.reason)"; return }
            let pause = RootRequest(schemaVersion: 1, command: "pause", sessionId: id, seconds: 0.5, hostsEnabled: nil)
            let paused = try await Task.detached { try RootSocketClient.send(pause) }.value
            let healthyPaused = paused.verifiedPeerPID != nil && paused.policyState == "paused" && paused.policySessionId == id
            guard paused.accepted, healthyPaused else { message = "Пауза теста не подтверждена"; return }
            let child = Process(); child.executableURL = executable
            let output = Pipe(); child.standardOutput = output; child.standardError = FileHandle.nullDevice
            try child.run()
            let ready = await Task.detached { output.fileHandleForReading.readData(ofLength: 6) }.value
            guard ready == Data("ready\n".utf8), let identity = ProcessIdentity.inspect(child.processIdentifier),
                  identity.parent == UInt32(getpid()), identity.signingID == target else {
                if child.isRunning { child.terminate() }; message = "Идентичность своего тестового процесса не подтверждена"; return
            }
            try await Task.sleep(for: .milliseconds(100))
            let survivedPause = child.isRunning
            let repeated = try await Task.detached { try RootSocketClient.send(pause) }.value
            let limit = ProcessInfo.processInfo.systemUptime + 3
            while child.isRunning && ProcessInfo.processInfo.systemUptime < limit { try await Task.sleep(for: .milliseconds(50)) }
            guard !child.isRunning else {
                child.terminate(); message = "Тест НЕ пройден: root-процесс не завершил собственную fixture; личные приложения не затронуты"; return
            }
            child.waitUntilExit()
            var observedRoot = false
            for _ in 0..<8 {
                await guardService.observe(expectedSession: id, expectedTargets: [target], expectedDomains: ["focusdeck-fixture.test"], isolated: false)
                if guardService.observedAppsActive { observedRoot = true; break }
                try await Task.sleep(for: .milliseconds(100))
            }
            let term = child.terminationReason == .uncaughtSignal && child.terminationStatus == SIGTERM
            guard survivedPause, !repeated.accepted, term, observedRoot, !guardService.observedHostsActive else {
                message = "Тест не подтверждён полностью: пауза=\(survivedPause), повтор отклонён=\(!repeated.accepted), TERM=\(term), root-статус=\(observedRoot): \(guardService.status). Домены не включались."; return
            }
            let expiry = paused.expiresAt ?? Date()
            while Date() <= expiry.addingTimeInterval(1.3) { try await Task.sleep(for: .milliseconds(100)) }
            await guardService.observe(expectedSession: id, expectedTargets: [target], expectedDomains: ["focusdeck-fixture.test"], isolated: false)
            guard guardService.observedDaemonHealthy, !guardService.observedAppsActive, ["expired", "idle"].contains(guardService.observedPolicyState) else { message = "Истечение тестовой политики при доступном root-помощнике ещё не подтверждено"; return }
            message = "ПРОЙДЕНО на этой машине: собственная fixture пережила паузу 0,5 с, затем завершена SIGTERM; подтверждён root-помощник; повтор паузы отклонён, политика истекла. Личные приложения и DNS не проверялись/не блокировались. Помощник остаётся бездействующим после закрытия окна; теперь удалите его кнопкой 3."
        } catch { message = "Тест не подтверждён: \(error.localizedDescription). Личные приложения не являются целями; тестовая политика ограничена 3,5 с." }
    }
}

@main
struct RootFixtureApp: App {
    @StateObject var model = RootFixtureModel()
    var body: some Scene {
        WindowGroup("FocusDeck · безопасная root-проба") {
            VStack(alignment: .leading, spacing: 20) {
                Text("Проверка root-помощника").font(.largeTitle)
                Text("Единственная цель: dev.stanislav.focusdeck.guard-fixture. Контроллер имеет другой ID. Личные приложения не являются целями; личный контекст и receiver не используются. Изменение hosts ВЫКЛЮЧЕНО.").textSelection(.enabled)
                Text("1. Установите бездействующего помощника через системное окно macOS.\n2. Запустите короткую пробу: свой процесс, 3 с + одна пауза 0,5 с.\n3. Удалите тестового помощника. Обычная FocusDeck.app потребует отдельной привязки после успешной пробы.").foregroundStyle(.secondary)
                Toggle("Проверил единственную тестовую цель; разрешаю эту изолированную пробу", isOn: $model.acknowledged)
                HStack {
                    Button("1 · Установить БЕЗ блокировки…") { Task { await model.install() } }
                    Button("2 · Проверить свой процесс") { Task { await model.runFixture() } }
                    Button("3 · Восстановить и удалить…") { Task { await model.remove() } }
                }.disabled(!model.acknowledged || model.busy)
                Divider()
                Text(model.message).font(.system(size: 14)).textSelection(.enabled)
                Spacer()
                Text("Пароль — только в отдельном системном окне macOS. Эта проба не доказывает блокировку сайтов, уведомлений или совместимость личных приложений.").font(.caption).foregroundStyle(.secondary)
            }.padding(28).frame(width: 760, height: 480)
        }
    }
}

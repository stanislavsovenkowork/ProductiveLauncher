import SwiftUI
import ServiceManagement

@MainActor struct SystemFixtureService: FixtureServiceManaging {
    private var service: SMAppService { .daemon(plistName: "dev.stanislav.focusdeck.smfixture.plist") }
    var state: String {
        switch service.status {
        case .notRegistered: return "Не зарегистрирована"
        case .enabled: return "Зарегистрирована; работа процесса не подтверждена"
        case .requiresApproval: return "Ожидает системного подтверждения"
        case .notFound: return "Служба не найдена"
        @unknown default: return "Неизвестно"
        }
    }
    func register() throws { try service.register() }
    func unregister() throws { try service.unregister() }
}

@main struct SMFixtureCandidateApp: App {
    @StateObject private var model = FixtureServiceModel(service: SystemFixtureService())
    @State private var fixtureResult = "Тест процесса ещё не выполнялся."
    @State private var socketState = "Проверка сокета ещё не проводилась."
    var body: some Scene {
        WindowGroup("FocusDeck — тест службы") {
            VStack(alignment: .leading, spacing: 14) {
                Text("Только тестовая служба").font(.title2)
                Text("Регистрация создаёт системную службу, которая может запускаться после перезагрузки. Она остаётся зарегистрированной после закрытия приложения. Удалите её кнопкой и проверьте состояние.")
                Text("Единственная цель — встроенный синтетический процесс. Защита личных приложений и сайтов отсутствует.").bold()
                Text(fixtureResult).font(.caption)
                Text(model.state).textSelection(.enabled)
                Text(model.message).font(.caption)
                Text(socketState).font(.caption)
                HStack {
                    Button("Зарегистрировать") { model.register() }
                    Button("Удалить службу") { model.unregister() }
                    Button("Тест процесса") { do { fixtureResult = try FixtureClient.run() } catch { fixtureResult = "Тест не подтверждён: \(error.localizedDescription)" } }
                    Button("Обновить") {
                        model.refresh()
                        socketState = FileManager.default.fileExists(atPath: "/var/run/dev.stanislav.focusdeck.smfixture.sock")
                            ? "Тестовый сокет существует; завершение службы не подтверждено."
                            : "Тестовый сокет отсутствует; отсутствие процесса требует отдельной проверки."
                    }
                    Button("Системные настройки") { SMAppService.openSystemSettingsLoginItems() }
                }
            }.padding().frame(width: 620)
        }
    }
}

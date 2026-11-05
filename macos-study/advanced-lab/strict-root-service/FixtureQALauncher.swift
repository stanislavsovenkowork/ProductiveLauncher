import SwiftUI

@main struct FixtureQALauncherApp: App {
    @State private var state = "Проверка не запущена."
    @State private var busy = false
    var body: some Scene {
        WindowGroup("FocusDeck · тест системного помощника") {
            VStack(alignment: .leading, spacing: 14) {
                Text("Проверка тестового процесса").font(.title2)
                Text("Однократно создаются отдельные тестовые файлы и запускается root-помощник с пределом в две минуты. Ограничивается только собственный подписанный тестовый процесс. После проверки выполняется удаление тестовых файлов; при сбое его результат может быть неизвестен. Личные приложения и сеть не изменяются.")
                Button("Запустить ограниченную проверку") {
                    busy = true; state = "Ожидается системное разрешение и проверка…"
                    Task {
                        let result = await Task.detached { focusdeck_fixture_qa() }.value
                        state = result == 0 ? "Проверка и удаление тестовых файлов подтверждены." : result == 1 ? "Разрешение отменено." : "Результат или удаление неизвестны; требуется проверка тестовых файлов."
                        busy = false
                    }
                }.disabled(busy)
                Text(state).textSelection(.enabled)
            }.padding(24).frame(width: 580, height: 260)
        }.windowResizability(.contentSize)
    }
}

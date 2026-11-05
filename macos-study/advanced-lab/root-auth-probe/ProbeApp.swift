import SwiftUI

@main struct FocusDeckRootProbe: App {
    @State private var state = "Проверка ещё не начата."
    @State private var busy = false
    var body: some Scene {
        WindowGroup("FocusDeck · проверка прав") {
            VStack(alignment: .leading, spacing: 16) {
                Text("Проверка системных прав").font(.title2)
                Text("Проверка запускает только системную команду определения пользователя. Она не устанавливает службу и не меняет файлы или сеть. Если macOS запросит пароль, введите его самостоятельно в системном окне.")
                Button("Проверить системные права") {
                    busy = true; state = "Ожидаем системное разрешение…"
                    Task {
                        let result = await Task.detached { focusdeck_root_probe() }.value
                        state = result == 0 ? "Системная команда подтвердила root." : result == 1 ? "Запрос отменён; права не подтверждены." : "Проверка не подтвердила root."
                        busy = false
                    }
                }.disabled(busy)
                Text(state).textSelection(.enabled)
            }.padding(24).frame(width: 540, height: 240)
        }
        .windowResizability(.contentSize)
    }
}

import SwiftUI

@main struct FocusDeckPFProbe: App {
    @State private var text = "Проверка ещё не начата."
    @State private var busy = false
    var body: some Scene {
        WindowGroup("FocusDeck · состояние PF") {
            VStack(alignment: .leading, spacing: 14) {
                Text("Проверка состояния сетевого фильтра").font(.title2)
                Text("macOS запросит системные права для чтения состояния и сводных счётчиков PF, а также наличия безусловного якоря com.apple/*. Проверка не меняет правила, соединения или сеть. Содержимое правил и адреса соединений не сохраняются и не выводятся.")
                Button("Прочитать состояние PF") {
                    busy = true; text = "Ожидаем системное разрешение…"
                    Task {
                        let result = await Task.detached { () -> (Int32, Int32, Int32, Int32, Int32, Int32, Int32) in
                            var status: Int32 = -1, count: Int32 = -1, rules: Int32 = -1, nat: Int32 = -1, anchors: Int32 = -1, hook: Int32 = -1
                            let result = focusdeck_pf_readonly(&status, &count, &rules, &nat, &anchors, &hook); return (result, status, count, rules, nat, anchors, hook)
                        }.value
                        func lines(_ n: Int32) -> String { n >= 0 ? String(n) : "неизвестно" }
                        let hook = result.6 == 1 ? "есть" : result.6 == 0 ? "не обнаружен" : "неизвестно"
                        text = result.0 == 0 ? "PF: \(result.1 == 1 ? "включён" : "выключен"); состояний: \(result.2). Строки правил: \(lines(result.3)); NAT: \(lines(result.4)); anchors: \(lines(result.5)); безусловный com.apple/*: \(hook). Пустой вывод не подтверждает успешную проверку." : result.0 == 1 ? "Запрос отменён; состояние неизвестно." : "Не удалось проверить PF; состояние неизвестно."
                        busy = false
                    }
                }.disabled(busy)
                Text(text).textSelection(.enabled)
            }.padding(24).frame(width: 560, height: 260)
        }
        .windowResizability(.contentSize)
    }
}

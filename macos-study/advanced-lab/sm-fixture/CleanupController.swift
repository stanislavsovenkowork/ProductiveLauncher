import SwiftUI
import ServiceManagement
@main struct CleanupController: App {
 @State private var state = "Ещё не проверено"
 private let label = "dev.stanislav.focusdeck.calendarfixture.cleanup.plist"
 var body: some Scene {
  WindowGroup("FocusDeck · очистка теста") {
   VStack(alignment: .leading, spacing: 12) {
    Text("Очистка только календарного fixture").font(.title2)
    Text("Сначала отключите исходную тестовую службу. Помощник удаляет только её известные файлы после окончания тестового окна. После этого обязательно отключите и эту службу.")
    Text(state)
    HStack {
     Button("Включить очистку") { do { try SMAppService.daemon(plistName: label).register(); state = "Запрос отправлен системе; проверьте подтверждение." } catch { state = "Включение не подтверждено: \(error.localizedDescription)" } }
     Button("Обновить") {
      let service = SMAppService.daemon(plistName: label)
      let exists = FileManager.default.fileExists(atPath: "/Library/Application Support/FocusDeckCalendarFixture")
      state = "Служба: \(String(describing: service.status)); тестовая папка: \(exists ? "существует" : "отсутствует")"
     }
     Button("Отключить очистку") { do { try SMAppService.daemon(plistName: label).unregister(); state = "Запрос отключения отправлен; проверьте состояние." } catch { state = "Отключение не подтверждено: \(error.localizedDescription)" } }
     Button("Системные настройки") { SMAppService.openSystemSettingsLoginItems() }
    }
   }.padding().frame(width: 650)
  }
 }
}

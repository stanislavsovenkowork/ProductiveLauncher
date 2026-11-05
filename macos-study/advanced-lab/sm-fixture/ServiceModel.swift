import Foundation

@MainActor protocol FixtureServiceManaging {
    var state: String { get }
    func register() throws
    func unregister() throws
}

@MainActor final class FixtureServiceModel: ObservableObject {
    @Published private(set) var state: String
    @Published private(set) var message = "Служба не регистрируется автоматически."
    private let service: any FixtureServiceManaging
    init(service: any FixtureServiceManaging) { self.service = service; state = service.state }
    func refresh() { state = service.state }
    func register() {
        do { try service.register(); message = "Запрос отправлен системе. Проверьте состояние и системное подтверждение." }
        catch { message = "Регистрация не подтверждена: \(error.localizedDescription)" }
        refresh()
    }
    func unregister() {
        do { try service.unregister(); message = "Запрос удаления отправлен. Проверьте состояние; завершение процесса ещё нужно подтвердить." }
        catch { message = "Удаление не подтверждено: \(error.localizedDescription)" }
        refresh()
    }
}

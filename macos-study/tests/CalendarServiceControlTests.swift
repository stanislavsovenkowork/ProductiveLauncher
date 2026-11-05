import Foundation
@MainActor final class FakeCalendarService: CalendarServiceManaging {
    var state: CalendarServiceState = .absent
    var requests: [String] = []
    var fail = false
    func register() throws { requests.append("register"); if fail { throw CocoaError(.fileWriteNoPermission) }; state = .approvalRequired }
    func unregister() throws { requests.append("unregister"); if fail { throw CocoaError(.fileWriteNoPermission) }; state = .absent }
}
@main enum CalendarServiceControlTests {
    @MainActor static func main() {
        let service = FakeCalendarService(), model = CalendarServiceControl(service: service)
        precondition(service.requests.isEmpty && model.state == .absent)
        model.register(); precondition(model.state == .approvalRequired && service.requests == ["register"])
        service.state = .enabled; model.refresh(); precondition(model.state == .enabled)
        model.unregister(); precondition(model.state == .absent && service.requests == ["register", "unregister"])
        service.fail = true; model.register(); precondition(model.state == .absent && model.message.contains("не подтверждено"))
        model.unregister(); precondition(model.state == .absent && model.message.contains("не подтверждено"))
        print("Calendar service-control fake: opt-in, approval, status, explicit removal and errors passed. No SM service registered.")
    }
}

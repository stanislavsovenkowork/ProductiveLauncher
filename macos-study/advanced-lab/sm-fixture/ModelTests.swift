import Foundation

@MainActor final class FakeService: FixtureServiceManaging {
    var state = "idle"
    var calls: [String] = []
    var fail = false
    func register() throws { calls.append("register"); if fail { throw CocoaError(.fileWriteNoPermission) }; state = "approval" }
    func unregister() throws { calls.append("unregister"); if fail { throw CocoaError(.fileWriteNoPermission) }; state = "idle" }
}
@main enum ModelTests {
    @MainActor static func main() {
        let fake = FakeService(); let model = FixtureServiceModel(service: fake)
        precondition(fake.calls.isEmpty && model.state == "idle")
        model.register(); precondition(fake.calls == ["register"] && model.state == "approval")
        model.unregister(); precondition(fake.calls == ["register", "unregister"] && model.state == "idle")
        fake.fail = true; model.register(); precondition(model.state == "idle" && model.message.contains("не подтверждена"))
        model.unregister(); precondition(model.state == "idle" && model.message.contains("не подтверждено"))
        print("Fake service: explicit registration, approval, removal and error states pass; no system service touched.")
    }
}

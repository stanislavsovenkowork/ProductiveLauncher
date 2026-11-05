import Foundation

@MainActor final class ManualFakeSM: CalendarServiceManaging {
 var state: CalendarServiceState = .missing
 func register() throws { state = .enabled }
 func unregister() throws { state = .absent }
}

@MainActor final class FakeManager {
 var calls: [String] = [], phase = "absent", fail = false, changeSM = false, actionFailure: String?
 let sm: ManualFakeSM
 init(_ sm: ManualFakeSM) { self.sm = sm }
 func run(_ command: String) async -> ManualAgentReply? {
  calls.append(command)
  if fail { return nil }
  if command == "status" {
   if changeSM { sm.state = .enabled }
   return ManualAgentReply(ok: true, state: phase, reason: phase == "absent" ? "not_installed" : "connected")
  }
  if let reason = actionFailure { return ManualAgentReply(ok: false, state: "unknown", reason: reason) }
  switch command {
  case "install": phase = "ready"; return ManualAgentReply(ok: true, state: phase, reason: "installed")
  case "uninstall": phase = "absent"; return ManualAgentReply(ok: true, state: phase, reason: "uninstalled_ledger_retained")
  case "update": return ManualAgentReply(ok: true, state: "ready", reason: "updated")
  default: return nil
  }
 }
}

@main @MainActor enum ManualAgentControlTests {
 static func main() async {
  let sm = ManualFakeSM(), control = CalendarServiceControl(service: sm), fake = FakeManager(sm)
  var model = ManualAgentControl { command in await fake.run(command) }
  await model.perform("install", sm: control); precondition(fake.calls.isEmpty && model.state == "pending")
  await model.refresh(); precondition(model.state == "absent")
  await model.perform("install", sm: control)
  precondition(Array(fake.calls.suffix(3)) == ["status", "install", "status"] && model.state == "ready" && control.state == .missing)
  await model.perform("uninstall", sm: control)
  precondition(Array(fake.calls.suffix(3)) == ["status", "uninstall", "status"] && model.state == "absent")
  model = ManualAgentControl { command in await fake.run(command) } // fresh UI after OFF
  await model.refresh(); await model.perform("install", sm: control)
  precondition(Array(fake.calls.suffix(3)) == ["status", "install", "status"] && model.state == "ready")
  fake.phase = "active"; let beforeActive = fake.calls.filter { $0 == "uninstall" }.count
  await model.perform("uninstall", sm: control)
  precondition(model.state == "active" && fake.calls.filter { $0 == "uninstall" }.count == beforeActive)
  fake.phase = "ready"; await model.refresh(); fake.actionFailure = "service_conflict"
  await model.perform("uninstall", sm: control)
  precondition(model.state == "unknown" && model.message.contains("Обнаружена другая регистрация"))
  fake.phase = "absent"; fake.actionFailure = "clock_unknown"; await model.refresh()
  await model.perform("install", sm: control); precondition(model.state == "unknown" && model.message.contains("Не удалось надёжно проверить время"))
  fake.actionFailure = nil; fake.phase = "ready"; sm.state = .enabled; await model.refresh(); let beforeEnabled = fake.calls.count
  await model.perform("update", sm: control); precondition(fake.calls.count == beforeEnabled && model.state == "unknown")
  sm.state = .missing; await model.refresh(); fake.changeSM = true
  let beforeChanged = fake.calls.filter { $0 == "update" }.count
  await model.perform("update", sm: control); precondition(model.state == "unknown" && fake.calls.filter { $0 == "update" }.count == beforeChanged)
  fake.changeSM = false; sm.state = .unknown; await model.refresh(); let beforeUnknown = fake.calls.count
  await model.perform("uninstall", sm: control); precondition(fake.calls.count == beforeUnknown && model.state == "unknown")
  sm.state = .missing; await model.refresh(); fake.fail = true
  let beforeFailure = fake.calls.filter { $0 == "uninstall" }.count
  await model.perform("uninstall", sm: control); precondition(model.state == "unknown" && fake.calls.filter { $0 == "uninstall" }.count == beforeFailure)
  print("PASS: SM notFound cold install, OFF and UI restart/ON; active, conflicts and unknown fail closed")
 }
}

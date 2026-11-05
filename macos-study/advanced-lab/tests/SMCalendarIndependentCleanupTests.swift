import Foundation
import Darwin
@main enum SMCalendarIndependentCleanupTests {
 static func main() throws {
  let base = URL(fileURLWithPath: CommandLine.arguments[2], isDirectory: true), executable = CommandLine.arguments[1]
  func run(_ dir: URL) throws -> Int32 {
   let p = Process(); p.executableURL = URL(fileURLWithPath: executable); p.environment = ["FOCUSDECK_CLEANUP_FAKE_DIR": dir.path, "FOCUSDECK_CLEANUP_FAKE_SOCKET": base.appendingPathComponent("no.sock").path]; try p.run(); p.waitUntilExit(); return p.terminationStatus
  }
  func prepare(_ name: String, terminal: Bool, extra: Bool) throws -> URL {
   let dir = base.appendingPathComponent(name); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
   let now = Date(); let p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "2026-10-29", startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(60), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: terminal ? .exit : .active)
   let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601; let file = dir.appendingPathComponent("window.json"); try enc.encode(p).write(to: file); try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: file.path)
   if extra { try Data("foreign".utf8).write(to: dir.appendingPathComponent("other")) }
   return dir
  }
  let active = try prepare("active", terminal: false, extra: false), foreign = try prepare("foreign", terminal: true, extra: true), done = try prepare("done", terminal: true, extra: false)
  let a = try run(active), b = try run(foreign), c = try run(done)
  precondition(a != 0 && b != 0 && c == 0)
  precondition(FileManager.default.fileExists(atPath: active.path) && FileManager.default.fileExists(atPath: foreign.appendingPathComponent("other").path) && !FileManager.default.fileExists(atPath: done.path))
  print("Independent cleanup: active and unexpected data refused; terminal synthetic fixture alone removed, no root.")
 }
}

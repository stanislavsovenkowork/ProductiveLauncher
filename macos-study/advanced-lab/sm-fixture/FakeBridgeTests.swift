import Foundation
import Darwin
@main enum FakeBridgeTests {
 static func main() throws {
  let daemon = Process(); daemon.executableURL = URL(fileURLWithPath: CommandLine.arguments[1]); try daemon.run()
  defer { if daemon.isRunning { daemon.terminate(); daemon.waitUntilExit() } }
  let path = "/tmp/focusdeck-smfixture-fake-501.sock"
  for _ in 0..<100 { if FileManager.default.fileExists(atPath: path) { break }; usleep(10000) }
  let child = Process(); child.executableURL = URL(fileURLWithPath: CommandLine.arguments[2]); let pipe = Pipe(); child.standardOutput = pipe
  try child.run(); defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
  precondition(pipe.fileHandleForReading.readData(ofLength: 6) == Data("ready\n".utf8))
  let fd = socket(AF_UNIX, SOCK_STREAM, 0); precondition(fd >= 0)
  var a = sockaddr_un(); a.sun_family = sa_family_t(AF_UNIX); a.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
  withUnsafeMutableBytes(of: &a.sun_path) { $0.copyBytes(from: Array(path.utf8) + [0]) }
  let rc = withUnsafePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
  precondition(rc == 0)
  let wrong = "1"; _ = wrong.withCString { send(fd, $0, strlen($0), MSG_NOSIGNAL) }
  var rejected = [UInt8](repeating: 0, count: 32); let rejectedCount = recv(fd, &rejected, rejected.count, 0)
  precondition(rejectedCount > 0 && String(decoding: rejected.prefix(rejectedCount), as: UTF8.self) == "refused" && child.isRunning)
  close(fd)
  let second = socket(AF_UNIX, SOCK_STREAM, 0); precondition(second >= 0); defer { close(second) }
  let secondRC = withUnsafePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(second, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
  precondition(secondRC == 0); let request = String(child.processIdentifier)
  _ = request.withCString { send(second, $0, strlen($0), MSG_NOSIGNAL) }
  var bytes = [UInt8](repeating: 0, count: 32); let n = recv(second, &bytes, bytes.count, 0)
  precondition(n > 0 && String(decoding: bytes.prefix(n), as: UTF8.self) == "terminated")
  child.waitUntilExit(); precondition(child.terminationReason == .uncaughtSignal && child.terminationStatus == SIGTERM)
  daemon.terminate(); daemon.waitUntilExit(); precondition(!FileManager.default.fileExists(atPath: path))
  print("Fake unprivileged socket: exact signed synthetic child terminated; SIGTERM removes owned socket. No registration or root.")
 }
}

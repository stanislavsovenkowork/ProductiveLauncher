import Foundation
import Darwin
@main enum SMCalendarIdleCleanupTests {
 static func main() throws {
    let path = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
    try FileManager.default.createDirectory(at: path, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    try SMCalendarFixtureCleanup.clear(directory: path, owner: getuid(), allowEmpty: true)
    precondition(!FileManager.default.fileExists(atPath: path.path))
    print("Idle empty fixture directory cleanup passed without root.")
 }
}

import Foundation
import Darwin
@main enum GuardFixture { static func main() { if CommandLine.arguments.contains("--ignore-term") { signal(SIGTERM, SIG_IGN) }; FileHandle.standardOutput.write(Data("ready\n".utf8)); Thread.sleep(forTimeInterval: 30) } }

import Foundation
import Darwin

@main enum StrictRootIPCTests {
 static func main() throws {
    guard CommandLine.arguments.count == 2 else { exit(1) }
    let root=URL(fileURLWithPath:"/tmp/fdstrict-"+UUID().uuidString.prefix(8))
    try FileManager.default.createDirectory(at:root,withIntermediateDirectories:false)
    defer { try? FileManager.default.removeItem(at:root) }
    let envelope=StrictRootEnvelope(schemaVersion:1,kind:.discover,enrollment:nil,request:nil)
    func one(_ mode:String, expectedUID: uid_t, wrongHash:Bool, succeeds:Bool) throws {
        let path=root.appendingPathComponent(UUID().uuidString.prefix(8).description).path
        let process=Process();process.executableURL=URL(fileURLWithPath:CommandLine.arguments[1]);process.arguments=[path,mode]
        process.standardOutput=FileHandle.nullDevice;process.standardError=FileHandle.nullDevice;try process.run()
        defer { if process.isRunning { process.terminate() };process.waitUntilExit() }
        let deadline=ProcessInfo.processInfo.systemUptime+5
        while !FileManager.default.fileExists(atPath:path) && process.isRunning && ProcessInfo.processInfo.systemUptime<deadline { usleep(10_000) }
        guard let hash=RootSocketServer.verifiedHash(pid:process.processIdentifier) else { fatalError("fake signature unavailable") }
        let client=StrictRootIPCClient(path:path,expectedDaemonHash:wrongHash ? String(repeating:"0",count:40) : hash,expectedUID:expectedUID)
        let result=try? client.send(envelope)
        precondition((result != nil) == succeeds)
        if succeeds { precondition(result!.accepted && result!.reason == "not_enrolled" && result!.snapshot == nil) }
    }
    try one("reply",expectedUID:getuid(),wrongHash:false,succeeds:true)
    try one("reply",expectedUID:getuid(),wrongHash:true,succeeds:false)
    if getuid() != 0 { try one("reply",expectedUID:0,wrongHash:false,succeeds:false) }
    try one("silent",expectedUID:getuid(),wrongHash:false,succeeds:false)
    print("PASS: bounded newline IPC, same-socket UID and live CDHash checks, absent authority response and timeout; synthetic process only.")
 }
}

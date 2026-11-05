import Foundation
import Darwin

// Fixture binding is deliberately outside the production protocol. Both sides
// authenticate the same connected socket before accepting the QA-only frame.
private func bindFixture(path: String, daemonHash: String, pid: Int32) throws -> StrictRootReply {
    guard pid > 1, daemonHash.count == 40,
          daemonHash.unicodeScalars.allSatisfy({ CharacterSet(charactersIn:"0123456789abcdefABCDEF").contains($0) }) else { throw StrictRootIPCClient.Failure.invalidEnvelope }
    let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw StrictRootIPCClient.Failure.invalidPeer }; defer { close(fd) }
    guard fcntl(fd, F_SETFL, O_NONBLOCK) == 0 else { throw StrictRootIPCClient.Failure.invalidPeer }
    var address = try guardAddress(path)
    let rc = withUnsafePointer(to:&address) { $0.withMemoryRebound(to:sockaddr.self,capacity:1) { connect(fd,$0,socklen_t(MemoryLayout<sockaddr_un>.size)) } }
    if rc != 0 {
        guard errno == EINPROGRESS || errno == EAGAIN else { throw StrictRootIPCClient.Failure.invalidPeer }
        var ready = pollfd(fd:fd,events:Int16(POLLOUT),revents:0)
        guard poll(&ready,1,2000) > 0 else { throw StrictRootIPCClient.Failure.timeout }
        var error: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
        guard getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&size) == 0, error == 0 else { throw StrictRootIPCClient.Failure.invalidPeer }
    }
    var uid: uid_t = 0, gid: gid_t = 0, peerPID: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
    guard getpeereid(fd,&uid,&gid) == 0, uid == 0,
          getsockopt(fd,SOL_LOCAL,LOCAL_PEERPID,&peerPID,&size) == 0,
          RootSocketServer.verifiedHash(pid:peerPID)?.lowercased() == daemonHash.lowercased() else { throw StrictRootIPCClient.Failure.invalidPeer }
    let data = try JSONSerialization.data(withJSONObject:["schemaVersion":1,"kind":"fixture-bind","pid":pid],options:[.sortedKeys])
    try writeRootFrame(data,fd:fd,timeout:2)
    let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
    let reply = try decoder.decode(StrictRootReply.self,from:readRootFrame(fd,timeout:2))
    guard reply.schemaVersion == 1 else { throw StrictRootIPCClient.Failure.invalidResponse }
    return reply
}

// QA-only executable uses the same presentation/controller and authenticated
// root IPC client intended for UI. It can enroll only the fixed synthetic app.
@main enum StrictRootQAClient {
 @MainActor static func main() async {
    let args=CommandLine.arguments
    guard (args.count == 4 || (args.count == 5 && args[3] == "bind")), args[1].hasPrefix("/tmp/focusdeck-strict-root-qa-") else { exit(2) }
    let socket=args[1], daemonHash=args[2], action=args[3]
    let client=StrictRootIPCClient(path:socket,expectedDaemonHash:daemonHash)
    let controller=StrictRootController(transport:client,taskContext:"synthetic task")
    await controller.refresh()
    var success=false, reason="unknown"
    switch action {
    case "status": success=controller.presentation.authorityConfirmed;reason=controller.presentation.noWindow ? "not_enrolled" : "observed"
    case "enroll":
        let now=Date(timeIntervalSince1970:floor(Date().timeIntervalSince1970))
        let fixture=TrustedSigningRequirement(signingID:"dev.stanislav.focusdeck.guard-fixture",teamID:"SYNTHETIC",requirement:"3e481e3d08c534a55081e55313fa2768f44d57bc")
        let plan=StrictRootPlan(schemaVersion:1,revisionId:UUID(),windowId:UUID(),startsAt:now,endsAt:now.addingTimeInterval(90),appRequirements:[fixture],siteDomains:[],siteSubdomains:false)
        success=await controller.enroll(plan);reason=success ? "enrolled" : "not_confirmed"
    case "request-exit": success=await controller.perform(.requestExit);reason=success ? "exit_requested" : "not_confirmed"
    case "confirm-exit": success=await controller.perform(.confirmExit);reason=success ? "exited" : "not_confirmed"
    case "rest": success=await controller.perform(.rest);reason=success ? "rest" : "not_confirmed"
    case "return": success=await controller.perform(.return);reason=success ? "returned" : "not_confirmed"
    case "bind":
        if args.count == 5, let pid = Int32(args[4]), let reply = try? bindFixture(path:socket,daemonHash:daemonHash,pid:pid) {
            success=reply.accepted && reply.reason == "fixture_bound";reason=reply.reason
        }
    case "raw-confirm":
        if let snapshot=controller.presentation.snapshot {
            let request=StrictRootRequest(schemaVersion:1,revisionId:snapshot.plan.revisionId,windowId:snapshot.plan.windowId,command:.confirmExit)
            if let reply=try? client.send(.init(schemaVersion:1,kind:.command,enrollment:nil,request:request)) {
                success = !reply.accepted && reply.reason == "exit_not_ready"; reason=reply.reason
            }
        }
    default: exit(2)
    }
    let snapshot=controller.presentation.snapshot
    let result: [String:Any] = ["ok":success,"reason":reason,"state":snapshot?.phase.rawValue ?? "none",
        "clock":snapshot?.clock.rawValue ?? "unknown", "requested":snapshot?.enforcementRequested ?? false,
        "remaining":snapshot?.exitRemainingSeconds as Any? ?? NSNull(),"taskContextPreserved":controller.presentation.taskContext == "synthetic task"]
    guard let data=try? JSONSerialization.data(withJSONObject:result,options:[.sortedKeys]) else { exit(1) }
    print(String(decoding:data,as:UTF8.self));if !success { exit(1) }
 }
}

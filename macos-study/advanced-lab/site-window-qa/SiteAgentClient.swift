import Foundation
import Darwin

// QA-only client: bind each response to the connected agent's UID and exact
// executable CDHash. It never sends URLs or page contents to the agent.
@main enum SiteAgentClient {
 static func main() {
  do {
   let a = CommandLine.arguments
   guard a.count == 4 else { throw RootSocketFailure.invalidResponse }
   let enc = JSONEncoder(); enc.dateEncodingStrategy = .iso8601
   let dec = JSONDecoder(); dec.dateDecodingStrategy = .iso8601
   func exchange(_ request: WindowGuardRequest) throws -> WindowGuardReply {
    let fd = socket(AF_UNIX, SOCK_STREAM, 0)
    guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
    var address = try guardAddress(a[1])
    let rc = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
    var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0, size = socklen_t(MemoryLayout<Int32>.size)
    guard rc == 0, getpeereid(fd, &uid, &gid) == 0, uid == getuid(),
          getsockopt(fd, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
          RootSocketServer.verifiedHash(pid: pid)?.lowercased() == a[2].lowercased() else { throw RootSocketFailure.invalidResponse }
    try writeRootFrame(enc.encode(request), fd: fd, timeout: 2)
    return try dec.decode(WindowGuardReply.self, from: readRootFrame(fd, timeout: 2))
   }
   let now = Date(), target = "dev.stanislav.focusdeck.guard-fixture"
   var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: "qa", startsAt: now, endsAt: now.addingTimeInterval(60), reliefBudgetSeconds: 0, reliefRemainingSeconds: 0, reliefMaxGrantSeconds: 1, targetRefs: [target])
   if a[3] == "publish" || a[3] == "publish_site" {
    p.localDate = "2026-11-02"; p.startsAt = now.addingTimeInterval(-1); p.endsAt = now.addingTimeInterval(300); p.state = .active
    p.reliefBudgetSeconds = 120; p.reliefRemainingSeconds = 120; p.reliefMaxGrantSeconds = 5; p.exitCoolingOffSeconds = 1
    let req = TrustedSigningRequirement(signingID: target, teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")
    if a[3] == "publish_site" { p.targetRefs = []; p.siteDomains = ["qa.example"] }
    let requirements = a[3] == "publish_site" ? [] : [req]
    guard try exchange(WindowGuardRequest(policy: p, command: "publish", signingRequirements: requirements)).accepted else { throw RootSocketFailure.invalidResponse }
    print("{\"ok\":true}"); return
   }
   let status = try exchange(WindowGuardRequest(policy: p, command: "status"))
   guard status.accepted, status.scheduleStatus != "clock_unknown" else { throw RootSocketFailure.invalidResponse }
   if a[3] == "status" {
    let phase = status.policy == nil ? "released" : (status.enforcement == "selected_apps_requested" ? "active" : "released")
    print("{\"phase\":\"\(phase)\"}"); return
   }
   guard var current = status.policy else { throw RootSocketFailure.invalidResponse }
   let changed: Bool
   switch a[3] {
   case "relief": changed = current.relief(now: now)
   case "return": changed = current.returnToWindow(now: now)
   case "request_exit": changed = current.requestExit(now: now)
   case "exit": changed = current.exitWindow(now: now)
   default: changed = false
   }
   guard changed, try exchange(WindowGuardRequest(policy: current, command: "update")).accepted else { throw RootSocketFailure.invalidResponse }
   print("{\"ok\":true}")
  } catch { print("{\"phase\":\"unknown\"}"); exit(1) }
 }
}

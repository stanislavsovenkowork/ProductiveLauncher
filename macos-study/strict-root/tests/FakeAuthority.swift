import Foundation
import Darwin

@main enum FakeAuthority {
 static func main() throws {
    let args=CommandLine.arguments; guard args.count == 3 else { exit(1) }
    let fd=socket(AF_UNIX,SOCK_STREAM,0);guard fd >= 0 else { exit(1) };defer { close(fd);unlink(args[1]) }
    var address=try guardAddress(args[1]);guard withUnsafePointer(to:&address, { $0.withMemoryRebound(to:sockaddr.self,capacity:1) { bind(fd,$0,socklen_t(MemoryLayout<sockaddr_un>.size)) } }) == 0,
       listen(fd,1)==0 else { exit(1) }
    let peer=accept(fd,nil,nil);guard peer >= 0 else { exit(1) };defer { close(peer) }
    if args[2] == "silent" { sleep(5);return }
    guard let data=try? readRootFrame(peer,timeout:2), let envelope=try? JSONDecoder().decode(StrictRootEnvelope.self,from:data), envelope.validShape() else { return }
    let reply=StrictRootReply(schemaVersion:1,accepted:true,reason:"not_enrolled",snapshot:nil)
    try writeRootFrame(reply.frame(),fd:peer,timeout:2)
 }
}

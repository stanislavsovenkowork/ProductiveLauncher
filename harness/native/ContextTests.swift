import Foundation

@main
struct ContextTests {
    static func row(_ data: Data) throws -> [String:Any] { try JSONSerialization.jsonObject(with:data) as! [String:Any] }
    static func main() throws {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        func sample(_ seconds: Double, _ bundle: String? = "fixture.editor", _ available: Bool = true, _ link: SessionLink? = nil) -> ContextSample {
            ContextSample(wall:start.addingTimeInterval(seconds),monotonic:100+seconds,bundleID:bundle,available:available,session:link)
        }
        var recorder = ContextRecorder()
        var event = try row(recorder.sample(sample(0),reason:"startup"))
        precondition(event["category"] is NSNull && event["sessionId"] is NSNull)
        precondition((event["metrics"] as! [String:Any])["focusSeconds"] is NSNull)
        event = try row(recorder.sample(sample(20,"fixture.browser"),reason:"frontmost_transition"))
        var context = event["context"] as! [String:Any]
        precondition(context["bundleId"] as? String == "fixture.editor")
        precondition(context["observedSeconds"] as? Double == 20)
        // A quiet foreground reading interval remains context with unknown attention.
        event = try row(recorder.sample(sample(40,"fixture.browser"),reason:"sample"))
        precondition((event["metrics"] as! [String:Any])["distractionSeconds"] is NSNull)
        event = try row(recorder.sample(sample(200,"fixture.browser"),reason:"sample"))
        context = event["context"] as! [String:Any]
        precondition(context["observedSeconds"] is NSNull)
        precondition(context["unknownGapSeconds"] as? Double == 160)
        // No elapsed context is invented across inactive screen/session signals.
        _ = try recorder.sample(sample(210,nil,false),reason:"sleep")
        event = try row(recorder.sample(sample(220,"fixture.editor"),reason:"wake"))
        precondition((event["context"] as! [String:Any])["observedSeconds"] is NSNull)
        let link = SessionLink(sessionID:UUID(),category:"study")
        _ = try recorder.sample(sample(230,"fixture.editor",true,link),reason:"sample")
        event = try row(recorder.sample(sample(240,"fixture.editor",true,link),reason:"sample"))
        precondition(event["category"] as? String == "study")
        precondition(event["sessionId"] as? String == link.sessionID.uuidString.lowercased())
        let snapshot = PassiveSessionSnapshot(sessionId:link.sessionID,category:"study",phase:"running",lastTick:start)
        precondition(snapshot.link(now:start.addingTimeInterval(5)) == link)
        precondition(snapshot.link(now:start.addingTimeInterval(11)) == nil)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:directory) }
        let journal = ContextJournal(path:directory.appendingPathComponent("fixture-events.jsonl"),retentionDays:7)
        try journal.append(try JSONSerialization.data(withJSONObject:event),now:start.addingTimeInterval(240))
        let permissions = try FileManager.default.attributesOfItem(atPath:journal.path.path)[.posixPermissions] as! Int
        precondition(permissions & 0o777 == 0o600)
        print("ContextTests: synthetic context checks passed")
    }
}

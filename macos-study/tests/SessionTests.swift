import Foundation

@main
enum SessionTests {
    static func main() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        var state = SessionState(); state.taskTitle = "Synthetic reading task"; state.taskRef = "synthetic-task"
        state.start(now: now)
        precondition(state.phase == .running && state.remainingSeconds == 1500)
        _ = state.tick(elapsed: 1, now: now.addingTimeInterval(1))
        precondition(state.remainingSeconds == 1499)
        let segment = state.pause(now: now.addingTimeInterval(1))!
        precondition(segment.0 == now && segment.1 == now.addingTimeInterval(1))
        precondition(state.usedPause && state.phase == .paused)
        _ = state.tick(elapsed: 1, now: now.addingTimeInterval(302))
        precondition(state.phase == .returnReady && state.remainingSeconds == 1499)
        state.resume(now: now.addingTimeInterval(303))
        precondition(state.phase == .running)
        precondition(state.pause(now: now.addingTimeInterval(304)) == nil)
        var restored = state
        restored.recoverAfterRelaunch()
        precondition(restored.phase == .returnReady && restored.segmentStart == nil)
        precondition(restored.taskRef == "synthetic-task" && restored.usedPause)
        let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
        let store = SessionStore(directory: root)
        state.note = "Synthetic next action"
        try store.save(state)
        let loaded = try store.load()
        precondition(loaded!.sessionId == state.sessionId && loaded!.note == "Synthetic next action")
        try store.append(SessionEvent(state: state, type: "started", now: now))
        try store.append(SessionEvent(state: state, type: "paused", now: segment.1, interval: segment))
        try store.append(SessionEvent(state: state, type: "resumed", now: now.addingTimeInterval(303)))
        _ = state.tick(elapsed: 2, now: now.addingTimeInterval(305))
        let final = state.closeSegment(now: now.addingTimeInterval(310))!
        try store.append(SessionEvent(state: state, type: "stopped", now: final.1, interval: final))
        var overrun = SessionState(); overrun.start(now: now)
        var crossings = 0
        for second in 1...1501 { if overrun.tick(elapsed: 1, now: now.addingTimeInterval(Double(second))) { crossings += 1 } }
        precondition(crossings == 1 && overrun.phase == .running && overrun.remainingSeconds == -1)
        precondition(overrun.closeSegment(now: now.addingTimeInterval(1501))!.1.timeIntervalSince(now) == 1501)
        var pausedClock = SessionState(); pausedClock.start(now: now); _ = pausedClock.pause(now: now)
        _ = pausedClock.tick(elapsed: 300, now: now.addingTimeInterval(-100))
        precondition(pausedClock.phase == .returnReady)
        var legacy = try JSONSerialization.jsonObject(with: JSONEncoder().encode(state)) as! [String: Any]
        legacy.removeValue(forKey: "segmentElapsed")
        let migrated = try JSONDecoder().decode(SessionState.self, from: JSONSerialization.data(withJSONObject: legacy))
        precondition(migrated.taskRef == state.taskRef && migrated.segmentElapsed == 0)
        if CommandLine.arguments.count > 2 {
            let base = ISO8601DateFormatter().date(from: "2026-10-28T09:00:00Z")!
            var sample = SessionState(); sample.taskRef = "synthetic-cross-device-task"; sample.category = "work"
            sample.start(now: base)
            sample.sessionId = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
            var exports = [SessionEvent(state: sample, type: "started", now: base)]
            for second in 1...300 { _ = sample.tick(elapsed: 1, now: base.addingTimeInterval(Double(second))) }
            let first = sample.pause(now: base.addingTimeInterval(300))!
            exports.append(SessionEvent(state: sample, type: "paused", now: first.1, interval: first))
            _ = sample.tick(elapsed: 1, now: base.addingTimeInterval(600))
            precondition(sample.phase == .returnReady)
            sample.resume(now: base.addingTimeInterval(600))
            exports.append(SessionEvent(state: sample, type: "resumed", now: base.addingTimeInterval(600)))
            for second in 601...1200 { _ = sample.tick(elapsed: 1, now: base.addingTimeInterval(Double(second))) }
            let second = sample.closeSegment(now: base.addingTimeInterval(1200))!
            exports.append(SessionEvent(state: sample, type: "completed", now: second.1, interval: second))
            precondition(first.1.timeIntervalSince(first.0) == 300 && second.1.timeIntervalSince(second.0) == 600)
            let exported = try exports.map { String(decoding: try JSONEncoder().encode($0), as: UTF8.self) }.joined(separator: "\n") + "\n"
            try exported.write(toFile: CommandLine.arguments[2], atomically: true, encoding: .utf8)
        }
        print("Session pause/deadline/explicit return/relaunch/context/journal tests passed.")
    }
}

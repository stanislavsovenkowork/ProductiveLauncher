import Foundation

@main enum ScheduleBoundaryTests {
    static func main() {
        let now = Date(timeIntervalSince1970: 1_793_625_600)
        // An already started window, including one that began just now, is
        // distinct from an upcoming start on the maintenance boundary.
        precondition(!scheduleStartIsNear(now.addingTimeInterval(-3600), now: now))
        precondition(!scheduleStartIsNear(now.addingTimeInterval(-1), now: now))
        precondition(scheduleStartIsNear(now, now: now))
        precondition(scheduleStartIsNear(now.addingTimeInterval(1), now: now))
        precondition(scheduleStartIsNear(now.addingTimeInterval(120), now: now))
        precondition(!scheduleStartIsNear(now.addingTimeInterval(120.001), now: now))
        print("PASS: past scheduled start permits maintenance; current or next 120 seconds blocked; later start allowed.")
    }
}

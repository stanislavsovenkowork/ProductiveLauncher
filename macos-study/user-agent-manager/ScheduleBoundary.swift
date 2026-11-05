import Foundation

// Only an imminent start can race lifecycle operations. A start in the past
// is not a future boundary; the agent's fresh terminal reply is still required.
func scheduleStartIsNear(_ start: Date, now: Date) -> Bool {
    let seconds = start.timeIntervalSince(now)
    return seconds >= 0 && seconds <= 120
}

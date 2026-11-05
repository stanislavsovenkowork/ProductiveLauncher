import Foundation

struct SessionState: Codable {
    enum Phase: String, Codable { case ready, running, paused, returnReady, finished }
    var sessionId: UUID?
    var taskTitle = ""
    var taskRef = ""
    var taskNotePath: String?
    var taskCompleted = false
    var category = "study"
    var phase = Phase.ready
    var remainingSeconds: Double = 25 * 60
    var pauseUntil: Date?
    var segmentElapsed: Double = 0
    var segmentStart: Date?
    var lastTick: Date?
    var usedPause = false
    var pauseRemainingSeconds: Double = 300
    var focusRequested = false
    var quietUntil: Date?
    var quietOutcome: String?
    var note = ""

    init() {}
    enum CodingKeys: String, CodingKey {
        case sessionId, taskTitle, taskRef, taskNotePath, taskCompleted, category, phase, remainingSeconds, pauseUntil, segmentStart, segmentElapsed, lastTick, usedPause, pauseRemainingSeconds, focusRequested, quietUntil, quietOutcome, note
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        sessionId = try values.decodeIfPresent(UUID.self, forKey: .sessionId)
        taskTitle = try values.decodeIfPresent(String.self, forKey: .taskTitle) ?? ""
        taskRef = try values.decodeIfPresent(String.self, forKey: .taskRef) ?? ""
        taskNotePath = try values.decodeIfPresent(String.self, forKey: .taskNotePath)
        taskCompleted = try values.decodeIfPresent(Bool.self, forKey: .taskCompleted) ?? false
        category = try values.decodeIfPresent(String.self, forKey: .category) ?? "study"
        phase = try values.decodeIfPresent(Phase.self, forKey: .phase) ?? .ready
        remainingSeconds = try values.decodeIfPresent(Double.self, forKey: .remainingSeconds) ?? 1500
        pauseUntil = try values.decodeIfPresent(Date.self, forKey: .pauseUntil)
        segmentStart = try values.decodeIfPresent(Date.self, forKey: .segmentStart)
        segmentElapsed = try values.decodeIfPresent(Double.self, forKey: .segmentElapsed) ?? 0
        lastTick = try values.decodeIfPresent(Date.self, forKey: .lastTick)
        usedPause = try values.decodeIfPresent(Bool.self, forKey: .usedPause) ?? false
        pauseRemainingSeconds = try values.decodeIfPresent(Double.self, forKey: .pauseRemainingSeconds) ?? 300
        focusRequested = try values.decodeIfPresent(Bool.self, forKey: .focusRequested) ?? false
        quietUntil = try values.decodeIfPresent(Date.self, forKey: .quietUntil)
        quietOutcome = try values.decodeIfPresent(String.self, forKey: .quietOutcome)
        note = try values.decodeIfPresent(String.self, forKey: .note) ?? ""
        guard ["work", "study"].contains(category), remainingSeconds.isFinite,
              remainingSeconds >= -3153600000, remainingSeconds <= 1500,
              pauseRemainingSeconds.isFinite, pauseRemainingSeconds >= 0, pauseRemainingSeconds <= 300,
              segmentElapsed.isFinite, segmentElapsed >= 0, segmentElapsed <= 3153600000 else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid persisted session state"))
        }
    }

    mutating func start(now: Date) {
        sessionId = UUID(); phase = .running; remainingSeconds = 25 * 60
        segmentStart = now; segmentElapsed = 0; lastTick = now; usedPause = false; pauseUntil = nil; pauseRemainingSeconds = 300
    }
    mutating func pause(now: Date) -> (Date, Date)? {
        guard phase == .running, !usedPause else { return nil }
        let interval = closeSegment(now: now)
        usedPause = true; phase = .paused; pauseUntil = now.addingTimeInterval(300); pauseRemainingSeconds = 300
        return interval
    }
    mutating func resume(now: Date) {
        guard phase == .paused || phase == .returnReady else { return }
        phase = .running; pauseUntil = nil; pauseRemainingSeconds = 0; segmentStart = now; segmentElapsed = 0; lastTick = now
    }
    mutating func closeSegment(now: Date) -> (Date, Date)? {
        guard let start = segmentStart else { return nil }
        let end = start.addingTimeInterval(segmentElapsed)
        segmentStart = nil; lastTick = nil; segmentElapsed = 0
        return (start, end)
    }
    // Only called by a live monotonic scheduler. Relaunch never invents elapsed work.
    mutating func tick(elapsed: Double, now: Date) -> Bool {
        if phase == .paused {
            if elapsed.isFinite { pauseRemainingSeconds = max(0, pauseRemainingSeconds - max(0, elapsed)) }
            if pauseRemainingSeconds == 0 || pauseUntil.map({ now >= $0 }) == true { phase = .returnReady; pauseUntil = nil }
        }
        guard phase == .running else { return false }
        let crossedBefore = remainingSeconds > 0
        let measured = elapsed.isFinite ? max(0, min(elapsed, 3)) : 0
        segmentElapsed += measured
        remainingSeconds -= measured
        lastTick = now
        return crossedBefore && remainingSeconds <= 0
    }
    mutating func recoverAfterRelaunch() {
        if phase == .running || phase == .paused {
            phase = .returnReady; segmentStart = nil; segmentElapsed = 0; lastTick = nil; pauseUntil = nil
        }
    }
}

struct SessionEvent: Encodable {
    let schemaVersion = 1
    let eventId: UUID
    let sessionId: UUID?
    let occurredAt: String
    let source = "mac"
    let category: String
    let type: String
    let evidence = "timer"
    let coverage = "partial"
    let taskRef: String?
    let startedAt: String?
    let endedAt: String?
    let reason: String?
    let metrics: [String: Double?]
    let protected = false
    let mode = "timer"

    init(state: SessionState, type: String, now: Date, interval: (Date, Date)? = nil, reason: String? = nil) {
        eventId = UUID(); sessionId = state.sessionId; category = state.category; self.type = type
        occurredAt = Self.format(now); taskRef = state.taskRef.isEmpty ? nil : state.taskRef
        startedAt = interval.map { Self.format($0.0) }; endedAt = interval.map { Self.format($0.1) }
        self.reason = reason
        metrics = ["focusSeconds": interval.map { max(0, $0.1.timeIntervalSince($0.0)) }, "distractionSeconds": nil, "maintenanceSeconds": nil]
    }
    static func format(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }
}

struct SessionStore {
    let directory: URL
    var stateURL: URL { directory.appendingPathComponent("session.json") }
    var eventsURL: URL { directory.appendingPathComponent("events.jsonl") }
    func prepare() throws { try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700]) }
    func load() throws -> SessionState? {
        guard FileManager.default.fileExists(atPath: stateURL.path) else { return nil }
        return try JSONDecoder().decode(SessionState.self, from: Data(contentsOf: stateURL))
    }
    func save(_ state: SessionState) throws {
        try prepare()
        try JSONEncoder().encode(state).write(to: stateURL, options: [.atomic])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: stateURL.path)
    }
    func append(_ event: SessionEvent) throws {
        try prepare()
        var data = try JSONEncoder().encode(event); data.append(0x0A)
        if !FileManager.default.fileExists(atPath: eventsURL.path) {
            _ = FileManager.default.createFile(atPath: eventsURL.path, contents: nil, attributes: [.posixPermissions: 0o600])
        }
        let handle = try FileHandle(forWritingTo: eventsURL)
        defer { try? handle.close() }
        try handle.seekToEnd(); try handle.write(contentsOf: data); try handle.synchronize()
    }
}

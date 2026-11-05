import Foundation

struct SessionLink: Equatable {
    let sessionID: UUID
    let category: String
}

struct ContextSample {
    let wall: Date
    let monotonic: Double
    let bundleID: String?
    let available: Bool
    let session: SessionLink?
}

/// Observed foreground context is never converted to focus or distraction.
struct ContextRecorder {
    var previous: ContextSample?
    let maxGap: Double = 45

    mutating func sample(_ next: ContextSample, reason: String) throws -> Data {
        let old = previous
        previous = next
        let elapsed = old.map { max(0, next.monotonic - $0.monotonic) }
        let wallElapsed = old.map { next.wall.timeIntervalSince($0.wall) }
        let continuous = old != nil && old!.available && next.available && old!.bundleID != nil
            && elapsed! > 0 && elapsed! <= maxGap && abs(wallElapsed! - elapsed!) <= 3
        let link = continuous && old!.session == next.session ? next.session : nil
        let null = NSNull()
        let stamp = ISO8601DateFormatter()
        let event: [String: Any] = [
            "schemaVersion": 1, "eventId": UUID().uuidString.lowercased(),
            "sessionId": link?.sessionID.uuidString.lowercased() as Any? ?? null,
            "occurredAt": stamp.string(from: next.wall), "source": "mac",
            "category": link?.category as Any? ?? null, "type": "health", "evidence": "activity",
            "metrics": ["focusSeconds": null, "distractionSeconds": null, "maintenanceSeconds": null],
            "coverage": continuous ? "partial" : "missing",
            "context": ["collector": "frontmost_bundle", "signal": reason,
                        "bundleId": continuous ? old!.bundleID! as Any : null,
                        "observedSeconds": continuous ? elapsed! as Any : null,
                        "unknownGapSeconds": !continuous ? elapsed as Any? ?? null : null,
                        "collectorAvailable": next.available,
                        "availabilityMeaning": "No inactive notification observed; startup screen lock state is unknown.",
                        "meaning": "Foreground context only; attention, reading and useful work unknown."],
            "startedAt": continuous ? stamp.string(from: old!.wall) as Any : null,
            "endedAt": continuous ? stamp.string(from: next.wall) as Any : null
        ]
        return try JSONSerialization.data(withJSONObject: event, options: [.sortedKeys])
    }
}

struct PassiveSessionSnapshot: Decodable {
    let sessionId: UUID?
    let category: String
    let phase: String
    let lastTick: Date?

    func link(now: Date) -> SessionLink? {
        guard phase == "running", let sessionId, ["work", "study"].contains(category),
              let lastTick, now.timeIntervalSince(lastTick) >= 0,
              now.timeIntervalSince(lastTick) <= 10 else { return nil }
        return SessionLink(sessionID: sessionId, category: category)
    }
}

struct ContextJournal {
    let path: URL
    let retentionDays: Int
    let maxBytes = 16 * 1024 * 1024

    func append(_ event: Data, now: Date) throws {
        let directory = path.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                 attributes: [.posixPermissions: 0o700])
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        var rows = Data()
        if FileManager.default.fileExists(atPath: path.path) {
            let size = (try FileManager.default.attributesOfItem(atPath: path.path)[.size] as? Int) ?? 0
            guard size <= maxBytes else { throw NSError(domain: "ContextJournal", code: 1) }
            let existing = try String(contentsOf: path, encoding: .utf8)
            let cutoff = now.addingTimeInterval(-Double(retentionDays) * 86400)
            let formatter = ISO8601DateFormatter()
            for line in existing.split(separator: "\n") {
                // Invalid prior rows stay visible to the review validator; do not invent context.
                let data = Data(line.utf8)
                if let row = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let at = row["occurredAt"] as? String, let date = formatter.date(from: at), date < cutoff { continue }
                rows.append(data); rows.append(0x0A)
            }
        }
        rows.append(event); rows.append(0x0A)
        guard rows.count <= maxBytes else { throw NSError(domain: "ContextJournal", code: 2) }
        try rows.write(to: path, options: [.atomic])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path.path)
    }
}

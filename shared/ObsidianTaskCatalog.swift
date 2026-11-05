import Foundation

/// Adapter for the existing daily-note format. Only an explicitly selected note is used.
/// Completing a task is a separate user action, never a side effect of stopping a timer.
struct ObsidianTaskChoice: Equatable, Identifiable {
    let lineIndex: Int
    let title: String
    let stableID: String?
    let done: Bool
    var id: Int { lineIndex }
}

enum ObsidianTaskCatalog {
    enum WriteFailure: LocalizedError {
        case missingOrAmbiguous, changedTitle, changedFile
        var errorDescription: String? {
            switch self {
            case .missingOrAmbiguous: "Задача удалена, перемещена или её ID повторяется. Выберите её заново из заметки."
            case .changedTitle: "Название задачи изменилось в Obsidian. Выберите актуальную задачу заново."
            case .changedFile: "Заметка изменилась во время сохранения. Повторите действие; сеанс сохранён."
            }
        }
    }

    /// Changes only the checkbox identified by a unique stable ID; preserves all other bytes.
    static func completing(_ content: String, stableID: String, expectedTitle: String) throws -> String {
        guard !stableID.isEmpty else { throw WriteFailure.missingOrAmbiguous }
        let matches = parse(content).filter { $0.stableID == stableID }
        guard matches.count == 1, let task = matches.first else { throw WriteFailure.missingOrAmbiguous }
        guard task.title == expectedTitle else { throw WriteFailure.changedTitle }
        if task.done { return content }
        var lines = content.components(separatedBy: "\n")
        let line = lines[task.lineIndex]
        let pattern = try NSRegularExpression(pattern: #"^\s*-\s\[( )\]"#)
        guard let match = pattern.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)),
              let range = Range(match.range(at: 1), in: line) else { throw WriteFailure.changedFile }
        lines[task.lineIndex].replaceSubrange(range, with: "x")
        return lines.joined(separator: "\n")
    }

    @discardableResult
    static func completeSelectedTask(_ url: URL, stableID: String, expectedTitle: String) throws -> Bool {
        var coordinationError: NSError?
        var result: Result<Bool, Error>?
        NSFileCoordinator().coordinate(writingItemAt: url, options: .forReplacing, error: &coordinationError) { currentURL in
            result = Result {
                _ = try readSelectedNote(currentURL)
                let before = try Data(contentsOf: currentURL)
                guard let content = String(data: before, encoding: .utf8) else { throw WriteFailure.changedFile }
                let after = Data(try completing(content, stableID: stableID, expectedTitle: expectedTitle).utf8)
                if after == before { return false } // Already completed on another device; idempotent.
                guard try Data(contentsOf: currentURL) == before else { throw WriteFailure.changedFile }
                try after.write(to: currentURL, options: .atomic)
                // Syncthing delivery is separate. This verifies only the local selected note.
                let verified = try readSelectedNote(currentURL).filter { $0.stableID == stableID }
                guard verified.count == 1, verified[0].done else { throw WriteFailure.changedFile }
                return true
            }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw WriteFailure.changedFile }
        return try result.get()
    }

    static func readSelectedNote(_ url: URL) throws -> [ObsidianTaskChoice] {
        let resource = try url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard resource.isRegularFile == true, (resource.fileSize ?? Int.max) <= 1024 * 1024 else {
            throw NSError(domain: "FocusDeckTasks", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Выберите обычную заметку до 1 МиБ."])
        }
        return parse(try String(contentsOf: url, encoding: .utf8))
    }

    static func parse(_ content: String) -> [ObsidianTaskChoice] {
        let lines = content.components(separatedBy: "\n")
        guard let heading = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespacesAndNewlines) == "## Задачи" }) else { return [] }
        let task = try! NSRegularExpression(pattern: #"^\s*-\s\[( |x|X)\]\s*(.*)$"#)
        let id = try! NSRegularExpression(pattern: #"\^task-([A-Za-z0-9_-]+)\s*$"#)
        let stripped = [
            #"\[focusdeck-note::\s*\[\[[^\r\n\[\]]+\]\]\s*\]"#,
            #"[⏳🕐]\s*\d{1,2}:\d{2}"#,
            #"\[time::\s*\d{1,2}:\d{2}\]"#,
            #"\[dur::\s*\d+\]"#,
            #"\[(?:project|goal|area)::\s*[A-Za-z0-9_-]+\]"#,
            #"\^task-[A-Za-z0-9_-]+\s*$"#
        ].map { try! NSRegularExpression(pattern: $0) }
        var result: [ObsidianTaskChoice] = []
        for number in (heading + 1)..<lines.count {
            let line = lines[number]
            if line.trimmingCharacters(in: .whitespaces).hasPrefix("#") { break }
            let whole = NSRange(line.startIndex..., in: line)
            guard let match = task.firstMatch(in: line, range: whole),
                  let flagRange = Range(match.range(at: 1), in: line),
                  let rawRange = Range(match.range(at: 2), in: line) else { continue }
            let raw = String(line[rawRange])
            let rawRangeNS = NSRange(raw.startIndex..., in: raw)
            let stable = id.firstMatch(in: raw, range: rawRangeNS).flatMap { Range($0.range(at: 1), in: raw) }.map { String(raw[$0]) }
            var title = raw
            for regex in stripped {
                title = regex.stringByReplacingMatches(in: title, range: NSRange(title.startIndex..., in: title), withTemplate: "")
            }
            title = title.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { continue }
            result.append(ObsidianTaskChoice(lineIndex: number, title: title, stableID: stable,
                                            done: line[flagRange].lowercased() == "x"))
        }
        return result
    }
}

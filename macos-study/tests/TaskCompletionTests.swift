import Foundation

@main
enum TaskCompletionTests {
    static func main() throws {
        let note = "---\r\nkeep: yes\r\n---\r\n## Задачи\r\n- [ ] Другая ^task-other\r\n- [ ] Проверить связь ⏳ 18:00 [dur:: 25] ^task-owned\r\n## Дневник\r\nНе менять ✨\r\n"
        let result = try ObsidianTaskCatalog.completing(note, stableID: "owned", expectedTitle: "Проверить связь")
        precondition(result == note.replacingOccurrences(of: "- [ ] Проверить", with: "- [x] Проверить"))
        let repeated = try ObsidianTaskCatalog.completing(result, stableID: "owned", expectedTitle: "Проверить связь")
        precondition(repeated == result)
        let reordered = note.replacingOccurrences(of: "## Задачи\r\n", with: "## Задачи\r\n- [ ] Новая ^task-new\r\n")
        let moved = try ObsidianTaskCatalog.completing(reordered, stableID: "owned", expectedTitle: "Проверить связь")
        precondition(moved.contains("- [x] Проверить связь"))
        for (content, id, title) in [(note, "missing", "Проверить связь"), (note, "owned", "Устаревшее название"),
                                    (note.replacingOccurrences(of: "## Дневник", with: "- [ ] Дубликат ^task-owned\r\n## Дневник"), "owned", "Проверить связь")] {
            do { _ = try ObsidianTaskCatalog.completing(content, stableID: id, expectedTitle: title); preconditionFailure("Ambiguous/stale task accepted") }
            catch is ObsidianTaskCatalog.WriteFailure { }
        }
        var state = SessionState(); state.taskRef = "owned"; state.taskNotePath = "/selected/note.md"; state.taskCompleted = true
        let restored = try JSONDecoder().decode(SessionState.self, from: JSONEncoder().encode(state))
        precondition(restored.taskNotePath == state.taskNotePath && restored.taskCompleted)
        let legacy = try JSONDecoder().decode(SessionState.self, from: Data("{}".utf8))
        precondition(legacy.taskNotePath == nil && !legacy.taskCompleted)
        print("PASS: exact checkbox, CRLF/Unicode/metadata, stable ID after reorder, idempotency, missing/duplicate/renamed refusal, session migration")
    }
}

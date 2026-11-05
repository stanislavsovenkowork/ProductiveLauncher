import Foundation

@main enum CatalogTests {
    static func main() throws {
        let note = """
        - [ ] Not a daily task
        ## Задачи
        - [ ] Изучить async ⏳ 16:30 [dur:: 25] [project:: demo] [focusdeck-note:: [[Concurrency]]] ^task-abc_12
        - [X] Done ^task-done
        - [ ]
        - [ ] Legacy task
        ## Дневник
        - [ ] Not a task
        """
        let choices = ObsidianTaskCatalog.parse(note)
        precondition(choices.count == 3)
        precondition(choices[0].title == "Изучить async")
        precondition(choices[0].stableID == "abc_12")
        precondition(choices[1].done)
        precondition(choices[2].stableID == nil)
        precondition(ObsidianTaskCatalog.parse("## Other\n- [ ] Nope").isEmpty)
        precondition(ObsidianTaskCatalog.parse("## Задачи").isEmpty)
        let path = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".md")
        defer { try? FileManager.default.removeItem(at: path) }
        try note.write(to: path, atomically: true, encoding: .utf8)
        let before = try Data(contentsOf: path)
        let read = try ObsidianTaskCatalog.readSelectedNote(path)
        precondition(read == choices)
        let after = try Data(contentsOf: path)
        precondition(after == before)
        print("Read-only selected-note task adapter: 9 assertions passed")
    }
}

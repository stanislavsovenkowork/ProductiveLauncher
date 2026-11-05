import Foundation
import Darwin

@main enum StoreTests {
    static func main() throws {
        var template = Array("/tmp/focusdeck-strict-root-qa.XXXXXXXX\0".utf8CString)
        guard let path = mkdtemp(&template) else { fatalError("mkdtemp") }
        let directory = String(cString: path)
        defer { try? FileManager.default.removeItem(atPath: directory) }
        guard chmod(directory, 0o700) == 0 else { fatalError("chmod") }
        var store: StrictRootStore? = try StrictRootStore(directory: directory, owner: getuid())
        guard let authority = store else { fatalError("init") }
        let first = Data("first".utf8), second = Data("second".utf8)
        let initial = try authority.read(); precondition(initial == nil)
        try authority.commit(first, matching: nil)
        let savedFirst = try authority.read(); precondition(savedFirst == first)
        do { try authority.commit(second, matching: nil); fatalError("first enrollment overwritten") } catch StrictRootStore.Failure.changed {}
        do { _ = try StrictRootStore(directory: directory, owner: getuid()); fatalError("duplicate authority accepted") } catch StrictRootStore.Failure.busy {}
        try authority.commit(second, matching: first)
        let savedSecond = try authority.read(); precondition(savedSecond == second)
        let record = directory + "/record.json"
        guard unlink(record) == 0 else { fatalError("unlink own QA record") }
        do { try authority.commit(first, matching: second); fatalError("missing record reconstructed") } catch StrictRootStore.Failure.changed {}
        guard symlink("/etc/hosts", record) == 0 else { fatalError("symlink fixture") }
        do { _ = try authority.read(); fatalError("symlink accepted") } catch StrictRootStore.Failure.unsafe {}
        store = nil
        print("PASS: private directory, exclusive lock, durable compare-and-commit, missing and symlink rejection")
    }
}

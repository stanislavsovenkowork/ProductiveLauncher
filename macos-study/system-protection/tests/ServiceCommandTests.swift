import Foundation
import Darwin

@main enum ServiceCommandChecks {
    static func descriptorCount() -> Int {
        var bytes = [UInt64](repeating: 0, count: 4096)
        let count = bytes.withUnsafeMutableBytes { proc_pidinfo(getpid(), PROC_PIDLISTFDS, 0, $0.baseAddress, Int32($0.count)) }
        precondition(count > 0)
        return Int(count) / 8
    }
    static func main() throws {
        var limits = rlimit()
        precondition(getrlimit(RLIMIT_NOFILE, &limits) == 0)
        limits.rlim_cur = 128
        precondition(setrlimit(RLIMIT_NOFILE, &limits) == 0)
        _ = try ServiceCommand.run("/usr/bin/true", [])
        let initial = descriptorCount()
        for index in 0..<400 {
            if index % 4 == 0 {
                let text = "FocusDeck pipe lifecycle \(index)\n"
                let result = try ServiceCommand.run("/bin/cat", [], input: Data(text.utf8))
                precondition(result == text)
            } else if index % 4 == 1 {
                do { _ = try ServiceCommand.run("/usr/bin/false", []); preconditionFailure("nonzero child must fail") }
                catch ServiceCommandFailure.processFailed(1) { }
            } else if index % 4 == 2 {
                do { _ = try ServiceCommand.run("/does-not-exist-focusdeck", []); preconditionFailure("missing executable must fail") }
                catch { }
            } else { _ = try ServiceCommand.run("/usr/bin/true", []) }
        }
        let final = descriptorCount()
        precondition(final <= initial + 4, "Descriptor leak: \(initial) -> \(final)")
        print("PASS: 400 commands including stdin, failed launch and nonzero exit under 128-FD limit; descriptors \(initial) -> \(final)")
    }
}

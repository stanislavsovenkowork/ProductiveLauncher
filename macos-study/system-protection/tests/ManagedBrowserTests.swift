import Foundation
import Darwin

@main enum ManagedBrowserChecks {
    static func main() throws {
        let required = ["--proxy-server=http://127.0.0.1:55", "--disable-quic", "--user-data-dir=/managed"]
        precondition(BrowserRuntimePolicy.permits(arguments: ["Chrome"] + required, required: required))
        for extra in ["--proxy-server=direct://", "--proxy-server", "--no-proxy-server", "--proxy-pac-url=file:///tmp/a", "--remote-debugging-port=9222", "--load-extension=/tmp/a", "--type=renderer"] {
            precondition(!BrowserRuntimePolicy.permits(arguments: required + [extra], required: required), extra)
        }
        precondition(!BrowserRuntimePolicy.permits(arguments: Array(required.dropFirst()), required: required))
        precondition(!BrowserRuntimePolicy.claimsWebBrowser([["CFBundleURLSchemes": ["http", "https", "codex"]]]))
        precondition(BrowserRuntimePolicy.claimsWebBrowser([["CFBundleTypeRole": "Viewer", "CFBundleURLSchemes": ["https"]]]))
        let chat = Bundle(path: "/Applications/ChatGPT.app")
        precondition(chat?.bundleIdentifier == "com.openai.codex")
        precondition(!BrowserRuntimePolicy.claimsWebBrowser(chat?.infoDictionary?["CFBundleURLTypes"] as? [[String:Any]] ?? []))

        var pacing = BrowserLaunchPacing()
        for (time, expected) in [(0.0,3.0),(3,9),(9,21),(21,45),(45,93),(93,153)] {
            pacing.attempted(at: time); precondition(pacing.nextAttempt == expected)
        }
        pacing.healthy(at: 154); pacing.healthy(at: 184)
        precondition(pacing.failures == 0)

        // Real exec in one disposable child: start time stays the same while
        // the kernel execution stamp changes. No personal process is signaled.
        let child = Process(), input = Pipe()
        child.executableURL = URL(fileURLWithPath: "/bin/sh")
        child.arguments = ["-c", "read reply; exec /bin/sleep 1"]
        child.standardInput = input
        try child.run()
        defer { if child.isRunning { child.terminate(); child.waitUntilExit() } }
        let before = ProcessIdentity.basic(child.processIdentifier)!
        let stamp = BrowserExecution.read(before, path: "/test")!
        input.fileHandleForWriting.write(Data("go\n".utf8))
        var changed = false
        for _ in 0..<100 {
            if let after = ProcessIdentity.basic(child.processIdentifier), let next = BrowserExecution.read(after, path: "/test"), next != stamp {
                precondition(BrowserIdentity(before) == BrowserIdentity(after)); changed = true; break
            }
            usleep(10_000)
        }
        precondition(changed, "exec must invalidate cached trust")
        child.waitUntilExit()

        // Discover the already-running protected Chrome without a launcher,
        // saved identity, or sending any signals. This is the daemon-update
        // race that previously caused a valid browser to be closed.
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("FocusDeck-browser-check-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let browser = ManagedBrowser(uid: getuid(), record: directory.appendingPathComponent("browser.json"))
        browser.tick(enforce: false, discoverOnly: true)
        guard let pid = browser.pid else { throw NSError(domain: "ManagedBrowserChecks", code: 1, userInfo: [NSLocalizedDescriptionKey: "Existing protected Chrome was not adopted"]) }
        for _ in 0..<6 { browser.tick(enforce: false, discoverOnly: true); precondition(browser.pid == pid); usleep(100_000) }
        print("PASS: URL handler is not a browser; policy overrides denied; bounded relaunch; exec invalidates trust; live Chrome adopted without launcher and remains recognized (PID \(pid))")
    }
}

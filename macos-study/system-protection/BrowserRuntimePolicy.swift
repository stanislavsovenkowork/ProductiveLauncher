import Foundation
import Darwin

// A process may exec a different image without changing PID/start time. Bind
// cached trust to the kernel's executable UUID and pid version, not its parent
// (launchctl/sudo can exit or reparent an otherwise unchanged Chrome).
struct BrowserExecution: Equatable {
    let identity: BrowserIdentity
    let uniqueID: UInt64
    let version: Int32
    let executableUUID: Data
    let path: String

    static func read(_ process: ProcessIdentity, path: String) -> BrowserExecution? {
        // proc_uniqidentifierinfo / PROC_PIDUNIQIDENTIFIERINFO from Darwin's
        // proc_info_private.h. Present since before our macOS 15 minimum.
        var words = [UInt64](repeating: 0, count: 7)
        let count = words.withUnsafeMutableBytes {
            proc_pidinfo(process.pid, 17, 0, $0.baseAddress, Int32($0.count))
        }
        guard count == 56 else { return nil }
        return words.withUnsafeBytes {
            BrowserExecution(identity: BrowserIdentity(process), uniqueID: $0.load(fromByteOffset: 16, as: UInt64.self),
                version: $0.load(fromByteOffset: 32, as: Int32.self), executableUUID: Data($0.prefix(16)), path: path)
        }
    }
}

enum BrowserRuntimePolicy {
    static func claimsWebBrowser(_ types: [[String: Any]]) -> Bool {
        types.contains { type in
            // Many desktop apps (including ChatGPT) handle http/https for
            // deep links. Only a declared document-viewing role is a browser
            // claim; a URL scheme by itself must never authorize termination.
            let role = type["CFBundleTypeRole"] as? String ?? ""
            let schemes = type["CFBundleURLSchemes"] as? [String] ?? []
            return ["Viewer", "Editor"].contains(role) && schemes.contains { ["http", "https"].contains($0.lowercased()) }
        }
    }
    static func permits(arguments: [String], required: [String]) -> Bool {
        required.allSatisfy { expected in
            let name = String(expected.split(separator: "=", maxSplits: 1)[0])
            return arguments.filter { $0 == name || $0.hasPrefix(name + "=") } == [expected]
        } && !arguments.contains {
            $0.hasPrefix("--no-proxy") || $0.hasPrefix("--proxy-pac") ||
            $0.hasPrefix("--remote-debugging") || $0.hasPrefix("--load-extension") ||
            $0 == "--type" || $0.hasPrefix("--type=")
        }
    }
}

struct BrowserLaunchPacing {
    private(set) var nextAttempt = -Double.infinity
    private(set) var failures = 0
    private var healthySince: Double?

    mutating func attempted(at now: Double) {
        healthySince = nil
        failures = min(failures + 1, 6)
        nextAttempt = now + min(60, 3 * pow(2, Double(failures - 1)))
    }
    mutating func healthy(at now: Double) {
        if healthySince == nil { healthySince = now }
        if now - (healthySince ?? now) >= 30 { failures = 0; nextAttempt = now }
    }
}

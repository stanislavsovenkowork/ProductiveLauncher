import Foundation

@main
enum PolicyTests {
    static func main() throws {
        let policy = try JSONDecoder().decode(StudyPolicy.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1])))
        try policy.validate()
        let cases: [(String?, String?, Bool?)] = [
            ("youtube.com", nil, true), ("m.youtube.com", nil, true),
            ("YouTube.COM.", nil, true), (" youtube.com ", nil, true),
            ("youtube.com", "com.openai.codex", false),
            ("youtube.com", "com.hossinasaadi.v2box", false),
            ("youtube.com", "net.openvpn.connect.app", false), ("notyoutube.com", nil, false),
            ("youtube.com.evil.example", nil, false),
            ("gitlab.eskhata.com", nil, false), ("chatgpt.com", nil, false),
            ("cdn.openai.com", nil, false), (nil, nil, nil),
            (nil, "ru.keepcoder.Telegram", true),
            ("example.org", "com.microsoft.Outlook", true),
            ("example.org", nil, false), ("googlevideo.com", nil, true)
        ]
        for (host, bundle, expected) in cases {
            precondition(policy.shouldBlock(host: host, bundleID: bundle) == expected,
                         "Incorrect decision: \(String(describing: host))")
        }
        let infrastructure = StudyPolicy(blockedDomains: [], protectedDomains: [], blockedAppBundleIds: ["com.openai.codex"])
        do { try infrastructure.validate(); preconditionFailure("Protected app accepted") } catch {}
        let malformed = StudyPolicy(blockedDomains: ["https://youtube.com"], protectedDomains: [], blockedAppBundleIds: [])
        do { try malformed.validate(); preconditionFailure("Malformed domain accepted") } catch {}
        precondition(!infrastructure.shouldTerminate(bundleID: "com.openai.codex"))
        precondition(policy.shouldTerminate(bundleID: "ru.keepcoder.Telegram"))
        precondition(!policy.shouldTerminate(bundleID: "ru.keepcoder.Telegram.clone"))
        let work = policy.profile(category: "work")
        precondition(work.shouldBlock(host: "outlook.office.com", bundleID: "com.microsoft.Outlook") == false)
        precondition(work.shouldBlock(host: "web.telegram.org", bundleID: "ru.keepcoder.Telegram") == false)
        precondition(work.shouldBlock(host: "youtube.com", bundleID: nil) == true)
        print("\(cases.count) policy decisions passed; missing hostname stays explicitly uncovered.")
    }
}

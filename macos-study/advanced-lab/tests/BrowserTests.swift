import Foundation
@main @MainActor enum BrowserTests {
    static func main() {
        let policy = StudyPolicy(blockedDomains: ["youtube.com"], protectedDomains: ["openai.com"], blockedAppBundleIds: [])
        precondition(BrowserControl.blocks(url: "https://m.youtube.com/watch?v=x", policy: policy))
        precondition(!BrowserControl.blocks(url: "https://notyoutube.com/", policy: policy))
        precondition(!BrowserControl.blocks(url: "https://youtube.com.evil.example/", policy: policy))
        precondition(!BrowserControl.blocks(url: "https://openai.com/", policy: policy))
        precondition(!BrowserControl.blocks(url: "file:///youtube.com", policy: policy))
        precondition(BrowserControl.quote("a\"b\\c") == "\"a\\\"b\\\\c\"")
        let control = BrowserControl(); control.poll(policy: policy, enabled: false, active: true)
        precondition(control.status == "Контроль вкладок выключен")
        print("Browser URL boundary/escaping/default-off tests passed. No AppleEvents sent.")
    }
}

import Foundation

@main
@MainActor
enum HostMain {
    static func main() async throws {
        let args = CommandLine.arguments
        let controller = HostController(preferences: AppleFilterPreferences())
        if args.count == 2, args[1] == "--recover" {
            try await controller.recover()
            print("Removed only FocusDeck's own filter configuration; extension remains registered.")
            return
        }
        guard args.count == 4, ["--plan", "--activate-lab"].contains(args[1]),
              let minutes = Double(args[3]), minutes.isFinite, minutes > 0, minutes <= 25 else {
            throw HostController.HostError.invalidSession
        }
        let data = try Data(contentsOf: URL(fileURLWithPath: args[2]))
        let policy = try JSONDecoder().decode(StudyPolicy.self, from: data)
        try policy.validate()
        let sessionID = UUID()
        if args[1] == "--plan" {
            let plan: [String: Any] = ["sessionId": sessionID.uuidString, "minutes": minutes,
                "extensionId": HostController.extensionID, "protected": false,
                "activation": "not performed", "policyValidated": true]
            print(String(decoding: try JSONSerialization.data(withJSONObject: plan, options: [.sortedKeys]), as: UTF8.self))
            return
        }
        // This command is deliberately laboratory-only. Strict capability stays false.
        guard Bundle.main.bundleIdentifier == "dev.stanislav.focusdeck.study" else {
            throw HostController.HostError.unsignedContainer
        }
        let activation = ExtensionActivation()
        try await activation.activate()
        try await controller.start(policy: policy, minutes: minutes, sessionID: sessionID, category: "study")
        print("Partial lab filter configured. No claim of protected hostname/app coverage.")
        do {
            try await Task.sleep(for: .seconds(minutes * 60))
        } catch {
            try await controller.recover(sessionID: sessionID)
            throw error
        }
        try await controller.recover(sessionID: sessionID)
        // If killed/crashed, provider's frozen deadline still fails open; --recover removes prefs.
    }
}

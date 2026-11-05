import Foundation
@main @MainActor enum BrowserWorker {
    static func main() throws {
        guard (2...3).contains(CommandLine.arguments.count) else { return }
        let policy = try JSONDecoder().decode(StudyPolicy.self, from: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))).profile(category: CommandLine.arguments.count == 3 ? CommandLine.arguments[2] : "study")
        try policy.validate()
        let worker = BrowserControl(); worker.start(); worker.poll(policy: policy, enabled: true, active: true)
        print(worker.status)
    }
}

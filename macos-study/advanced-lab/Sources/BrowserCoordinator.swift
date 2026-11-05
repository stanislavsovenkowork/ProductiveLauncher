import Foundation

@MainActor
final class BrowserCoordinator {
    private(set) var status = "Контроль вкладок выключен"
    private var deadline: TimeInterval = 0
    private var pauseGranted = false
    private var busy = false
    private var child: Process?
    private let workerOverride: URL?
    private let policyOverride: URL?
    private let timeout: Double
    init(worker: URL? = nil, policy: URL? = nil, timeout: Double = 5) {
        workerOverride = worker; policyOverride = policy; self.timeout = min(5, max(0.05, timeout))
    }
    func start() { deadline = ProcessInfo.processInfo.systemUptime + 1500; pauseGranted = false }
    func grantPause() { if !pauseGranted { deadline += 300; pauseGranted = true }; cancelPoll() }
    func cancelPoll() { if let child, child.isRunning { child.terminate() }; child = nil }
    func stop() { cancelPoll(); deadline = 0; status = "Контроль вкладок выключен" }
    func poll(enabled: Bool, active: Bool, category: String = "study") async {
        guard enabled, active else { cancelPoll(); status = "Контроль вкладок выключен"; return }
        guard ProcessInfo.processInfo.systemUptime < deadline else { cancelPoll(); status = "Пробный контроль окончен · предел 25 мин"; return }
        guard !busy else { return }; busy = true; defer { busy = false }
        guard let executable = workerOverride ?? Bundle.main.url(forResource: "BrowserWorker", withExtension: nil),
              let policy = policyOverride ?? Bundle.main.url(forResource: "study", withExtension: "json") else {
            status = "Компонент контроля вкладок недоступен"; return
        }
        let process = Process(); process.executableURL = executable; process.arguments = [policy.path, category]
        let output = Pipe(); process.standardOutput = output; process.standardError = FileHandle.nullDevice
        do {
            try process.run(); child = process
            let limitSeconds = timeout
            let message: String = await Task.detached {
                let limit = ProcessInfo.processInfo.systemUptime + limitSeconds
                while process.isRunning && ProcessInfo.processInfo.systemUptime < limit { try? await Task.sleep(for: .milliseconds(50)) }
                if process.isRunning { process.terminate() }
                let killLimit = ProcessInfo.processInfo.systemUptime + 0.2
                while process.isRunning && ProcessInfo.processInfo.systemUptime < killLimit { try? await Task.sleep(for: .milliseconds(20)) }
                if process.isRunning { _ = kill(process.processIdentifier, SIGKILL) }
                process.waitUntilExit()
                let data = output.fileHandleForReading.readDataToEndOfFile()
                guard process.terminationStatus == 0 else { return "Браузер не ответил вовремя · защита неполная" }
                return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "Контроль вкладок не подтверждён"
            }.value
            if child === process { child = nil; status = message }
        } catch { child = nil; status = "Не удалось запустить контроль вкладок" }
    }
}

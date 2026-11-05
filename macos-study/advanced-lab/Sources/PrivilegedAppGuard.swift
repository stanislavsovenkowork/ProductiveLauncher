import Foundation
import Darwin

@MainActor
final class PrivilegedAppGuard {
    private(set) var status = "Root-помощник не подтверждён · ограничения не заявлены"
    private(set) var observedDaemonHealthy = false
    private(set) var observedPolicyState = "unavailable"
    private var livePolicySessionId: UUID?
    private var livePolicyExpiry: Date?
    private(set) var observedAppsActive = false
    private(set) var observedHostsActive = false
    private(set) var pauseUntil: Date?
    private var verifiedServerPID: Int32?
    private(set) var expiresAt: Date?
    static func shellQuote(_ value: String) -> String { "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'" }
    static func appleQuote(_ value: String) -> String { "\"" + value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"") + "\"" }
    func administratorAction(_ action: String, isolated: Bool) async -> String {
        guard !isolated else { return "Изолированная проверка: системные изменения отключены" }
        guard ["--install-idle", "--recover", "--remove"].contains(action),
              let root = Bundle.main.resourceURL?.appendingPathComponent("RootGuard"),
              let ui = Bundle.main.executableURL else { return "Компонент установки отсутствует" }
        var command = "/bin/sh " + Self.shellQuote(root.appendingPathComponent("install-root-guard.sh").path) + " " + action
        if action == "--install-idle" {
            guard let runtimeHash = RootSocketServer.verifiedHash(pid: getpid()) else { return "Не удалось подтвердить код текущей сборки; перезапустите приложение" }
            command += " \(getuid()) " + Self.shellQuote(ui.path) + " " + Self.shellQuote(runtimeHash)
        }
        let script = "do shell script " + Self.appleQuote(command) + " with administrator privileges"
        return await Task.detached {
            let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript"); process.arguments = ["-e", script]
            let output = Pipe(); let errors = Pipe(); process.standardOutput = output; process.standardError = errors
            do {
                try process.run()
                let data = output.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
                guard process.terminationStatus == 0 else { return "Изменение не подтверждено: разрешение отменено, уже есть установка или проверка безопасности не пройдена. История сохранена." }
                return String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "Действие завершено; проверьте состояние помощника"
            } catch { return "Не удалось открыть системное подтверждение. Пароль вводится только в окне macOS, не в чате." }
        }.value
    }
    func request(command: String, sessionId: UUID, hosts: Bool = false, category: String = "study", isolated: Bool) async -> Bool {
        guard !isolated else { status = "Изолированная проверка: root-команды отключены"; return false }
        let request = RootRequest(schemaVersion: 1, command: command, sessionId: sessionId,
                                  seconds: command == "pause" ? 300 : 1500, hostsEnabled: hosts, category: category)
        do {
            let reply = try await Task.detached { try RootSocketClient.send(request) }.value
            status = reply.accepted ? "Запрос принят; ожидается наблюдение применённой политики" : "Ограничение не применено: \(reply.reason)"
            verifiedServerPID = reply.verifiedPeerPID
            expiresAt = reply.expiresAt; pauseUntil = reply.pauseUntil
            return reply.accepted
        } catch { status = "Связь с root-помощником недоступна; защита не заявлена"; return false }
    }
    func observe(expectedSession: UUID?, expectedTargets: [String], expectedDomains: [String], isolated: Bool) async {
        guard !isolated else { observedDaemonHealthy = false; observedAppsActive = false; observedHostsActive = false; return }
        do {
            let request = RootRequest(schemaVersion: 1, command: "status", sessionId: expectedSession ?? UUID(), seconds: nil, hostsEnabled: nil)
            let reply = try await Task.detached { try RootSocketClient.send(request) }.value
            guard reply.accepted else { throw RootSocketFailure.invalidResponse }
            verifiedServerPID = reply.verifiedPeerPID
            observedPolicyState = reply.policyState ?? "unavailable"; livePolicySessionId = reply.policySessionId; livePolicyExpiry = reply.expiresAt
            refresh(expectedSession: expectedSession, expectedTargets: expectedTargets, expectedDomains: expectedDomains, isolated: false)
        } catch { observedDaemonHealthy = false; observedPolicyState = "unavailable"; observedAppsActive = false; observedHostsActive = false; status = "Kernel-связь с root-помощником не подтверждена; защита не заявлена" }
    }
    func refresh(expectedSession: UUID?, expectedTargets: [String], expectedDomains: [String], isolated: Bool) {
        observedDaemonHealthy = false; observedAppsActive = false; observedHostsActive = false
        status = "Свежий статус root-помощника не подтверждён; защита не заявлена"
        guard !isolated else { return }
        let path = "/var/run/dev.stanislav.focusdeck.guard.status.json"
        let fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC)
        guard fd >= 0 else { return }; defer { close(fd) }
        var info = stat()
        guard fstat(fd, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFREG,
              info.st_mode & 0o022 == 0, info.st_size <= 64 * 1024 else { return }
        let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: false)
        guard let data = try? handle.readToEnd(),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["service"] as? String == "dev.stanislav.focusdeck.guard",
              let stamp = root["updatedAt"] as? String, let updated = ISO8601DateFormatter().date(from: stamp),
              abs(Date().timeIntervalSince(updated)) < 3, root["pid"] as? Int32 == verifiedServerPID else { return }
        observedDaemonHealthy = true
        guard let rawSession = root["sessionId"] as? String else { status = "Root-помощник работает без активной политики"; return }
        guard let expectedSession, UUID(uuidString: rawSession) == expectedSession, livePolicySessionId == expectedSession,
              root["targetUID"] as? UInt32 == getuid(), root["blockedSigningIDs"] as? [String] == expectedTargets, root["blockedDomains"] as? [String] == expectedDomains,
              let end = root["expiresAt"] as? String, let snapshotExpiry = ISO8601DateFormatter().date(from: end) else { status = "Наблюдается другая/неподтверждённая политика; защита текущего сеанса не заявлена"; return }
        let expiry = livePolicyExpiry ?? snapshotExpiry
        expiresAt = expiry
        guard expiry > Date(), observedPolicyState != "expired" else { status = "Root-политика истекла; помощник доступен, учёт времени может продолжаться без неё"; return }
        observedAppsActive = observedPolicyState == "enforcing"
        observedHostsActive = observedAppsActive && root["hostsApplied"] as? Bool == true
        status = observedAppsActive ? "Наблюдается root-политика приложений · до \(expiry.formatted(date: .omitted, time: .shortened)); домены: \(observedHostsActive ? "точные системные DNS-имена" : "не подтверждены")" : "Root-политика на паузе; работа сама не возобновляется"
    }
}

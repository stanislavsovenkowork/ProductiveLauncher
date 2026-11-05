import Foundation
import Darwin
import ServiceManagement
import SwiftUI

// Candidate-only integration surface. A registration is never automatic.
@MainActor protocol CalendarServiceManaging {
    var state: CalendarServiceState { get }
    func register() throws
    func unregister() throws
}
enum CalendarServiceState: Equatable { case absent, approvalRequired, enabled, missing, unknown }
@MainActor struct CalendarSystemService: CalendarServiceManaging {
    let plistName: String
    private var service: SMAppService {
        #if USER_CALENDAR_FIXTURE || USER_GUARD_MAIN
        .agent(plistName: plistName)
        #else
        .daemon(plistName: plistName)
        #endif
    }
    var state: CalendarServiceState {
        switch service.status {
        case .notRegistered: return .absent
        case .requiresApproval: return .approvalRequired
        case .enabled: return .enabled
        case .notFound: return .missing
        @unknown default: return .unknown
        }
    }
    func register() throws { try service.register() }
    func unregister() throws { try service.unregister() }
}
@MainActor final class CalendarServiceControl: ObservableObject {
    @Published private(set) var state: CalendarServiceState
    @Published private(set) var message = "Служба сама не включается; действие правила ещё не подтверждено."
    private let service: any CalendarServiceManaging
    init(service: any CalendarServiceManaging) { self.service = service; state = service.state }
    func refresh() { state = service.state }
    func register() {
        do { try service.register(); message = "Запрос отправлен. Проверьте системное подтверждение и состояние." }
        catch { message = "Включение не подтверждено: \(error.localizedDescription)" }
        refresh()
    }
    func unregister() {
        do { try service.unregister(); message = "Запрос удаления отправлен. Проверьте состояние и завершение службы." }
        catch { message = "Отключение не подтверждено: \(error.localizedDescription)" }
        refresh()
    }
}

#if USER_GUARD_MAIN && !USER_GUARD_QA
struct ManualAgentReply: Decodable, Sendable {
    let ok: Bool
    let state: String
    let reason: String
}
@MainActor final class ManualAgentControl: ObservableObject {
    typealias Runner = @MainActor (String) async -> ManualAgentReply?
    @Published private(set) var state = "pending"
    @Published private(set) var message = "Проверяем пользовательскую службу…"
    @Published private(set) var busy = false
    private let runner: Runner
    init(runner: Runner? = nil) { self.runner = runner ?? Self.runBundled }
    private static func runBundled(_ command: String) async -> ManualAgentReply? {
        let binary = Bundle.main.bundleURL.appendingPathComponent("Contents/Library/HelperTools/FocusDeckUserAgentManager")
        return await Task.detached { () -> ManualAgentReply? in
            let p = Process(); p.executableURL = binary; p.arguments = [command]
            let output = Pipe(); p.standardOutput = output; p.standardError = FileHandle.nullDevice
            do {
                try p.run(); let data = output.fileHandleForReading.readDataToEndOfFile(); p.waitUntilExit()
                guard data.count <= 8192, let reply = try? JSONDecoder().decode(ManualAgentReply.self, from: data),
                      reply.ok == (p.terminationStatus == 0) else { return nil }
                return reply
            } catch { return nil }
        }.value
    }
    private func set(_ reply: ManualAgentReply?) {
        guard let reply else { state = "unknown"; message = "Состояние пользовательской службы неизвестно."; return }
        state = reply.ok && ["absent", "ready", "active"].contains(reply.state) ? reply.state : "unknown"
        let messages = [
            "not_installed": "Пользовательская служба не установлена.",
            "connected": "Пользовательская служба ответила; действие на цели проверяется отдельно.",
            "schedule_enabled": "Служба ответила; будущее расписание сохранено.",
            "installed": "Служба включена; действие на цели ещё не подтверждено.",
            "updated": "Служба обновлена; контекст и расписание сохранены. Повторно подключите мост Chrome.",
            "uninstalled_ledger_retained": "Служба отключена; окно, бюджет и расписание сохранены.",
            "window_active": "Окно ещё действует; подтвердите полный выход. Если служба недоступна, требуется восстановление её состояния.",
            "schedule_boundary": "Начало следующего окна близко; действие не выполнено. Проверьте расписание и состояние службы.",
            "schedule_would_start": "Сохранённое окно по расписанию уже должно было начаться; служба не запущена, требуется восстановление.",
            "clock_unknown": "Не удалось надёжно проверить время расписания; служба не запущена, требуется восстановление.",
            "ledger_changed": "Сохранённое состояние изменилось во время проверки; действие не подтверждено.",
            "chrome_host_registered": "Сначала удалите старую регистрацию моста Chrome, после обновления подключите новую.",
            "chrome_pin_mismatch": "Мост и агент этой сборки не совпадают; изменение не выполнено.",
            "service_conflict": "Обнаружена другая регистрация или занятый сокет; изменение не выполнено.",
            "new_bundle_required": "Для обновления нужна новая сборка в отдельном месте; прежнюю сохраните для отката.",
            "ownership_mismatch": "Не удалось подтвердить принадлежность службы; изменение не выполнено.",
            "agent_unknown": "Связь со службой неизвестна; обычное изменение недоступно.",
            "recovery_required": "Состояние после ошибки неизвестно; требуется восстановление.",
            "update_rolled_back": "Обновление не удалось; прежняя служба подтверждённо восстановлена."
        ]
        let label = ["absent": "Не установлена", "ready": "Связь подтверждена", "active": "Есть окно", "unknown": "Состояние неизвестно"][state] ?? "Состояние неизвестно"
        message = label + ". " + (messages[reply.reason] ?? "Действие или состояние не подтверждено (\(reply.reason)).")
    }
    func refresh() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        set(await runner("status"))
    }
    func perform(_ command: String, sm: CalendarServiceControl) async {
        guard !busy, ["install", "update", "uninstall"].contains(command), state != "pending" else { return }
        busy = true; defer { busy = false }
        // notFound remains unknown. The user explicitly operates the separate
        // manual backend; its manager checks live jobs, socket, ledger and peer.
        func mayManage(_ state: CalendarServiceState) -> Bool {
            state == .absent || state == .missing
        }
        sm.refresh()
        guard mayManage(sm.state) else {
            state = "unknown"
            message = sm.state == .missing || sm.state == .unknown
                ? "Не удалось определить состояние службы SM; обычное изменение пользовательской службы недоступно."
                : "Служба SM зарегистрирована; сначала отключите её, чтобы службы не работали одновременно."
            return
        }
        let current = await runner("status"); set(current)
        guard let current, current.ok,
              (command == "install" ? current.state == "absent" : current.state == "ready") else {
            if current?.state == "active" { message = "Есть действующее или ожидающее окно; сначала завершите его." }
            return
        }
        sm.refresh()
        guard mayManage(sm.state) else { state = "unknown"; message = "Состояние SM изменилось или не подтверждено; действие не выполнено."; return }
        let result = await runner(command); set(result)
        if result?.ok == true {
            let completedMessage = message
            let after = await runner("status"); set(after)
            if after?.ok == true { message = completedMessage + " " + message }
            else { message = completedMessage + " Последующее состояние службы не подтверждено." }
        }
    }
}
#endif

#if FOCUSDECK_SM_FIXTURE || USER_GUARD_QA
extension CalendarServiceControl {
    func clearFixtureData() {
        do {
            let fd = socket(AF_UNIX, SOCK_STREAM, 0); guard fd >= 0 else { throw RootSocketFailure.systemCall }; defer { close(fd) }
            #if USER_GUARD_QA
            let socketPath = "/tmp/focusdeck-user-protection-qa-\(getuid()).sock"
            let expectedUID = getuid()
            #elseif USER_CALENDAR_FIXTURE
            let socketPath = "/tmp/focusdeck-user-calendar-\(getuid()).sock"
            let expectedUID = getuid()
            #else
            let socketPath = "/var/run/dev.stanislav.focusdeck.calendarfixture.sock"
            let expectedUID: uid_t = 0
            #endif
            var address = try guardAddress(socketPath)
            let rc = withUnsafePointer(to: &address) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) } }
            var uid: uid_t = 1, gid: gid_t = 0
            guard rc == 0, getpeereid(fd, &uid, &gid) == 0, uid == expectedUID else { throw RootSocketFailure.systemCall }
            try writeRootFrame(Data("clear-test-data".utf8), fd: fd, timeout: 2)
            let response = try readRootFrame(fd, timeout: 2)
            guard response == Data("cleared".utf8) else { message = "Удаление не выполнено: завершите окно и проверьте содержимое тестовой папки."; return }
            message = "Тестовые данные удалены. Теперь отключите службу и проверьте её состояние."
        } catch { message = "Удаление тестовых данных не подтверждено; проверьте службу." }
    }
}
#endif

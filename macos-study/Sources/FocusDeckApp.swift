import SwiftUI
#if FOCUSDECK_SM_FIXTURE || USER_GUARD_MAIN
import ServiceManagement
#endif
import AppKit
import Darwin
import UniformTypeIdentifiers

#if USER_GUARD_MAIN
struct UserEnrollmentPending: Codable {
    let policy: WindowPolicy
    let schedule: WindowSchedule
    let requirements: [TrustedSigningRequirement]
}
#endif

@MainActor
final class SessionModel: ObservableObject {
    static func reliefBalanceText(seconds: Int) -> String {
        let seconds = max(0, seconds)
        if seconds < 60 { return "\(seconds) сек" }
        if seconds.isMultiple(of: 60) { return "\(seconds / 60) мин" }
        return "\(seconds / 60) мин \(seconds % 60) сек"
    }
    @Published var taskChoices: [ObsidianTaskChoice] = []
    @Published var choosingTask = false
    @Published var taskBindingStatus = ""
    private var boundTitle: String?
    private var selectedNoteURL: URL?
    @Published var taskSourceStatus = ""
    private var taskReadAt = Date.distantPast
    var canCompleteLinkedTask: Bool { !storageBlocked && !state.taskCompleted && !state.taskRef.isEmpty && state.taskNotePath != nil }
    @Published var windowPolicy: WindowPolicy?
    private let fixtureBridge: WindowFixtureBridge
    private let allowIsolatedFixtureBridge: Bool
    private let candidateBridge: WindowCandidateBridge
    private let allowIsolatedCandidateBridge: Bool
    @Published var selectedProtectionTargets: [ProtectionTarget] = []
    @Published var selectingProtectionStatus = ""
    @Published var confirmProtectionSetup = false
    @Published var candidateStatus = "Помощник не подключён или связь неизвестна; защита не подтверждена."
    @Published var candidateBusy = false
    @Published var candidateAuthoritative = false
    @Published var trustProposalStatus = ""
    var trustProposalURL: URL { store.directory.appendingPathComponent("candidate-trust-proposal.json") }
    #if USER_GUARD_MAIN
    var userPendingURL: URL { store.directory.appendingPathComponent("user-protection-pending.json") }
    private var userPending: UserEnrollmentPending?
    private var userServiceGeneration: UInt = 0
    @Published var removalStatusReady = false
    @Published var removalRecoveryAvailable = false
    private var removalPolicy: WindowPolicy?
    var canRetryUserEnrollment: Bool { userPending != nil }
    func userProtectionServiceDisconnected() { userServiceGeneration &+= 1; candidateAuthoritative = false; removalStatusReady = false; removalRecoveryAvailable = false; removalPolicy = nil; candidateStatus = "Служба отключена или связь неизвестна; защита не подтверждена." }
    private func removalBlocked(_ policy: WindowPolicy?) -> Bool {
        guard let policy else { return false }
        if policy.state == .inactive && Date() >= policy.startsAt && Date() < policy.endsAt { return true }
        return [.active, .limitedRelief, .rest, .exitPending].contains(policy.state)
    }
    var canUnregisterUserService: Bool {
        removalStatusReady && !candidateBusy && !removalBlocked(removalPolicy)
    }
    var canRemoveChromeHost: Bool {
        removalStatusReady && !candidateBusy && (!removalBlocked(removalPolicy) || (removalPolicy?.siteDomains ?? []).isEmpty)
    }
    private func removalProbe() -> WindowPolicy {
        windowPolicy ?? WindowPolicy(windowId: UUID(), localDate: "status", startsAt: Date(), endsAt: Date().addingTimeInterval(60), reliefBudgetSeconds: 0, reliefRemainingSeconds: 0, reliefMaxGrantSeconds: 1, targetRefs: [])
    }
    private func acceptRemovalStatus(_ reply: WindowGuardReply?) -> Bool {
        guard let reply, reply.accepted, reply.scheduleStatus != "clock_unknown", ["selected_apps_requested", "released"].contains(reply.enforcement),
              (reply.policy == nil && reply.windowId == nil) || (reply.policy != nil && reply.windowId == reply.policy?.windowId),
              reply.policy.map({ $0.policyVersion == 2 && $0.policyRevisionId != nil && $0.startsAt < $0.endsAt && !($0.siteSubdomains == true && $0.siteDomains == nil) && SiteDomainPolicy.validList($0.siteDomains, includeSubdomains: $0.siteSubdomains == true) }) ?? true else {
            removalStatusReady = false; removalRecoveryAvailable = true
            candidateStatus = "Не удалось проверить службу. Обычное отключение недоступно; доступно отдельное восстановление без связи."
            return false
        }
        removalPolicy = reply.policy; removalStatusReady = true; removalRecoveryAvailable = false
        if windowPolicy == nil, let remote = reply.policy, userPending == nil {
            windowPolicy = remote
            do { try windowStore.save(remote); candidateAuthoritative = true; describeCandidate(reply) }
            catch { candidateAuthoritative = false; candidateStatus = "Служба ответила, но локальное восстановление не сохранено." }
        }
        return true
    }
    func refreshRemovalStatus() async {
        guard !candidateBusy else { return }; candidateBusy = true; defer { candidateBusy = false }
        let generation = userServiceGeneration
        let reply = await candidateBridge.request(removalProbe(), command: "status")
        guard generation == userServiceGeneration else { return }
        _ = acceptRemovalStatus(reply)
    }
    func withVerifiedRemoval(siteOnly: Bool, action: @MainActor () -> Void) async {
        guard removalStatusReady, !candidateBusy else { return }
        candidateBusy = true; defer { candidateBusy = false }
        let generation = userServiceGeneration
        let reply = await candidateBridge.request(removalProbe(), command: "status")
        guard generation == userServiceGeneration, acceptRemovalStatus(reply) else { return }
        if removalBlocked(removalPolicy) && (!siteOnly || !(removalPolicy?.siteDomains ?? []).isEmpty) {
            candidateStatus = "Окно ещё действует. Сначала запросите и подтвердите полный выход."; return
        }
        action()
    }
    func unregisterUserService(_ service: CalendarServiceControl) async {
        await withVerifiedRemoval(siteOnly: false) { userProtectionServiceDisconnected(); service.unregister() }
    }
    func recoverUserService(_ service: CalendarServiceControl) {
        guard removalRecoveryAvailable, !candidateBusy else { return }
        userProtectionServiceDisconnected(); service.unregister()
    }
    #if USER_GUARD_QA
    func clearUserFixtureData(_ service: CalendarServiceControl) async {
        await withVerifiedRemoval(siteOnly: false) { userProtectionServiceDisconnected(); service.clearFixtureData() }
    }
    #endif
    #endif
    func prepareTrustProposal(_ policy: WindowPolicy) throws {
        guard let hash = RootSocketServer.verifiedHash(pid: getpid()) else { throw WindowPolicy.Failure.invalid }
        let trust = RootTrust(schemaVersion: 1, allowedUID: getuid(), uiCDHash: hash, blockedSigningIDs: policy.targetRefs,
                              blockedDomains: [], protectedDomains: ["gitlab.eskhata.com", "openai.com", "chatgpt.com"])
        try trust.validate(); try store.prepare()
        let encoder = JSONEncoder()
        var proposal = try JSONSerialization.jsonObject(with: encoder.encode(trust)) as! [String: Any]
        let policyEncoder = JSONEncoder(); policyEncoder.dateEncodingStrategy = .iso8601
        proposal["approvedWindow"] = try JSONSerialization.jsonObject(with: policyEncoder.encode(policy))
        proposal["proposalStatus"] = "local_uninstalled"
        if let revision = policy.policyRevisionId { proposal["policyRevisionId"] = revision.uuidString }
        proposal["signingRequirements"] = selectedProtectionTargets.compactMap { target -> [String: String]? in
            guard let team = target.teamID, let requirement = target.requirement else { return nil }
            return ["signingID": target.id, "teamID": team, "requirement": requirement]
        }
        proposal["targetApplications"] = selectedProtectionTargets.compactMap { target -> [String: String]? in
            guard let path = target.path else { return nil }; return ["signingID": target.id, "path": path]
        }
        try JSONSerialization.data(withJSONObject: proposal, options: [.prettyPrinted, .sortedKeys]).write(to: trustProposalURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: trustProposalURL.path)
    }
    func revealTrustProposal() { NSWorkspace.shared.activateFileViewerSelecting([trustProposalURL]) }
    func chooseProtectionTargets() {
        #if USER_GUARD_QA
        selectedProtectionTargets = [ProtectionTarget(id: "dev.stanislav.focusdeck.guard-fixture", name: "Синтетическая цель", teamID: "synthetic", requirement: "3e481e3d08c534a55081e55313fa2768f44d57bc")]
        windowTargets = "dev.stanislav.focusdeck.guard-fixture"; selectingProtectionStatus = "Выбрана только синтетическая цель."; return
        #endif
        guard windowPolicy == nil || [.exit, .expired].contains(windowPolicy!.state) else { return }
        let panel = NSOpenPanel(); panel.canChooseFiles = true; panel.canChooseDirectories = false; panel.allowsMultipleSelection = true
        panel.allowedContentTypes = [.applicationBundle]
        panel.message = "Выберите приложения. Браузер ограничивается целиком; сайты отдельно не фильтруются."
        guard panel.runModal() == .OK else { return }
        do {
            let targets = try panel.urls.map { try ProtectionTarget.inspect($0) }
            guard !targets.isEmpty, Set(targets.map(\.id)).count == targets.count else { selectingProtectionStatus = "Выберите разные подписанные приложения."; return }
            selectedProtectionTargets = targets; windowTargets = targets.map(\.id).joined(separator: ", "); selectingProtectionStatus = "Подписи проверены; список ещё не сохранён."
        } catch ProtectionTarget.Failure.protected { selectingProtectionStatus = "Эта цель защищена от блокировки. Системные приложения, включая Safari, а также выбранная сетевая и рабочая инфраструктура исключены." }
        catch ProtectionTarget.Failure.untrustedTeam { selectingProtectionStatus = "У приложения нет проверяемого Team ID разработчика; ad-hoc подписи пока не поддерживаются." }
        catch { selectingProtectionStatus = "Приложение не выбрано: подпись не прошла проверку или ID подписи не совпадает с ID приложения." }
    }
    @Published var fixtureAcknowledgedWindow: UUID?
    @Published var fixtureAcknowledgedState: WindowPolicy.State?
    @Published var fixtureBridgeStatus = "Тестовый помощник не подключён; защита не подтверждена."
    func isLegacyFixture(_ policy: WindowPolicy) -> Bool {
        #if USER_GUARD_MAIN
        return false
        #else
        return policy.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"]
        #endif
    }
    var fixtureNeedsAcknowledgement: Bool {
        #if USER_CALENDAR_FIXTURE
        return windowPolicy?.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"] && (fixtureAcknowledgedWindow != windowPolicy?.windowId || fixtureAcknowledgedState != windowPolicy?.state)
        #else
        return false
        #endif
    }
    @Published var windowStorageBlocked = false
    @Published var windowSchedule: WindowSchedule?
    @Published var recurringWeekdays: Set<Int> = [1, 2, 3, 4, 5]
    @Published var windowMessage = "Защита не подключена: помощник не установлен или не проверен."
    @Published var windowStart = Date().addingTimeInterval(3600)
    @Published var windowEnd = Date().addingTimeInterval(7200)
    @Published var windowTargets = ""
    @Published var windowSites = ""
    var selectedSiteDomains: [String]? { try? SiteDomainPolicy.parse(windowSites) }
    var canSaveProtectionSetup: Bool {
        guard let domains = selectedSiteDomains else { return false }
        return !selectedProtectionTargets.isEmpty || !domains.isEmpty
    }
    #if USER_GUARD_MAIN && !USER_GUARD_QA
    func refreshUserProtection(manual: ManualAgentControl, sm: CalendarServiceControl) async {
        sm.refresh(); await manual.refresh(); await syncCandidate()
    }
    @Published var chromeSiteStatus = "Компонент Chrome не проверен."
    @Published var chromeSiteBusy = false
    var chromeExtensionURL: URL { Bundle.main.bundleURL.appendingPathComponent("Contents/Resources/FocusDeckChrome", isDirectory: true) }
    func revealChromeExtension() { NSWorkspace.shared.activateFileViewerSelecting([chromeExtensionURL]) }
    func manageChromeHost(install: Bool, recovery: Bool = false) {
        guard !chromeSiteBusy else { return }
        if !install {
            if recovery { guard removalRecoveryAvailable, !candidateBusy else { return }; runChromeHost(install: false); return }
            Task { await withVerifiedRemoval(siteOnly: true) { runChromeHost(install: false) } }
            return
        }
        runChromeHost(install: true)
    }
    private func runChromeHost(install: Bool) {
        chromeSiteBusy = true
        let executable = Bundle.main.bundleURL.appendingPathComponent("Contents/Library/HelperTools/FocusDeckSiteHost")
        Task {
            let ok = await Task.detached { () -> Bool in
                let process = Process(); process.executableURL = executable; process.arguments = [install ? "--install" : "--uninstall"]
                process.standardOutput = FileHandle.nullDevice; process.standardError = FileHandle.nullDevice
                do { try process.run(); process.waitUntilExit(); return process.terminationStatus == 0 }
                catch { return false }
            }.value
            chromeSiteBusy = false
            chromeSiteStatus = ok ? (install ? "Мост зарегистрирован. Загрузите расширение в Chrome и проверьте его значок; действие на сайты пока не подтверждено." : "Мост удалён. Отключите или удалите расширение в Chrome отдельно.") : "Действие не завершено. Проверьте права и существующую регистрацию другой сборки."
        }
    }
    #endif
    @Published var windowBudgetMinutes = 5
    @Published var windowGrantMinutes = 5
    @Published var exitCoolingOffSeconds = 15
    @Published var confirmWindowExit = false
    private var windowStore: WindowPolicyStore { WindowPolicyStore(directory: store.directory) }
    #if FOCUSDECK_SM_FIXTURE
    func startCalendarFixture() {
        guard windowPolicy == nil else { windowMessage = "Тестовое окно уже создано; сохранённый дневной бюджет не сбрасывается."; return }
        let now = Date(); var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
        let parts = cal.dateComponents([.year, .month, .day], from: now)
        let day = String(format: "%04d-%02d-%02d", parts.year!, parts.month!, parts.day!)
        var p = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: day, startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(150), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"], state: .active)
        p.exitCoolingOffSeconds = 5
        #if USER_CALENDAR_FIXTURE
        Task { await startUserFixture(p) }
        #else
        do { try windowStore.save(p, event: "configured", now: now); windowPolicy = p; windowMessage = "Только синтетический тест, окно не более 150 секунд."; Task { await syncFixture() } }
        catch { windowMessage = "Тестовое окно не сохранено: \(error.localizedDescription)" }
        #endif
    }
    #endif
    func saveWindow() {
        guard let sites = selectedSiteDomains, !selectedProtectionTargets.isEmpty || !sites.isEmpty else { windowMessage = "Выберите приложения или укажите корректные домены сайтов."; return }
        guard !windowStorageBlocked else { return }
        guard windowPolicy == nil || [.exit, .expired].contains(windowPolicy!.state) else { windowMessage = "Текущее окно уже сохранено; сначала явно завершите его."; return }
        let targets = windowTargets.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        do {
            #if USER_GUARD_QA
            let now = Date(); var qaCalendar = Calendar(identifier: .gregorian); qaCalendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
            let dayParts = qaCalendar.dateComponents([.year, .month, .day], from: now)
            let qaDay = String(format: "%04d-%02d-%02d", dayParts.year!, dayParts.month!, dayParts.day!)
            var policy = WindowPolicy(policyRevisionId: UUID(), windowId: UUID(), localDate: qaDay, startsAt: now.addingTimeInterval(-1), endsAt: now.addingTimeInterval(150), reliefBudgetSeconds: 30, reliefRemainingSeconds: 30, reliefMaxGrantSeconds: 30, targetRefs: targets, state: .active)
            policy.exitCoolingOffSeconds = 5
            #else
            var policy = try WindowPolicy.create(start: windowStart, end: windowEnd, targets: targets, sites: sites, budget: windowBudgetMinutes * 60, maxGrant: windowGrantMinutes * 60, now: Date())
            policy.exitCoolingOffSeconds = exitCoolingOffSeconds
            #endif
            if let existing = windowPolicy, policy.localDate <= existing.localDate { windowMessage = "Новое окно можно настроить только на следующий день: бюджет этого дня не сбрасывается."; return }
            var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Europe/Minsk")!
            let startParts = cal.dateComponents([.hour, .minute], from: policy.startsAt)
            let endParts = cal.dateComponents([.hour, .minute], from: policy.endsAt)
            let startWeekday = ((cal.component(.weekday, from: policy.startsAt) + 5) % 7) + 1
            #if !USER_GUARD_QA
            guard recurringWeekdays.contains(startWeekday) else { windowMessage = "День первого окна должен быть выбран в расписании."; return }
            #endif
            var schedule = WindowSchedule(policyRevisionId: policy.policyRevisionId, weekdays: recurringWeekdays.sorted(), startMinute: startParts.hour! * 60 + startParts.minute!, endMinute: endParts.hour! * 60 + endParts.minute!, targetRefs: targets, reliefBudgetSeconds: policy.reliefBudgetSeconds, reliefMaxGrantSeconds: policy.reliefMaxGrantSeconds, exitCoolingOffSeconds: exitCoolingOffSeconds)
            schedule.siteDomains = policy.siteDomains
            schedule.siteSubdomains = policy.siteSubdomains
            guard schedule.endMinute > schedule.startMinute else { throw WindowPolicy.Failure.invalid }
            #if USER_GUARD_MAIN
            let requirements = selectedProtectionTargets.compactMap { target -> TrustedSigningRequirement? in
                guard let team = target.teamID, let requirement = target.requirement else { return nil }
                return TrustedSigningRequirement(signingID: target.id, teamID: team, requirement: requirement)
            }
            guard requirements.count == targets.count else { throw WindowPolicy.Failure.invalid }
            let frameEncoder = JSONEncoder(); frameEncoder.dateEncodingStrategy = .iso8601
            let request = WindowGuardRequest(policy: policy, command: "publish", signingRequirements: requirements, schedule: schedule)
            guard try frameEncoder.encode(request).count < 4096 else { windowMessage = "Список выбранных приложений и сайтов слишком велик; сократите его."; return }
            try store.prepare()
            let pending = UserEnrollmentPending(policy: policy, schedule: schedule, requirements: requirements)
            let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
            let fd = open(userPendingURL.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0o600)
            guard fd >= 0 else { throw WindowPolicy.Failure.invalid }
            defer { close(fd) }
            try FileHandle(fileDescriptor: fd, closeOnDealloc: false).write(contentsOf: encoder.encode(pending))
            guard fsync(fd) == 0 else { throw WindowPolicy.Failure.invalid }
            let d = open(store.directory.path, O_RDONLY | O_CLOEXEC); guard d >= 0 else { throw WindowPolicy.Failure.invalid }; defer { close(d) }
            guard fsync(d) == 0 else { throw WindowPolicy.Failure.invalid }
            userPending = pending; windowPolicy = policy; windowSchedule = schedule; candidateAuthoritative = false
            windowMessage = "Проект окна сохранён; служба ещё не подтвердила правило."
            Task { await retryUserEnrollment() }
            #else
            try windowStore.saveConfiguration(policy, schedule: schedule); windowPolicy = policy; windowSchedule = schedule
            windowMessage = "Окно сохранено. Состояние помощника и проверка действия указаны ниже."
            do { try prepareTrustProposal(policy); trustProposalStatus = "Подготовлен локальный проект настроек помощника; он не установлен и не активирован." }
            catch { trustProposalStatus = "Не удалось подготовить проект настроек: подпись этого запуска не проверена или запись недоступна. Помощник не установлен." }
            if !isolated || allowIsolatedCandidateBridge { Task { await syncCandidate() } }
            #endif
        } catch { windowPolicy = try? windowStore.load(); windowSchedule = try? windowStore.loadSchedule(); windowMessage = "Выберите будущие начало после 15:00 и конец в тот же день по Минску и укажите цели." }
    }
    func windowAction(_ action: String) {
        #if USER_GUARD_MAIN
        Task { await candidateAction(action) }; return
        #endif
        guard var policy = windowPolicy else { return }
        if policy.targetRefs != ["dev.stanislav.focusdeck.guard-fixture"] {
            Task { await candidateAction(action) }; return
        }
        #if USER_CALENDAR_FIXTURE
        Task { await userFixtureAction(action) }; return
        #endif
        let now = Date(); let changed: Bool
        switch action {
        case "relief": changed = policy.relief(now: now)
        case "rest": changed = policy.rest(now: now)
        case "returned": changed = policy.returnToWindow(now: now)
        case "exit_requested": changed = policy.requestExit(now: now)
        case "exit_cancelled": changed = policy.cancelExit(now: now)
        case "exit": changed = policy.exitWindow(now: now)
        default: changed = false
        }
        guard changed else { return }
        do { try windowStore.save(policy, event: ["relief": "relief_started", "rest": "rest_started", "returned": "returned", "exit_requested": "exit_requested", "exit_cancelled": "exit_cancelled", "exit": "window_exited"][action], now: now); windowPolicy = policy
            if policy.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"] {
                if !isolated || allowIsolatedFixtureBridge { Task { await syncFixture() } }
            } else if !isolated || allowIsolatedCandidateBridge { Task { await syncCandidate() } }
        }
        catch { windowPolicy = try? windowStore.load(); windowMessage = "Не удалось полностью сохранить состояние окна: \(error.localizedDescription)" }
    }
    func stopSchedule() {
        #if USER_GUARD_MAIN
        Task { await stopUserSchedule() }; return
        #endif
        do { try windowStore.saveSchedule(nil); windowSchedule = nil; windowMessage = "Расписание отключено. Текущее окно сохраняется до выхода или конца; защита не подключена." }
        catch { windowMessage = "Не удалось отключить расписание: \(error.localizedDescription)" }
    }
    func tickWindow() {
        // Candidate policy and budget are helper-owned; an offline UI must not
        // locally infer a grant, return, exit, or a new daily window.
        #if USER_GUARD_MAIN
        if let policy = windowPolicy { if policy.state == .exitPending { objectWillChange.send() }; return }
        #endif
        if let policy = windowPolicy, policy.targetRefs != ["dev.stanislav.focusdeck.guard-fixture"] {
            if policy.state == .exitPending { objectWillChange.send() }; return
        }
        #if USER_CALENDAR_FIXTURE
        if windowPolicy?.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"] { Task { await syncFixture() }; return }
        #endif
        let now = Date()
        if let schedule = windowSchedule, let candidate = schedule.window(on: now),
           windowPolicy == nil || (candidate.localDate > windowPolicy!.localDate && [.exit, .expired].contains(windowPolicy!.state)) {
            do { try windowStore.save(candidate, event: "configured"); windowPolicy = candidate }
            catch { windowMessage = "Не удалось сохранить дневное окно: \(error.localizedDescription)"; return }
        }
        guard var policy = windowPolicy else { return }
        guard let event = policy.reconcile(now: now) else { if policy.state == .exitPending { objectWillChange.send() }; return }
        do { try windowStore.save(policy, event: event); windowPolicy = policy }
        catch { windowPolicy = try? windowStore.load(); windowMessage = "Не удалось полностью сохранить состояние окна: \(error.localizedDescription)" }
    }
    @Published var state = SessionState()
    @Published var error: String?
    @Published var restored = false
    @Published var quietConfigured = false
    @Published var quietEnabled = false
    @Published var quietStatus = "Уведомления: настройка Focus ещё не связана"
    private let quiet = FocusShortcutsBridge()
    @Published var protectionStatus = ProtectionStatus.notConnected
    private let protection: any ExternalProtectionAdapter = UnconfiguredProtectionAdapter()
    @Published var storageBlocked = false
    @Published var collectionControlStatus = ""
    @Published var controllingCollection = false
    var isolated: Bool {
        #if FOCUSDECK_SM_FIXTURE || USER_GUARD_QA
        true
        #else
        ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"] != nil
        #endif
    }
    @Published var recoveryStatus = "Возвращение: нет данных"
    @Published var recoveryEnabled: Bool?
    @Published var reminderStatus = "Напоминания: состояние неизвестно"
    @Published var reminderEnabled: Bool?
    @Published var reminderPermission = "unknown"
    @Published var reminderBusy = false
    func reminderAction(_ action: String) async {
        guard !isolated, !reminderBusy else { return }; reminderBusy = true
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckHarness")
        let host = Bundle.main.bundleURL.appendingPathComponent("Contents/Helpers/FocusDeckRecovery.app/Contents/MacOS/RecoveryNotificationHost")
        let app = Bundle.main.bundleURL
        if action == "enable" || action == "request-permission" {
            let prepared = await ReminderControl.run("configure", root: root, host: host, context: store.stateURL, app: app)
            guard prepared.success else { reminderStatus = prepared.message; reminderBusy = false; return }
        }
        let result = await ReminderControl.run(action, root: root, host: host, context: store.stateURL, app: app)
        reminderStatus = result.message; if let enabled = result.enabled { reminderEnabled = enabled }
        if result.permission != "not_requested" { reminderPermission = result.permission }
        reminderBusy = false; reportReadAt = .distantPast
    }
    @Published var recoveryBusy = false
    @Published var returnReview = ReturnReviewSnapshot.unavailable
    func setRecovery(_ enabled: Bool) async {
        guard !recoveryBusy else { return }; recoveryBusy = true
        let result = await RecoveryControl.setEnabled(enabled, isolated: isolated)
        recoveryStatus = result.message; if result.success { recoveryEnabled = enabled }
        recoveryBusy = false; reportReadAt = .distantPast
    }
    @Published var report = DailyReportSnapshot.unavailable
    private var reportReadAt = Date.distantPast
    @Published var connection = "Локальная связь ещё не проверена"
    private let receiverOverride: (any ReceiverSyncing)?
    private lazy var receiver: any ReceiverSyncing = receiverOverride ?? LocalReceiver(journal: store)
    let store: SessionStore
    private var tickTime = ProcessInfo.processInfo.systemUptime
    init(receiver: (any ReceiverSyncing)? = nil, fixtureBridge: WindowFixtureBridge? = nil, candidateBridge: WindowCandidateBridge? = nil) {
        receiverOverride = receiver
        self.candidateBridge = candidateBridge ?? WindowCandidateBridge()
        #if USER_GUARD_QA
        allowIsolatedCandidateBridge = true
        #else
        allowIsolatedCandidateBridge = candidateBridge != nil
        #endif
        let env = ProcessInfo.processInfo.environment
        #if FOCUSDECK_SM_FIXTURE
        #if USER_CALENDAR_FIXTURE
        self.fixtureBridge = fixtureBridge ?? WindowFixtureBridge(path: "/tmp/focusdeck-user-calendar-\(getuid()).sock", expectedUID: getuid())
        #else
        self.fixtureBridge = fixtureBridge ?? WindowFixtureBridge(path: "/var/run/dev.stanislav.focusdeck.calendarfixture.sock", expectedUID: 0)
        #endif
        #else
        if let fixtureBridge { self.fixtureBridge = fixtureBridge }
        else if env["FOCUSDECK_DATA_DIR"] != nil, env["FOCUSDECK_V2_FIXTURE_BRIDGE"] == "1", let path = env["FOCUSDECK_V2_FIXTURE_SOCKET"], path.hasPrefix("/tmp/focusdeck-") {
            self.fixtureBridge = WindowFixtureBridge(path: path, expectedUID: getuid())
        } else { self.fixtureBridge = WindowFixtureBridge() }
        #endif
        #if FOCUSDECK_SM_FIXTURE
        allowIsolatedFixtureBridge = true
        #else
        allowIsolatedFixtureBridge = fixtureBridge != nil || ProcessInfo.processInfo.environment["FOCUSDECK_V2_FIXTURE_BRIDGE"] == "1"
        #endif
        let override = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"]
        #if FOCUSDECK_SM_FIXTURE || USER_GUARD_QA
        #if SESSION_MODEL_TEST
        guard let testRoot = override, testRoot.hasPrefix("/tmp/focusdeck-") else { fatalError("Fixture model tests require temporary data") }
        let root = URL(fileURLWithPath: testRoot, isDirectory: true)
        #else
        guard let namespace = Bundle.main.object(forInfoDictionaryKey: "FixtureDataNamespace") as? String,
              namespace.hasPrefix("focusdeck-calendar-bundle."), namespace.count <= 64,
              namespace.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-.").contains($0) }) else { fatalError("Fixture data namespace missing or invalid") }
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckCalendarFixtureUser-" + namespace, isDirectory: true)
        #endif
        #else
        let root = override.map { URL(fileURLWithPath: $0, isDirectory: true) } ??
            FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckStudy", isDirectory: true)
        #endif
        store = SessionStore(directory: root)
        do { windowPolicy = try windowStore.load(); windowSchedule = try windowStore.loadSchedule(); tickWindow() }
        catch { windowStorageBlocked = true; windowMessage = "Не удалось прочитать окно. Исходный файл сохранён; настройка недоступна." }
        #if USER_GUARD_MAIN
        if FileManager.default.fileExists(atPath: userPendingURL.path) {
            do {
                let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
                let pending = try decoder.decode(UserEnrollmentPending.self, from: Data(contentsOf: userPendingURL))
                userPending = pending
                if windowPolicy == nil { windowPolicy = pending.policy; windowSchedule = pending.schedule }
                candidateAuthoritative = false
            } catch { windowStorageBlocked = true; windowMessage = "Не удалось прочитать сохранённый проект окна; исходный файл сохранён." }
        }
        #endif
        if let policy = windowPolicy, FileManager.default.fileExists(atPath: trustProposalURL.path) {
            windowMessage = "Окно восстановлено. Состояние помощника и проверка действия указаны ниже."
            if let data = try? Data(contentsOf: trustProposalURL),
               let proposal = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               let revision = policy.policyRevisionId,
               proposal["policyRevisionId"] as? String == revision.uuidString,
               proposal["blockedSigningIDs"] as? [String] == policy.targetRefs,
               let currentHash = RootSocketServer.verifiedHash(pid: getpid()),
               proposal["uiCDHash"] as? String == currentHash {
                trustProposalStatus = "Сохранён локальный проект настроек; он не установлен и не активирован."
            } else { trustProposalStatus = "Сохранённый проект настроек может не соответствовать этому приложению или политике; проверьте его перед дальнейшими действиями." }
        }
        quietConfigured = !isolated && quiet.configuration(directory: root) != nil
        let preference = root.appendingPathComponent("preferences.json")
        let savedPreference = (try? JSONSerialization.jsonObject(with: Data(contentsOf: preference))) as? [String: Any]
        quietEnabled = savedPreference?["quietEnabled"] as? Bool ?? quietConfigured
        do {
            if var saved = try store.load() {
                restored = saved.phase == .running || saved.phase == .paused
                if saved.phase == .running, let interval = saved.closeSegment(now: saved.lastTick ?? Date()), interval.1 > interval.0 {
                    try store.append(SessionEvent(state: saved, type: "paused", now: Date(), interval: interval, reason: "persisted_known_interval_before_relaunch_gap"))
                }
                saved.recoverAfterRelaunch(); state = saved; boundTitle = saved.taskRef.isEmpty ? nil : saved.taskTitle
                if restored { try emit("health", reason: "relaunch_gap_unknown"); try store.save(state) }
            }
        } catch { storageBlocked = true; self.error = "Сохранённый контекст повреждён. Исходный файл сохранён; запись остановлена." }
    }
    func chooseExistingTask() {
        guard ![.running, .paused, .returnReady].contains(state.phase) else { return }
        if let path = state.taskNotePath ?? UserDefaults.standard.string(forKey: "obsidian.selectedNote"),
           FileManager.default.fileExists(atPath: path) {
            selectedNoteURL = URL(fileURLWithPath: path); choosingTask = true; refreshSelectedNote(force: true); return
        }
        chooseTaskNote()
    }
    func chooseTaskNote() {
        guard ![.running, .paused, .returnReady].contains(state.phase) else { return }
        let panel = NSOpenPanel(); panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.plainText, .text]
        panel.message = "Выберите дневную заметку. Задача будет отмечена в ней только по отдельной кнопке «Задача выполнена»."
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            taskChoices = try ObsidianTaskCatalog.readSelectedNote(url).filter { !$0.done }
            selectedNoteURL = url
            if !isolated { UserDefaults.standard.set(url.path, forKey: "obsidian.selectedNote") }
            if taskChoices.isEmpty { taskBindingStatus = "В разделе «Задачи» нет открытых задач." }
            else { choosingTask = true }
        } catch { self.error = error.localizedDescription }
    }
    func refreshSelectedNote(force: Bool = false) {
        guard force || Date().timeIntervalSince(taskReadAt) >= 2 else { return }; taskReadAt = Date()
        let url = choosingTask ? selectedNoteURL : state.taskNotePath.map { URL(fileURLWithPath: $0) }
        guard let url else { return }
        do {
            let choices = try ObsidianTaskCatalog.readSelectedNote(url)
            if choosingTask { taskChoices = choices.filter { !$0.done } }
            taskSourceStatus = "\(url.lastPathComponent) · файл проверен \(Date().formatted(date: .omitted, time: .standard))"
            if !choosingTask, !state.taskRef.isEmpty {
                let linked = choices.filter { $0.stableID == state.taskRef }
                if linked.count == 1, linked[0].title == state.taskTitle {
                    if state.taskCompleted != linked[0].done { state.taskCompleted = linked[0].done; persist() }
                    taskBindingStatus = linked[0].done ? "Задача выполнена в Obsidian" : "Связано с существующей задачей"
                } else { taskBindingStatus = "Задача изменена или удалена в Obsidian. Выберите её заново." }
            }
        } catch { taskSourceStatus = "Заметка недоступна · показан предыдущий список" }
    }
    func selectTask(_ choice: ObsidianTaskChoice) {
        boundTitle = choice.title; state.taskTitle = choice.title; state.taskRef = choice.stableID ?? ""; state.note = ""
        state.taskNotePath = selectedNoteURL?.path; state.taskCompleted = false
        taskBindingStatus = choice.stableID == nil ? "У задачи нет устойчивого ID — межустройственная связь пока недоступна." : "Связано с существующей задачей"
        choosingTask = false; persist()
    }
    func titleChanged() {
        if let boundTitle, state.taskTitle != boundTitle { state.taskRef = ""; state.taskNotePath = nil; state.taskCompleted = false; self.boundTitle = nil; taskBindingStatus = "Связь сброшена: название изменено" }
        persist()
    }
    func completeLinkedTask() {
        guard canCompleteLinkedTask, let path = state.taskNotePath else { return }
        do {
            let changed = try ObsidianTaskCatalog.completeSelectedTask(URL(fileURLWithPath: path), stableID: state.taskRef, expectedTitle: state.taskTitle)
            state.taskCompleted = true
            if [.running, .paused, .returnReady].contains(state.phase) { finish(completed: true) }
            else { persist() }
            taskBindingStatus = changed ? "Задача отмечена в Obsidian. На Pixel её доставит Syncthing." : "Задача уже была выполнена в Obsidian."
        } catch { self.error = error.localizedDescription }
    }
    func persist() { guard !storageBlocked else { return }; do { try store.save(state) } catch { self.error = "Не удалось сохранить контекст: \(error.localizedDescription)" } }
    func emit(_ type: String, interval: (Date, Date)? = nil, reason: String? = nil) throws {
        try store.append(SessionEvent(state: state, type: type, now: Date(), interval: interval, reason: reason))
    }
    func start() {
        guard !storageBlocked, !state.taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        state.start(now: Date()); restored = false; tickTime = ProcessInfo.processInfo.systemUptime
        state.focusRequested = quietEnabled && quietConfigured && state.category == "study" && !isolated
        state.quietUntil = nil; state.quietOutcome = nil
        record("started")
        if state.focusRequested, let id = state.sessionId {
            Task {
                let receipt = await quiet.enter(session: id, directory: store.directory, isolated: isolated)
                quietStatus = quiet.status
                if state.sessionId == id { state.quietUntil = receipt.until; state.quietOutcome = receipt.outcome.rawValue; persist() }
            }
        }
    }
    func pause() {
        guard state.phase == .running, !state.usedPause else { return }
        let interval = state.pause(now: Date()); record("paused", interval: interval, reason: "bounded_break_300_seconds")
    }
    func resume() {
        state.resume(now: Date()); tickTime = ProcessInfo.processInfo.systemUptime; restored = false
        record("resumed", reason: "explicit_return")
    }
    func finish(completed: Bool) {
        let interval = state.closeSegment(now: Date()); state.phase = .finished; state.pauseUntil = nil
        record(completed ? "completed" : "stopped", interval: interval)
        quiet.stopped(until: state.quietUntil); quietStatus = quiet.status
    }
    func archiveCorruptContext() {
        do {
            let backup = store.directory.appendingPathComponent("session-corrupt-" + UUID().uuidString + ".json")
            try FileManager.default.moveItem(at: store.stateURL, to: backup)
            state = SessionState(); storageBlocked = false; error = nil; persist()
        } catch { self.error = "Не удалось сохранить исходный файл отдельно. Запись остановлена." }
    }
    func record(_ type: String, interval: (Date, Date)? = nil, reason: String? = nil) {
        guard !storageBlocked else { return }
        do { try emit(type, interval: interval, reason: reason); try store.save(state) }
        catch { self.error = "Запись не сохранена: \(error.localizedDescription)" }
    }
    func tick() {
        tickWindow()
        refreshSelectedNote()
        let uptime = ProcessInfo.processInfo.systemUptime
        let elapsed = uptime - tickTime; tickTime = uptime
        // A suspended main loop or sleep is unknown time, not proof of work.
        let wallElapsed = state.lastTick.map { Date().timeIntervalSince($0) }
        if state.phase == .running && (elapsed > 3 || wallElapsed.map { abs($0 - elapsed) > 2 } == true) {
            let known = state.closeSegment(now: state.lastTick ?? Date())
            state.phase = .returnReady
            record("paused", interval: known, reason: "known_interval_before_unknown_gap")
            record("health", reason: "suspend_or_clock_gap_unknown"); return
        }
        let prior = state.phase
        if state.tick(elapsed: elapsed, now: Date()) { record("health", reason: "nominal_target_ended_accounting_continues_until_explicit_stop") }
        else if prior != state.phase { record("health", reason: "bounded_break_ended_explicit_return_needed") }
        else if state.phase == .running, state.segmentElapsed >= 60, let interval = state.closeSegment(now: Date()) {
            state.segmentStart = interval.1; state.lastTick = Date()
            record("heartbeat", interval: interval, reason: "measured_live_timer_segment")
        }
        else if state.phase == .running { persist() }
    }
    func controlCollection(start: Bool) async {
        guard !controllingCollection else { return }; controllingCollection = true
        let result = await LocalCollectionControl.perform(start: start, isolated: isolated)
        collectionControlStatus = result.message; controllingCollection = false
    }
    func saveQuietPreference() {
        guard !isolated else { return }
        do {
            try store.prepare()
            let path = store.directory.appendingPathComponent("preferences.json")
            try JSONSerialization.data(withJSONObject: ["quietEnabled": quietEnabled]).write(to: path, options: .atomic)
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path.path)
        } catch { self.error = "Не удалось сохранить настройку Focus" }
    }
    func observeQuiet() async {
        guard !isolated else { return }
        quietConfigured = quiet.configuration(directory: store.directory) != nil
        guard quietConfigured else { return }
        await quiet.observe(session: state.sessionId, until: state.quietUntil, priorOutcome: state.quietOutcome, directory: store.directory, isolated: false)
        quietStatus = quiet.status
    }
    #if USER_CALENDAR_FIXTURE
    private var fixtureConnectionGeneration: UInt = 0
    private func fixtureUnknown() { fixtureAcknowledgedWindow = nil; fixtureAcknowledgedState = nil; fixtureBridgeStatus = "Связь неизвестна: состояние и действие службы не подтверждены." }
    func fixtureServiceDisconnected() { fixtureConnectionGeneration &+= 1; fixtureUnknown() }
    private func acceptUserFixture(_ reply: WindowGuardReply?, local: WindowPolicy, event: String? = nil) -> Bool {
        guard let reply, reply.accepted, let remote = reply.policy, reply.windowId == remote.windowId,
              remote.windowId == local.windowId, candidateMatches(remote, local: local) else { fixtureUnknown(); return false }
        do { try windowStore.save(remote, event: event); windowPolicy = remote; fixtureAcknowledgedWindow = remote.windowId; fixtureAcknowledgedState = remote.state; fixtureBridgeStatus = "Служба подтвердила состояние synthetic fixture."; return true }
        catch { fixtureUnknown(); return false }
    }
    func startUserFixture(_ p: WindowPolicy) async {
        guard !candidateBusy else { return }; candidateBusy = true; defer { candidateBusy = false }
        let generation = fixtureConnectionGeneration
        let reply = await fixtureBridge.request(p, command: "publish")
        guard generation == fixtureConnectionGeneration else { return }
        if acceptUserFixture(reply, local: p, event: "configured") { windowMessage = "Только синтетическое тестовое окно." }
    }
    func userFixtureAction(_ action: String) async {
        guard !candidateBusy, let local = windowPolicy else { return }; candidateBusy = true; defer { candidateBusy = false }
        let generation = fixtureConnectionGeneration
        let status = await fixtureBridge.request(local, command: "status")
        guard generation == fixtureConnectionGeneration else { return }
        guard let status, acceptUserFixture(status, local: local), var remote = status.policy else { fixtureUnknown(); return }
        let now = Date(); let changed: Bool
        switch action {
        case "relief": changed = remote.relief(now: now)
        case "rest": changed = remote.rest(now: now)
        case "returned": changed = remote.returnToWindow(now: now)
        case "exit_requested": changed = remote.requestExit(now: now)
        case "exit_cancelled": changed = remote.cancelExit(now: now)
        case "exit": changed = remote.exitWindow(now: now)
        default: changed = false
        }
        guard changed else { return }
        let reply = await fixtureBridge.request(remote, command: "publish")
        guard generation == fixtureConnectionGeneration else { return }
        let event = ["relief": "relief_started", "rest": "rest_started", "returned": "returned", "exit_requested": "exit_requested", "exit_cancelled": "exit_cancelled", "exit": "window_exited"][action]
        _ = acceptUserFixture(reply, local: local, event: event)
    }
    #endif
    func syncFixture() async {
        #if USER_CALENDAR_FIXTURE
        if windowPolicy == nil, !candidateBusy {
            let probe = WindowPolicy(windowId: UUID(), localDate: "unknown", startsAt: Date(), endsAt: Date().addingTimeInterval(1), reliefBudgetSeconds: 0, reliefRemainingSeconds: 0, reliefMaxGrantSeconds: 1, targetRefs: ["dev.stanislav.focusdeck.guard-fixture"])
            let generation = fixtureConnectionGeneration
            let result = await fixtureBridge.request(probe, command: "status")
            guard generation == fixtureConnectionGeneration else { return }
            guard let reply = result, reply.accepted else { fixtureUnknown(); return }
            guard let remote = reply.policy else { return }
            guard reply.windowId == remote.windowId, remote.policyVersion == 2, remote.policyRevisionId != nil,
                  remote.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"], remote.startsAt < remote.endsAt,
                  remote.endsAt.timeIntervalSince(remote.startsAt) <= 180,
                  (0...remote.reliefBudgetSeconds).contains(remote.reliefRemainingSeconds) else { fixtureUnknown(); return }
            do { try windowStore.save(remote); windowPolicy = remote; fixtureAcknowledgedWindow = remote.windowId; fixtureAcknowledgedState = remote.state; fixtureBridgeStatus = "Служба восстановила состояние synthetic fixture." }
            catch { fixtureUnknown() }
            return
        }
        #endif
        guard (!isolated || allowIsolatedFixtureBridge), let policy = windowPolicy,
              policy.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"] else { return }
        #if USER_CALENDAR_FIXTURE
        guard !candidateBusy else { return }
        let generation = fixtureConnectionGeneration
        let reply = await fixtureBridge.request(policy, command: "status")
        guard generation == fixtureConnectionGeneration else { return }
        _ = acceptUserFixture(reply, local: policy)
        return
        #else
        guard policy.state != .expired else { return }
        let (status, accepted) = await fixtureBridge.send(policy)
        fixtureBridgeStatus = status
        if accepted { fixtureAcknowledgedWindow = policy.windowId; fixtureAcknowledgedState = policy.state }
        else { fixtureAcknowledgedWindow = nil; fixtureAcknowledgedState = nil }
        #endif
    }
    private func candidateMatches(_ remote: WindowPolicy, local: WindowPolicy) -> Bool {
        remote.windowId == local.windowId && remote.policyVersion == 2 && remote.policyRevisionId != nil && remote.policyRevisionId == local.policyRevisionId &&
        remote.targetRefs == local.targetRefs && remote.siteDomains == local.siteDomains && remote.siteSubdomains == local.siteSubdomains && remote.timezone == "Europe/Minsk" && remote.localDate == local.localDate &&
        abs(remote.startsAt.timeIntervalSince(local.startsAt)) < 1.1 && abs(remote.endsAt.timeIntervalSince(local.endsAt)) < 1.1 &&
        remote.reliefBudgetSeconds == local.reliefBudgetSeconds && remote.reliefMaxGrantSeconds == local.reliefMaxGrantSeconds &&
        remote.exitCoolingOffSeconds == local.exitCoolingOffSeconds && (0...remote.reliefBudgetSeconds).contains(remote.reliefRemainingSeconds)
    }
    #if USER_GUARD_MAIN
    private func recurringCandidateMatches(_ remote: WindowPolicy, local: WindowPolicy) -> Bool {
        guard let schedule = windowSchedule, let revision = schedule.policyRevisionId,
              remote.policyRevisionId == revision, remote.localDate > local.localDate,
              [.exit, .expired].contains(local.state) || Date() >= local.endsAt,
              remote.windowId == WindowPolicy.recurringWindowID(revision: revision, date: remote.localDate),
              let expected = schedule.window(on: remote.startsAt), expected.localDate == remote.localDate,
              abs(expected.startsAt.timeIntervalSince(remote.startsAt)) < 1.1, abs(expected.endsAt.timeIntervalSince(remote.endsAt)) < 1.1,
              expected.targetRefs == remote.targetRefs, expected.siteDomains == remote.siteDomains, expected.siteSubdomains == remote.siteSubdomains, expected.reliefBudgetSeconds == remote.reliefBudgetSeconds,
              expected.reliefMaxGrantSeconds == remote.reliefMaxGrantSeconds, expected.exitCoolingOffSeconds == remote.exitCoolingOffSeconds,
              (0...remote.reliefBudgetSeconds).contains(remote.reliefRemainingSeconds) else { return false }
        return true
    }
    #endif
    private func adoptCandidate(_ remote: WindowPolicy, local: WindowPolicy, event: String? = nil) -> Bool {
        #if USER_GUARD_MAIN
        let sameOrRecurring = candidateMatches(remote, local: local) || recurringCandidateMatches(remote, local: local)
        #else
        let sameOrRecurring = candidateMatches(remote, local: local)
        #endif
        guard windowPolicy?.windowId == local.windowId, windowPolicy?.policyRevisionId == local.policyRevisionId,
              sameOrRecurring else { candidateStatus = "Состояние помощника относится к другой политике; подтверждения нет."; return false }
        do { try windowStore.save(remote, event: event); windowPolicy = remote; return true }
        catch { candidateStatus = "Помощник ответил, но локальная запись недоступна; состояние требует повторной проверки."; return false }
    }
    private func describeCandidate(_ reply: WindowGuardReply) {
        if reply.scheduleStatus == "clock_unknown" { candidateStatus = "Время расписания неизвестно после перезагрузки или скачка часов; новые окна не создаются. Текущее окно и выход доступны отдельно."; return }
        if reply.scheduleStatus == "off", windowSchedule != nil { candidateStatus = "Служба не применяет сохранённое расписание; будущие окна не включены."; return }
        candidateStatus = reply.enforcement == "selected_apps_requested" ?
            "Помощник сообщил о действующей политике. Фактическая блокировка на устройстве не подтверждена." :
            "Помощник сообщил, что сейчас ограничения не запрошены; действие на устройстве не проверено."
    }
    #if USER_GUARD_MAIN
    private func commitUserEnrollment(_ remote: WindowPolicy, matching local: WindowPolicy) -> Bool {
        guard let pending = userPending, candidateMatches(remote, local: local), remote.windowId == pending.policy.windowId else { return false }
        do {
            try windowStore.saveConfiguration(remote, schedule: pending.schedule)
            try FileManager.default.removeItem(at: userPendingURL)
            userPending = nil; windowPolicy = remote; windowSchedule = pending.schedule; return true
        } catch { candidateStatus = "Служба ответила, но запись проекта требует восстановления."; return false }
    }
    func stopUserSchedule() async {
        guard !candidateBusy, let local = windowPolicy else { return }
        candidateBusy = true; candidateAuthoritative = false; defer { candidateBusy = false }
        let generation = userServiceGeneration
        let reply = await candidateBridge.request(local, command: "disable_schedule")
        guard generation == userServiceGeneration else { return }
        guard let reply, reply.accepted, reply.scheduleStatus == "off", let remote = reply.policy,
              reply.windowId == remote.windowId, candidateMatches(remote, local: local) || recurringCandidateMatches(remote, local: local) else {
            candidateStatus = "Отключение расписания не подтверждено; состояние неизвестно."; return
        }
        do { try windowStore.saveSchedule(nil); try windowStore.save(remote); windowSchedule = nil; windowPolicy = remote; candidateAuthoritative = true; candidateStatus = "Будущие окна отключены; текущее окно сохранено." }
        catch { candidateStatus = "Служба ответила, но локальная запись требует восстановления." }
    }
    func retryUserEnrollment() async {
        guard !candidateBusy, let pending = userPending else { return }
        candidateBusy = true; candidateAuthoritative = false; defer { candidateBusy = false }
        let generation = userServiceGeneration
        let statusReply = await candidateBridge.request(pending.policy, command: "status")
        guard generation == userServiceGeneration else { return }
        guard let status = statusReply, status.accepted else { candidateStatus = "Связь неизвестна; правило не подтверждено."; return }
        if let remote = status.policy {
            if remote.windowId == pending.policy.windowId {
                guard commitUserEnrollment(remote, matching: pending.policy) else { candidateStatus = "Состояние службы не совпадает с проектом."; return }
                candidateAuthoritative = true; describeCandidate(status); return
            }
            guard [.exit, .expired].contains(remote.state), pending.policy.localDate > remote.localDate else {
                candidateStatus = "Состояние службы относится к другому окну; проект не применён."; return
            }
        }
        #if USER_GUARD_QA
        let publishReply = await candidateBridge.request(pending.policy, command: "publish", requirements: pending.requirements)
        #else
        let publishReply = await candidateBridge.request(pending.policy, command: "publish", requirements: pending.requirements, schedule: pending.schedule)
        #endif
        guard generation == userServiceGeneration else { return }
        guard let result = publishReply, result.accepted, let remote = result.policy, commitUserEnrollment(remote, matching: pending.policy) else {
            candidateStatus = "Ответ службы неизвестен; сохранён тот же проект для проверки."; return
        }
        candidateAuthoritative = true; describeCandidate(result)
    }
    #endif
    func syncCandidate() async {
        guard !candidateBusy, (!isolated || allowIsolatedCandidateBridge) else { return }
        #if USER_GUARD_MAIN
        guard let local = windowPolicy else { await refreshRemovalStatus(); return }
        #else
        guard let local = windowPolicy else { return }
        #endif
        #if !USER_GUARD_MAIN
        guard local.targetRefs != ["dev.stanislav.focusdeck.guard-fixture"] else { return }
        #endif
        candidateBusy = true; candidateAuthoritative = false; defer { candidateBusy = false }
        #if USER_GUARD_MAIN
        let generation = userServiceGeneration
        #endif
        let statusReply = await candidateBridge.request(local, command: "status")
        #if USER_GUARD_MAIN
        guard generation == userServiceGeneration else { return }
        #endif
        guard let status = statusReply, status.accepted else {
            #if USER_GUARD_MAIN
            _ = acceptRemovalStatus(nil)
            #endif
            candidateStatus = "Связь с помощником неизвестна; защита и снятие ограничения не подтверждены."; return
        }
        #if USER_GUARD_MAIN
        guard acceptRemovalStatus(status) else { return }
        #endif
        if let remote = status.policy {
            #if USER_GUARD_MAIN
            if userPending != nil {
                guard status.windowId == remote.windowId, commitUserEnrollment(remote, matching: local) else { candidateStatus = "Состояние службы относится к другому окну; проект не применён."; return }
                candidateAuthoritative = true; describeCandidate(status); return
            }
            #endif
            guard status.windowId == remote.windowId, adoptCandidate(remote, local: local) else { return }; candidateAuthoritative = true; describeCandidate(status); return
        }
        // An empty helper could mean recovery, data loss, or a never-enrolled
        // window. It is not proof that restoring the local budget is safe.
        candidateStatus = "Помощник не вернул состояние окна; причина неизвестна. Настройка не опубликована автоматически."
    }
    func candidateAction(_ action: String) async {
        guard !candidateBusy, (!isolated || allowIsolatedCandidateBridge), let local = windowPolicy else { return }
        candidateBusy = true; candidateAuthoritative = false; defer { candidateBusy = false }
        #if USER_GUARD_MAIN
        let generation = userServiceGeneration
        #endif
        let statusReply = await candidateBridge.request(local, command: "status")
        #if USER_GUARD_MAIN
        guard generation == userServiceGeneration else { return }
        #endif
        guard let status = statusReply, status.accepted, let remote = status.policy,
              status.windowId == remote.windowId, adoptCandidate(remote, local: local) else {
            candidateStatus = "Связь с помощником неизвестна; изменение не подтверждено."; return
        }
        var proposed = remote; let now = Date(); let changed: Bool
        switch action {
        case "relief": changed = proposed.relief(now: now)
        case "rest": changed = proposed.rest(now: now)
        case "returned": changed = proposed.returnToWindow(now: now)
        case "exit_requested": changed = proposed.requestExit(now: now)
        case "exit_cancelled": changed = proposed.cancelExit(now: now)
        case "exit": changed = proposed.exitWindow(now: now)
        default: changed = false
        }
        guard changed else { candidateAuthoritative = true; candidateStatus = "Изменение сейчас недоступно по состоянию помощника."; return }
        let updateReply = await candidateBridge.request(proposed, command: "update")
        #if USER_GUARD_MAIN
        guard generation == userServiceGeneration else { return }
        #endif
        guard let reply = updateReply, reply.accepted, let committed = reply.policy,
              reply.windowId == committed.windowId, candidateMatches(committed, local: remote), committed.windowId == remote.windowId,
              committed.state == proposed.state, committed.reliefRemainingSeconds == proposed.reliefRemainingSeconds else {
            candidateStatus = "Ответ об изменении не получен или отклонён; фактическое состояние неизвестно. Повторно проверьте связь."; return
        }
        let event = ["relief": "relief_started", "rest": "rest_started", "returned": "returned", "exit_requested": "exit_requested", "exit_cancelled": "exit_cancelled", "exit": "window_exited"][action]
        guard adoptCandidate(committed, local: remote, event: event) else { return }; candidateAuthoritative = true; describeCandidate(reply)
    }
    func sync() async {
        await syncFixture(); await syncCandidate()
        protectionStatus = await protection.status()
        await receiver.sync(); connection = receiver.status
        #if FOCUSDECK_SM_FIXTURE || USER_GUARD_QA
        return // No production report or recovery reads in the fixture build.
        #else
        let environment = ProcessInfo.processInfo.environment
        let fixtureReport = environment["FOCUSDECK_REPORT_FILE"]
        guard !isolated || fixtureReport != nil else { return }
        guard Date().timeIntervalSince(reportReadAt) > 30 else { return }; reportReadAt = Date()
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let path = fixtureReport.map { URL(fileURLWithPath: $0) } ?? support.appendingPathComponent("FocusDeckHarness/reports/current-day.json")
        let fixtureReviewDirectory: URL? = {
            guard isolated, let dataRoot = environment["FOCUSDECK_DATA_DIR"], dataRoot.hasPrefix("/"),
                  let requested = environment["FOCUSDECK_REVIEW_FIXTURE_DIR"], requested.hasPrefix("/") else { return nil }
            let root = URL(fileURLWithPath: dataRoot).resolvingSymlinksInPath().standardizedFileURL.path
            let candidate = URL(fileURLWithPath: requested).resolvingSymlinksInPath().standardizedFileURL.path
            guard candidate == root || candidate.hasPrefix(root + "/") else { return nil }
            return URL(fileURLWithPath: candidate, isDirectory: true)
        }()
        if !isolated || fixtureReviewDirectory != nil {
            let reports = fixtureReviewDirectory ?? support.appendingPathComponent("FocusDeckHarness/reports", isDirectory: true)
            let recovery = reports.appendingPathComponent("recovery.json")
            let weekly = reports.appendingPathComponent("weekly-latest.json")
            func boundedData(_ url: URL) -> Data? {
                guard let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber, size.intValue <= 262144 else { return nil }
                return try? Data(contentsOf: url)
            }
            let recoveryData = boundedData(recovery)
            if !isolated, let config = boundedData(support.appendingPathComponent("FocusDeckHarness/review-config.json")),
               let settings = (try? JSONSerialization.jsonObject(with: config)) as? [String: Any],
               let reminder = settings["recoveryReminder"] as? [String: Any] { reminderEnabled = reminder["enabled"] as? Bool }
            if let data = boundedData(reports.appendingPathComponent("latest.json")),
               let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               let reminder = payload["recoveryReminder"] as? [String: Any], let status = reminder["state"] as? String {
                let meanings = ["dispatch_attempted":"Попытка передачи; доставка неизвестна", "quiet_hours":"Тихие часы", "cooldown":"Ожидание между попытками", "episode_limit":"Лимит попыток достигнут", "insufficient_evidence":"Недостаточно данных", "permission_required":"Нет подтверждённого разрешения", "unavailable":"Средство недоступно", "disabled":"Отключено", "not_configured":"Не настроено"]
                reminderStatus = "Последний обзор: " + (meanings[status] ?? "состояние попытки неизвестно")
            }
            returnReview = ReturnReviewSnapshot.parse(recovery: recoveryData, weekly: boundedData(weekly))
            if let data = recoveryData, let payload = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
               payload["schemaVersion"] as? Int == 1, let state = payload["state"] as? String {
                recoveryEnabled = payload["enabled"] as? Bool
                if returnReview.recoveryFreshness == "stale" { recoveryStatus = "Возвращение: отчёт устарел; текущее состояние источника неизвестно" }
                else if returnReview.recoveryFreshness == "unknown" { recoveryStatus = "Возвращение: время отчёта неизвестно; текущее состояние источника неизвестно" }
                else { switch state {
                case "available": recoveryStatus = "Возвращение: недавние события получены; текущая связь неизвестна"
                case "source_unavailable": recoveryStatus = "Возвращение: нет недавних событий или источник недоступен; причина неизвестна"
                case "disabled": recoveryStatus = "Возвращение: проверка выключена"
                default: recoveryStatus = "Возвращение: состояние источника неизвестно"
                } }
            } else { recoveryEnabled = nil; recoveryStatus = "Возвращение: нет данных" }
        }
        do { report = try DailyReportSnapshot.parse(Data(contentsOf: path), fixture: fixtureReport != nil) }
        catch { report = .unavailable }
        #endif
    }
    func revealJournal() { NSWorkspace.shared.activateFileViewerSelecting([store.eventsURL]) }
    var clock: String {
        let seconds: Int
        if state.phase == .paused, let deadline = state.pauseUntil { seconds = max(0, Int(ceil(min(state.pauseRemainingSeconds, deadline.timeIntervalSinceNow)))) }
        else { seconds = max(0, Int(ceil(abs(state.remainingSeconds)))) }
        return (state.phase != .paused && state.remainingSeconds < 0 ? "+" : "") + String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }
}

#if !SESSION_MODEL_TEST
struct FocusDeckApp: App {
    @StateObject private var model = SessionModel()
    var body: some Scene {
        WindowGroup("FocusDeck") {
            #if SYSTEM_PROTECTION
            DeckWorkspaceView(session: model)
                .frame(minWidth: 840, idealWidth: 1040, minHeight: 600, idealHeight: 740)
                .preferredColorScheme(.dark)
                .task { while !Task.isCancelled { try? await Task.sleep(for: .seconds(1)); model.tick() } }
                .task { while !Task.isCancelled { await model.sync(); try? await Task.sleep(for: .seconds(5)) } }
                .task { while !Task.isCancelled { await model.observeQuiet(); try? await Task.sleep(for: .seconds(15)) } }
            #else
            FocusDeckView(model: model)
                .frame(minWidth: 680, idealWidth: 780, minHeight: 680, idealHeight: 760)
                .preferredColorScheme(.dark)
                .task { while !Task.isCancelled { try? await Task.sleep(for: .seconds(1)); model.tick() } }
                .task { while !Task.isCancelled { await model.sync(); try? await Task.sleep(for: .seconds(5)) } }
                .task { while !Task.isCancelled { await model.observeQuiet(); try? await Task.sleep(for: .seconds(15)) } }
            #endif
        }
        #if SYSTEM_PROTECTION
        .defaultSize(width: 1040, height: 740)
        #endif
        .windowStyle(.hiddenTitleBar)
        .commands { CommandGroup(replacing: .newItem) {} }
    }
}

@main enum FocusDeckEntry {
    @MainActor static func main() {
        #if SYSTEM_PROTECTION
        if CommandLine.arguments.count >= 2, CommandLine.arguments[1].hasPrefix("--system-") {
            do {
                let action = CommandLine.arguments[1]
                guard ["--system-install", "--system-status", "--system-validate", "--system-validate-strict", "--system-validate-app", "--system-start", "--system-add", "--system-browser", "--system-request-exit", "--system-cancel-exit"].contains(action) else {
                    fputs("There is no early-stop or reset command for system protection.\n", stderr); exit(64)
                }
                if action == "--system-install" { print(try SystemProtectionInstaller.install()); return }
                let hash = try SystemProtectionInstaller.hash()
                var domains = Array(CommandLine.arguments.dropFirst(2))
                var seconds = 3600
                if domains.first == "--minutes" {
                    guard domains.count >= 2, let minutes = Int(domains[1]), (1...43_200).contains(minutes) else { exit(64) }
                    seconds = minutes * 60; domains.removeFirst(2)
                }
                let command = action == "--system-validate" ? "validate" : action == "--system-validate-strict" ? "validate-strict" : action == "--system-validate-app" ? "validate-app" : action == "--system-start" ? "start" : action == "--system-add" ? "add-targets" : action == "--system-browser" ? "browser" : action == "--system-request-exit" ? "request-exit" : action == "--system-cancel-exit" ? "cancel-exit" : "status"
                var applications = [TrustedSigningRequirement]()
                if command == "validate-app" {
                    guard domains.isEmpty else { exit(64) }
                    let target = try ProtectionTarget.inspect(URL(fileURLWithPath: "/Applications/Google Chrome.app"))
                    guard let team = target.teamID, let requirement = target.requirement else { exit(64) }
                    applications = [.init(signingID: target.id, teamID: team, requirement: requirement)]
                }
                let reply = try SystemProtectionClient.send(.init(command: command, seconds: seconds, domains: domains, applications: applications), expectedHash: hash)
                print(String(decoding: try JSONEncoder().encode(reply), as: UTF8.self))
                exit(reply.accepted ? 0 : 1)
            } catch { fputs("System protection: \(error)\n", stderr); exit(1) }
        }
        #endif
        #if USER_GUARD_MAIN
        let args = CommandLine.arguments
        if args.count == 2 && args[1] == "--focusdeck-sm-status" {
            #if USER_GUARD_QA
            let service = CalendarSystemService(plistName: "dev.stanislav.focusdeck.userprotection.qa.plist")
            #else
            let service = CalendarSystemService(plistName: "dev.stanislav.focusdeck.userprotection.plist")
            #endif
            let info: [String: String] = ["bundle": Bundle.main.bundleURL.standardizedFileURL.path,
                                          "bundleId": Bundle.main.bundleIdentifier ?? "",
                                          "smStatus": String(describing: service.state)]
            if let bytes = try? JSONSerialization.data(withJSONObject: info, options: [.sortedKeys]),
               let text = String(data: bytes, encoding: .utf8) { print(text); return }
            exit(1)
        }
        #if !USER_GUARD_QA
        if args.count == 2 && args[1] == "--focusdeck-install-user-agent" {
            guard Bundle.main.bundleIdentifier == "dev.stanislav.focusdeck.mac",
                  CalendarSystemService(plistName: "dev.stanislav.focusdeck.userprotection.plist").state == .absent else {
                fputs("SM registration is not confirmed absent; user-agent installation refused.\n", stderr); exit(1)
            }
            let manager = Process()
            manager.executableURL = Bundle.main.bundleURL.appendingPathComponent("Contents/Library/HelperTools/FocusDeckUserAgentManager")
            manager.arguments = ["install"]
            do { try manager.run(); manager.waitUntilExit(); exit(manager.terminationStatus) }
            catch { fputs("User-agent installation was not confirmed.\n", stderr); exit(1) }
        }
        #endif
        if args.dropFirst().contains(where: { $0.hasPrefix("--focusdeck-") }) { fputs("Unsupported FocusDeck command.\n", stderr); exit(2) }
        #endif
        FocusDeckApp.main()
    }
}
#endif

struct FocusDeckView: View {
    @ObservedObject var model: SessionModel
    #if USER_GUARD_MAIN
    #if USER_GUARD_QA
    @StateObject private var userService = CalendarServiceControl(service: CalendarSystemService(plistName: "dev.stanislav.focusdeck.userprotection.qa.plist"))
    @State private var qaChild: Process?
    @State private var qaChildStatus = "Синтетический процесс не запускался."
    #else
    @StateObject private var userService = CalendarServiceControl(service: CalendarSystemService(plistName: "dev.stanislav.focusdeck.userprotection.plist"))
    @StateObject private var manualService = ManualAgentControl()
    #endif
    #endif
    #if FOCUSDECK_SM_FIXTURE
    #if USER_CALENDAR_FIXTURE
    @StateObject private var calendarService = CalendarServiceControl(service: CalendarSystemService(plistName: "dev.stanislav.focusdeck.usercalendarfixture.plist"))
    @State private var syntheticProcess: Process?
    @State private var syntheticStatus = "Синтетический процесс не запущен."
    #else
    @StateObject private var calendarService = CalendarServiceControl(service: CalendarSystemService(plistName: "dev.stanislav.focusdeck.calendarfixture.plist"))
    #endif
    #endif
    private let ink = Color(red: 0.92, green: 0.94, blue: 0.95)
    private enum InputField: Hashable { case task, note }
    @FocusState private var inputFocus: InputField?
    private let accent = Color(red: 0.69, green: 0.94, blue: 0.44)
    var active: Bool { [.running, .paused, .returnReady].contains(model.state.phase) }
    var body: some View {
        ZStack {
            Color(red: 0.055, green: 0.065, blue: 0.07).ignoresSafeArea()
            ScrollViewReader { proxy in ScrollView { VStack(alignment: .leading, spacing: 20) {
                HStack {
                    HStack(spacing: 9) { Image(systemName: "circle.hexagongrid.fill").foregroundStyle(accent); Text("FocusDeck").font(.system(size: 17, weight: .semibold)) }
                    Spacer()
                    Text("MAC · ЛОКАЛЬНО").font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(.secondary)
                }.id("sessionTop")
                HStack(alignment: .top, spacing: 28) {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(active ? (model.state.phase == .returnReady ? "Вернуться к делу" : "Одно дело сейчас") : "Что сделаем сейчас?")
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                        if active {
                            Text(model.state.taskTitle).font(.system(size: 20, weight: .medium)).textSelection(.enabled)
                            Text(model.state.category == "work" ? "Работа" : "Учёба").foregroundStyle(accent)
                        } else {
                            TextField("Ближайшее действие", text: $model.state.taskTitle)
                                .focused($inputFocus, equals: .task)
                                .textFieldStyle(.plain).font(.system(size: 21)).padding(14)
                                .background(.white.opacity(0.045), in: RoundedRectangle(cornerRadius: 12))
                                .onChange(of: model.state.taskTitle) { model.titleChanged() }
                            Button("Из существующей задачи…") { model.chooseExistingTask() }.buttonStyle(.plain).font(.system(size: 12)).foregroundStyle(accent)
                            if !model.taskBindingStatus.isEmpty { Text(model.taskBindingStatus).font(.system(size: 10)).foregroundStyle(.secondary) }
                            HStack(spacing: 8) {
                                category("Работа", "work"); category("Учёба", "study")
                            }
                        }
                        if model.restored { Text("Контекст сохранён. Время вне приложения неизвестно.").font(.callout).foregroundStyle(.secondary) }
                        if model.state.phase == .returnReady { Text(model.restored ? "Контекст на месте. Продолжите ближайшее действие." : model.state.usedPause ? "Перерыв окончен. Работа продолжится после вашего действия." : "Наблюдение прерывалось. Продолжите, когда будете готовы.").font(.callout).foregroundStyle(.secondary) }
                    }
                    Spacer(minLength: 0)
                    VStack(spacing: 7) {
                        Text(model.clock).font(.system(size: 49, weight: .light, design: .monospaced)).monospacedDigit().foregroundStyle(accent)
                        Text(model.state.phase == .paused ? "ПЕРЕРЫВ" : model.state.remainingSeconds <= 0 ? "ПЛАН ОКОНЧЕН · УЧЁТ ИДЁТ" : "МИНУТ НА ОДНО ДЕЛО")
                            .font(.system(size: 9, weight: .semibold, design: .monospaced)).foregroundStyle(.secondary)
                    }.frame(width: 190)
                }
                HStack(spacing: 11) {
                    if model.state.phase == .running {
                        primary("Завершить отрезок", icon: "checkmark") { model.finish(completed: true) }
                        Button("Перерыв · 5 мин") { model.pause() }.disabled(model.state.usedPause)
                        Button("Остановить") { model.finish(completed: false) }.buttonStyle(.plain).foregroundStyle(.secondary)
                    } else if model.state.phase == .paused || model.state.phase == .returnReady {
                        primary("Продолжить", icon: "play.fill") { model.resume() }
                        Button("Закончить на сегодня") { model.finish(completed: false) }
                    } else {
                        primary(model.state.phase == .finished ? "Начать следующий отрезок" : "Начать · 25 минут", icon: "play.fill") { model.start() }
                            .disabled(model.state.taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }.controlSize(.large)
                Divider().overlay(.white.opacity(0.08))
                VStack(alignment: .leading, spacing: 12) {
                    HStack { Text(model.report.isFixture ? "Тестовая сводка" : "Учтено по таймеру").font(.system(size: 13, weight: .semibold)); Spacer(); if let day = model.report.day { Text(day).font(.system(size: 10, design: .monospaced)).foregroundStyle(.secondary) } }
                    HStack(spacing: 30) {
                        summaryValue("Работа", model.report.workTimerHours)
                        Divider().frame(height: 42)
                        summaryValue("Учёба", model.report.studyTimerHours)
                    }
                    Text(model.report.quality).font(.system(size: 10)).foregroundStyle(.secondary)
                    DisclosureGroup("Наблюдаемый контекст — отдельно от внимания") {
                        Text("Работа: \(DailyReportSnapshot.hoursText(model.report.workContextHours)) · Учёба: \(DailyReportSnapshot.hoursText(model.report.studyContextHours))").font(.system(size: 10))
                        Text("Доступность экрана, чтение и полезный результат этим показателем не доказаны.").font(.system(size: 10)).foregroundStyle(.secondary)
                    }.font(.system(size: 10)).foregroundStyle(.secondary)
                }.padding(17).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14))
                #if !SYSTEM_PROTECTION
                VStack(alignment: .leading, spacing: 10) {
                    Text("Окно ограничений · Минск").font(.system(size: 13, weight: .semibold))
                    #if USER_GUARD_MAIN
                    Text(model.removalStatusReady ? "Служба ответила; фактическое действие на цели проверяется отдельно." : "Связь со службой не подтверждена.").font(.caption)
                    #if USER_GUARD_QA
                    Text("Только synthetic QA: окно 150 секунд; реальные приложения и сеть недоступны.").font(.caption)
                    Text(qaChildStatus).font(.caption)
                    Button("Запустить synthetic процесс") {
                        if qaChild?.isRunning == true { qaChildStatus = "Процесс уже запущен." }
                        else if let url = Bundle.main.url(forAuxiliaryExecutable: "GuardFixture") {
                            do { let p = Process(); p.executableURL = url; p.standardOutput = FileHandle.nullDevice; try p.run(); qaChild = p; qaChildStatus = "Синтетический процесс запущен." }
                            catch { qaChildStatus = "Не удалось запустить процесс." }
                        }
                    }.font(.caption)
                    HStack {
                        Button("Включить службу") { userService.register() }
                        Button("Удалить тестовые данные") { Task { await model.clearUserFixtureData(userService) } }.disabled(!model.canUnregisterUserService)
                        Button("Отключить службу") { Task { await model.unregisterUserService(userService) } }.disabled(!model.canUnregisterUserService)
                        Button("Проверить связь") { userService.refresh(); Task { await model.syncCandidate() } }
                        Button("Повторить проект окна") { Task { await model.retryUserEnrollment() } }.disabled(!model.canRetryUserEnrollment || model.candidateBusy)
                    }.font(.caption)
                    Text(userService.message).font(.caption)
                    if model.removalRecoveryAvailable {
                        Text("Связь не подтверждена; восстановление отключает тестовую службу без подтверждения окна.").font(.caption)
                        Button("Отключить без связи · восстановление") { model.recoverUserService(userService) }.disabled(model.candidateBusy)
                    }
                    #else
                    HStack {
                        Button("Проверить связь") { Task { await model.refreshUserProtection(manual: manualService, sm: userService) } }
                        Button("Включить службу") { Task { await manualService.perform("install", sm: userService) } }.disabled(manualService.state != "absent")
                        Button("Обновить службу") { Task { await manualService.perform("update", sm: userService) } }.disabled(manualService.state != "ready")
                        Button("Отключить службу") { Task { await manualService.perform("uninstall", sm: userService) } }.disabled(manualService.state != "ready")
                        Button("Повторить проект окна") { Task { await model.retryUserEnrollment() } }.disabled(!model.canRetryUserEnrollment || model.candidateBusy)
                    }.font(.caption).disabled(manualService.busy).task {
                        while !Task.isCancelled { await manualService.refresh(); try? await Task.sleep(for: .seconds(15)) }
                    }
                    Text(manualService.message).font(.caption).foregroundStyle(.secondary)
                    if userService.state == .missing || userService.state == .unknown {
                        Text("Состояние системной регистрации SM неизвестно. Менеджер проверяет работающие службы и сокет; будущий запуск другой регистрации не исключён.").font(.caption).foregroundStyle(.secondary)
                    }
                    Text("Служба включается только по вашему действию. Сохранённое расписание может возобновиться. При неизвестном состоянии доступны системные средства восстановления; история и бюджет сохраняются.").font(.caption).foregroundStyle(.secondary)
                    DisclosureGroup("Дополнительная диагностика SM") {
                        Text("Регистрация SM: \(String(describing: userService.state)). \(userService.message)").font(.caption)
                        HStack {
                            Button("Включить SM") { userService.register() }.disabled(manualService.state != "absent" || manualService.busy)
                            Button("Отключить SM") { Task { await model.unregisterUserService(userService) } }.disabled(!model.canUnregisterUserService)
                        }
                        if model.removalRecoveryAvailable {
                            Text("Связь не подтверждена; восстановление отключает SM без подтверждения окна, сохраняя данные.").font(.caption)
                            Button("Отключить SM без связи · восстановление") { model.recoverUserService(userService) }.disabled(model.candidateBusy)
                        }
                    }.font(.caption)
                    #endif
                    #endif
                    #if FOCUSDECK_SM_FIXTURE
                    Text("Тест системной службы: \(String(describing: calendarService.state)). Только синтетическая цель; защита личных приложений не подтверждена.").font(.caption)
                    Text(calendarService.message).font(.caption)
                    #if USER_CALENDAR_FIXTURE
                    Text("Пользователь может остановить эту службу. Работа личных приложений и фильтрация сайтов не проверены.").font(.caption)
                    Text(syntheticStatus).font(.caption)
                    Button("Запустить синтетический процесс") {
                        if syntheticProcess?.isRunning == true { syntheticStatus = "Синтетический процесс уже запущен." }
                        else if let url = Bundle.main.url(forAuxiliaryExecutable: "GuardFixture") {
                            do { let process = Process(); process.executableURL = url; process.standardOutput = FileHandle.nullDevice; try process.run(); syntheticProcess = process; syntheticStatus = "Запущен собственный синтетический процесс; проверьте его состояние." }
                            catch { syntheticStatus = "Не удалось запустить синтетический процесс." }
                        }
                    }.font(.caption)
                    #endif
                    HStack {
                        Button("Включить тестовую службу") { calendarService.register() }
                        Button("Начать тестовое окно") { model.startCalendarFixture() }
                        Button("Удалить тестовые данные") {
                            #if USER_CALENDAR_FIXTURE
                            model.fixtureServiceDisconnected()
                            #endif
                            calendarService.clearFixtureData()
                        }
                        Button("Отключить тестовую службу") {
                            #if USER_CALENDAR_FIXTURE
                            model.fixtureServiceDisconnected()
                            #endif
                            calendarService.unregister()
                        }
                        Button("Обновить") {
                            calendarService.refresh()
                            #if USER_CALENDAR_FIXTURE
                            if calendarService.state != .enabled { model.fixtureServiceDisconnected() }
                            #endif
                        }
                        Button("Системные настройки") { SMAppService.openSystemSettingsLoginItems() }
                    }.font(.caption)
                    #endif
                    Text(model.windowMessage).font(.caption).foregroundStyle(.orange)
                    #if USER_GUARD_MAIN
                    if model.windowPolicy != nil { Text(model.candidateStatus).font(.caption).foregroundStyle(.secondary) }
                    #else
                    if model.windowPolicy?.targetRefs == ["dev.stanislav.focusdeck.guard-fixture"] { Text(model.fixtureBridgeStatus).font(.caption).foregroundStyle(.secondary) }
                    else if model.windowPolicy != nil { Text(model.candidateStatus).font(.caption).foregroundStyle(.secondary) }
                    #endif
                    if !model.trustProposalStatus.isEmpty {
                        HStack { Text(model.trustProposalStatus).font(.caption).foregroundStyle(.secondary)
                            if FileManager.default.fileExists(atPath: model.trustProposalURL.path) { Button("Показать проект настройки") { model.revealTrustProposal() }.font(.caption) }
                        }
                    }
                    if let schedule = model.windowSchedule {
                        HStack { Text("Расписание сохранено · дни недели: \(schedule.weekdays.map(String.init).joined(separator: ", ")) (1 = пн). Состояние службы показано выше.").font(.caption); Button("Отключить будущие окна") { model.stopSchedule() } }
                    }
                    if let policy = model.windowPolicy {
                        Text("\(model.isLegacyFixture(policy) || model.candidateAuthoritative ? "Состояние" : "Последнее состояние · связь неизвестна"): \(policy.state.rawValue) · до \(policy.endsAt.formatted(date: .abbreviated, time: .shortened)) · остаток послабления \(SessionModel.reliefBalanceText(seconds: policy.reliefRemainingSeconds))")
                            .font(.caption)
                        Text("Цели: \(policy.targetRefs.joined(separator: ", "))").font(.caption).textSelection(.enabled)
                        if let domains = policy.siteDomains { Text("\(policy.siteSubdomains == true ? "Сайты и их поддомены" : "Точные домены сайтов"): \(domains.joined(separator: ", "))").font(.caption).textSelection(.enabled) }
                        HStack {
                            Button("Послабление") { model.windowAction("relief") }.disabled(policy.state != .active || policy.reliefRemainingSeconds == 0)
                            Button("Отдых без лент") { model.windowAction("rest") }.disabled(policy.state != .active)
                            Button("Вернуться") { model.windowAction("returned") }.disabled(policy.state != .rest && policy.state != .limitedRelief)
                            if policy.state == .exitPending {
                                Button("Отменить выход") { model.windowAction("exit_cancelled") }
                                Button("Подтвердить полный выход") { model.confirmWindowExit = true }.disabled(!policy.canConfirmExit(now: Date()) || (model.isLegacyFixture(policy) && (model.fixtureAcknowledgedWindow != policy.windowId || model.fixtureAcknowledgedState != .exitPending)))
                            } else {
                                Button("Запросить полный выход") { model.windowAction("exit_requested") }.disabled(policy.state == .exit || policy.state == .expired)
                            }
                        }.disabled(model.candidateBusy || model.fixtureNeedsAcknowledgement || (!model.isLegacyFixture(policy) && !model.candidateAuthoritative))
                        if model.candidateBusy { Text("Проверяем состояние помощника…").font(.caption).foregroundStyle(.secondary) }
                        if policy.state == .exitPending, let available = policy.exitAvailableAt {
                            Text("Выход можно подтвердить после \(available.formatted(date: .omitted, time: .standard)). Задержка сохраняется при перезапуске. Если проверка времени недоступна после перезагрузки, отмените запрос и создайте его снова. Для тестового помощника нужно подтверждение связи; системный обход доступен.").font(.caption).foregroundStyle(.secondary)
                        }
                        Text("Послабление резервирует весь выбранный срок сразу; ранний возврат остаток не восстанавливает. Конец окна не переносится. Пока помощник не подключён, действия меняют только сохранённое состояние, не блокируют цели.").font(.caption).foregroundStyle(.secondary)
                    }
                    #if USER_GUARD_MAIN && !USER_GUARD_QA
                    HStack {
                        Button("Зарегистрировать мост Chrome") { model.manageChromeHost(install: true) }
                        Button("Показать расширение") { model.revealChromeExtension() }
                        Button("Удалить мост Chrome") { model.manageChromeHost(install: false) }.disabled(!model.canRemoveChromeHost)
                    }.disabled(model.chromeSiteBusy)
                    Text("В Chrome откройте chrome://extensions, включите режим разработчика и загрузите показанную папку. Значок расширения сообщает состояние; эту сборку нужно проверять отдельно.").font(.caption).foregroundStyle(.secondary)
                    Text(model.chromeSiteStatus).font(.caption).foregroundStyle(.secondary)
                    if model.removalStatusReady && !model.canRemoveChromeHost && !model.candidateBusy { Text("Чтобы удалить мост во время окна сайтов, сначала подтвердите полный выход. Расширение можно отключить в Chrome.").font(.caption).foregroundStyle(.secondary) }
                    if model.removalRecoveryAvailable {
                        Button("Удалить мост без связи · восстановление") { model.manageChromeHost(install: false, recovery: true) }.disabled(model.chromeSiteBusy || model.candidateBusy)
                    }
                    #endif
                    if !model.windowStorageBlocked && (model.windowPolicy == nil || model.windowPolicy?.state == .exit || model.windowPolicy?.state == .expired) {
                        Text("Настройка времени отображается в часовом поясе устройства; проверка начала и дня выполняется по Минску.").font(.caption).foregroundStyle(.secondary)
                        DatePicker("Начало", selection: $model.windowStart).datePickerStyle(.compact)
                        DatePicker("Конец", selection: $model.windowEnd).datePickerStyle(.compact)
                        Button("Выбрать подписанные приложения…") { model.chooseProtectionTargets() }
                        ForEach(model.selectedProtectionTargets) { target in Text("\(target.name) · \(target.id)").font(.caption).textSelection(.enabled) }
                        if !model.selectingProtectionStatus.isEmpty { Text(model.selectingProtectionStatus).font(.caption).foregroundStyle(.secondary) }
                        #if USER_GUARD_MAIN && !USER_GUARD_QA
                        TextField("Домены сайтов через запятую", text: $model.windowSites)
                        Text("Каждый домен включает свои поддомены. Для сайтов нужен установленный компонент Chrome; сохранение окна не подтверждает его работу.").font(.caption).foregroundStyle(.secondary)
                        if model.selectedSiteDomains == nil { Text("Укажите корректные домены без адресов страниц, портов и защищённой рабочей инфраструктуры.").font(.caption).foregroundStyle(.orange) }
                        #endif
                        Text("Строгий режим с длительным выходом: состояние системной защиты неизвестно. Он пока недоступен; настройки ниже относятся к обычному окну.")
                            .font(.caption).foregroundStyle(.secondary)
                        Stepper("Общий запас: \(model.windowBudgetMinutes) мин", value: $model.windowBudgetMinutes, in: 0...60)
                        Stepper("Задержка полного выхода: \(model.exitCoolingOffSeconds) сек", value: $model.exitCoolingOffSeconds, in: 5...120, step: 5)
                        Stepper("Одно послабление: \(model.windowGrantMinutes) мин", value: $model.windowGrantMinutes, in: 1...15)
                        Text("Повторять в выбранные дни:").font(.caption)
                        HStack { ForEach(1...7, id: \.self) { day in
                            Button(["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"][day - 1]) {
                                if model.recurringWeekdays.contains(day) { model.recurringWeekdays.remove(day) } else { model.recurringWeekdays.insert(day) }
                            }.foregroundStyle(model.recurringWeekdays.contains(day) ? accent : .secondary)
                        } }
                        Button("Проверить и сохранить окно") { model.confirmProtectionSetup = true }.disabled(!model.canSaveProtectionSetup)
                    }
                }.padding(17).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14))
                .confirmationDialog("Сохранить выбранные ограничения?", isPresented: $model.confirmProtectionSetup, titleVisibility: .visible) {
                    Button("Сохранить выбранную политику") { model.saveWindow() }
                    Button("Отмена", role: .cancel) { }
                } message: {
                    Text("Приложения: \(model.selectedProtectionTargets.map { $0.name + " · " + $0.id }.joined(separator: ", "))\nДомены и их поддомены: \((model.selectedSiteDomains ?? []).joined(separator: ", "))\nЗавершение приложения, включая принудительное, может привести к потере несохранённых данных. Для сайтов требуется отдельно подключённый компонент Chrome. Выход доступен с выбранной задержкой. Само сохранение не подтверждает работу помощника.")
                }
                .alert("Завершить окно ограничений?", isPresented: $model.confirmWindowExit) {
                    Button("Полный выход", role: .destructive) { model.windowAction("exit") }
                    Button("Отмена", role: .cancel) { }
                } message: { Text("Окно завершится до его установленного конца. Контекст задачи сохранится.") }
                #endif
                #if !SYSTEM_PROTECTION
                HStack(spacing: 8) {
                    Image(systemName: "shield").foregroundStyle(.secondary)
                    Text("Внешняя защита · не подключена").font(.system(size: 11)).foregroundStyle(.secondary)
                    Spacer()
                    Text("Сеанс доступен").font(.system(size: 10)).foregroundStyle(accent)
                }
                #endif
                DisclosureGroup(model.quietStatus) {
                    Toggle("Использовать бережный Focus в новых учебных сеансах", isOn: $model.quietEnabled)
                        .disabled(!model.quietConfigured || model.isolated).onChange(of: model.quietEnabled) { model.saveQuietPreference() }
                    Text("Существующий Focus сохраняется. Если его нет, проверенная команда может включить собственный режим максимум на 30 минут. Ранний Stop его не выключает; режим можно изменить в Пункте управления. Системная синхронизация Focus остаётся вашей.").font(.system(size: 10)).foregroundStyle(.secondary)
                }.font(.system(size: 10)).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 9) {
                    Text("Где продолжить").font(.system(size: 13, weight: .semibold))
                    Text(model.returnReview.nextStep(taskRef: model.state.taskRef, taskTitle: model.state.taskTitle, note: model.state.note))
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                    TextField("Оставьте себе следующий шаг — необязательно", text: $model.state.note)
                        .focused($inputFocus, equals: .note)
                        .textFieldStyle(.plain).font(.system(size: 13)).padding(11).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 9))
                        .onChange(of: model.state.note) { model.persist() }

                }
                Spacer(minLength: 0)
                HStack {
                    Text("Таймер ≠ измеренное внимание. Полезный результат пока не оценён.").font(.system(size: 10)).foregroundStyle(.secondary)
                    Spacer()
                    Button("Журнал") { model.revealJournal() }.buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(accent)
                }
                DisclosureGroup("Локальный сбор · независимо от окна приложения") {
                    Text("Локальные процессы обзора, приёма событий и контекста устанавливаются отдельно. Они могут продолжать работу после закрытия или удаления .app до явного отключения. Контекст хранится 7 дней, отчёты — 90 дней. Содержимое окон, клавиши и адреса вкладок не сохраняются.").font(.system(size: 10)).foregroundStyle(.secondary)
                    Text("Данные: ~/Library/Application Support/FocusDeckHarness; журнал приложения: ~/Library/Application Support/FocusDeckStudy. Остановка сбора не удаляет историю или задачу.").font(.system(size: 10)).foregroundStyle(.secondary).textSelection(.enabled)
                    HStack {
                        Button("Остановить независимый сбор") { Task { await model.controlCollection(start: false) } }
                        Button("Включить по моему выбору") { Task { await model.controlCollection(start: true) } }
                    }.disabled(model.isolated || model.controllingCollection)
                    if model.isolated { Text("Управление службами отключено в изолированной проверке").font(.system(size: 10)) }
                    if !model.collectionControlStatus.isEmpty { Text(model.collectionControlStatus).font(.system(size: 10)) }

                }.font(.system(size: 11)).foregroundStyle(.secondary)
                DisclosureGroup("Недельный обзор и источники") {
                    if model.isolated { Text("Тестовые данные").font(.system(size: 10, weight: .semibold)) }
                    Text(model.returnReview.weeklyText).font(.system(size: 11))
                    if let revision = model.windowPolicy?.policyRevisionId {
                        Text(model.returnReview.committedRevisions.contains(revision) ? "Эта ревизия политики сохранена в локальной истории обзора. Действие защиты проверяется отдельно." : "Сохранение текущей ревизии в истории обзора не подтверждено; источник может быть неполным.").font(.system(size: 10))
                    }
                    Text("Возврат к политике не доказывает продолжение работы. Длительность послабления и полнота событий неизвестны; автоматического усиления нет.").font(.system(size: 10))
                }.font(.system(size: 11)).foregroundStyle(.secondary)
                DisclosureGroup("Добровольное возвращение · локально") {
                    Text(model.recoveryStatus).font(.system(size: 11))
                    Divider()
                    Text("Локальные напоминания").font(.system(size: 11, weight: .semibold))
                    Text("\(model.reminderStatus) · Разрешение: \(model.reminderPermission)").font(.system(size: 10))
                    Text("Тихие часы 22:00–08:00 по Минску; не чаще раза в сутки, до трёх попыток. Следующий шаг может быть виден на экране блокировки. Доставка не подтверждена.").font(.system(size: 10))
                    HStack {
                        Button("Проверить статус") { Task { await model.reminderAction("status") } }
                        Button("Запросить разрешение") { Task { await model.reminderAction("request-permission") } }
                        Button("Включить напоминания") { Task { await model.reminderAction("enable") } }
                        Button("Остановить напоминания") { Task { await model.reminderAction("stop") } }
                    }.disabled(model.reminderBusy || model.isolated)
                    if let enabled = model.reminderEnabled { Text(enabled ? "Выбор сохранён: включены" : "Выбор сохранён: выключены").font(.system(size: 10)) }
                    Text("Проверка использует ранее выбранный источник Android. Отсутствие событий не означает удаление; сообщения вовне не отправляются. Отключение сохраняет историю.").font(.system(size: 10))
                    if let enabled = model.recoveryEnabled {
                        Button(enabled ? "Отключить проверку" : "Включить прежний источник") { Task { await model.setRecovery(!enabled) } }.disabled(model.recoveryBusy || model.isolated)
                    }
                }.font(.system(size: 11)).foregroundStyle(.secondary)
                Text(model.connection).font(.system(size: 10)).foregroundStyle(.secondary)
                if let error = model.error {
                    HStack { Text(error).foregroundStyle(.red).font(.caption)
                        if model.storageBlocked { Button("Сохранить исходник и начать заново") { model.archiveCorruptContext() } }
                    }
                }
            }.padding(30).foregroundStyle(ink) }
                .defaultScrollAnchor(.top, for: .initialOffset)
                .onAppear {
                    inputFocus = active ? nil : .task
                    Task { try? await Task.sleep(for: .milliseconds(100)); proxy.scrollTo("sessionTop", anchor: .top) }
                }
            }
            .sheet(isPresented: $model.choosingTask) {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Выберите ближайшее действие").font(.title2)
                    List(model.taskChoices) { choice in
                        Button { model.selectTask(choice) } label: {
                            HStack { Text(choice.title).lineLimit(2); Spacer(); Image(systemName: "arrow.up.right") }
                        }.buttonStyle(.plain).padding(.vertical, 5)
                    }
                    Button("Отмена") { model.choosingTask = false }
                }.padding(24).frame(width: 560, height: 420)
            }
        }
    }
    func summaryValue(_ title: String, _ hours: Double?) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 11)).foregroundStyle(.secondary)
            Text(DailyReportSnapshot.hoursText(hours)).font(.system(size: hours == nil ? 17 : 24, weight: .medium, design: .rounded))
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
    func category(_ title: String, _ value: String) -> some View {
        Button { model.state.category = value; model.persist() } label: {
            Text(title).font(.system(size: 13, weight: .medium)).padding(.horizontal, 17).padding(.vertical, 9)
                .background(model.state.category == value ? accent.opacity(0.16) : .white.opacity(0.04), in: Capsule())
                .foregroundStyle(model.state.category == value ? accent : ink.opacity(0.6))
        }.buttonStyle(.plain)
    }
    func primary(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) { Label(title, systemImage: icon).font(.system(size: 14, weight: .semibold)).padding(.horizontal, 17).padding(.vertical, 12) }
            .buttonStyle(.plain).background(accent, in: RoundedRectangle(cornerRadius: 11)).foregroundStyle(.black)
    }
}

import SwiftUI
import AppKit
import UniformTypeIdentifiers

@MainActor
final class SessionModel: ObservableObject {
    @Published var taskChoices: [ObsidianTaskChoice] = []
    @Published var choosingTask = false
    @Published var taskBindingStatus = ""
    private var boundTitle: String?
    @Published var state = SessionState()
    @Published var error: String?
    @Published var restored = false
    @Published var rootArmed = false
    @Published var rootHostsEnabled = false
    @Published var reviewingRoot = false
    @Published var rootAcknowledged = false
    @Published var rootBusy = false
    @Published var rootStatus = "Root-помощник не подтверждён · защита не заявлена"
    let rootGuard = PrivilegedAppGuard()
    var blockedAppTargets: [String] { browserPolicy?.profile(category: state.category).blockedAppBundleIds ?? [] }
    var reviewAppTargets: [String] { browserPolicy?.blockedAppBundleIds ?? [] }
    var blockedDomainTargets: [String] {
        guard let root = Bundle.main.resourceURL?.appendingPathComponent("RootGuard/config/study.json"),
              let data = try? Data(contentsOf: root), let policy = try? JSONDecoder().decode(StudyPolicy.self, from: data) else { return [] }
        return policy.profile(category: state.category).blockedDomains
    }
    @Published var browserEnabled = false
    @Published var browserStatus = "Контроль вкладок выключен"
    private let browser = BrowserCoordinator()
    private lazy var browserPolicy: StudyPolicy? = {
        guard let path = Bundle.main.url(forResource: "study", withExtension: "json"),
              let data = try? Data(contentsOf: path) else { return nil }
        return try? JSONDecoder().decode(StudyPolicy.self, from: data)
    }()
    @Published var storageBlocked = false
    @Published var collectionControlStatus = ""
    @Published var controllingCollection = false
    var isolated: Bool { ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"] != nil }
    @Published var reportSummary = "Сводка ещё не доступна · часы неизвестны"
    private var reportReadAt = Date.distantPast
    @Published var connection = "Локальная связь ещё не проверена"
    private let receiverOverride: (any ReceiverSyncing)?
    private lazy var receiver: any ReceiverSyncing = receiverOverride ?? LocalReceiver(journal: store)
    let store: SessionStore
    private var tickTime = ProcessInfo.processInfo.systemUptime
    init(receiver: (any ReceiverSyncing)? = nil) {
        receiverOverride = receiver
        let override = ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"]
        let root = override.map { URL(fileURLWithPath: $0, isDirectory: true) } ??
            FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("FocusDeckStudy", isDirectory: true)
        store = SessionStore(directory: root)
        do {
            if var saved = try store.load() {
                restored = saved.phase == .running || saved.phase == .paused
                if saved.phase == .running, let interval = saved.closeSegment(now: saved.lastTick ?? Date()), interval.1 > interval.0 {
                    try store.append(SessionEvent(state: saved, type: "paused", now: Date(), interval: interval, reason: "persisted_known_interval_before_relaunch_gap"))
                }
                saved.recoverAfterRelaunch(); state = saved; rootArmed = saved.rootRequested; rootHostsEnabled = saved.hostsRequested; boundTitle = saved.taskRef.isEmpty ? nil : saved.taskTitle
                if restored { try emit("health", reason: "relaunch_gap_unknown"); try store.save(state) }
            }
        } catch { storageBlocked = true; self.error = "Сохранённый контекст повреждён. Исходный файл сохранён; запись остановлена." }
    }
    func chooseExistingTask() {
        guard ![.running, .paused, .returnReady].contains(state.phase) else { return }
        let panel = NSOpenPanel(); panel.canChooseDirectories = false; panel.allowsMultipleSelection = false
        panel.allowedContentTypes = [.plainText, .text]
        panel.message = "Выберите одну дневную заметку. FocusDeck прочитает только раздел «Задачи» и не изменит файл."
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            taskChoices = try ObsidianTaskCatalog.readSelectedNote(url).filter { !$0.done }
            if taskChoices.isEmpty { taskBindingStatus = "В разделе «Задачи» нет открытых задач." }
            else { choosingTask = true }
        } catch { self.error = error.localizedDescription }
    }
    func selectTask(_ choice: ObsidianTaskChoice) {
        boundTitle = choice.title; state.taskTitle = choice.title; state.taskRef = choice.stableID ?? ""; state.note = ""
        taskBindingStatus = choice.stableID == nil ? "У задачи нет устойчивого ID — межустройственная связь пока недоступна." : "Связано с существующей задачей"
        choosingTask = false; persist()
    }
    func titleChanged() {
        if let boundTitle, state.taskTitle != boundTitle { state.taskRef = ""; self.boundTitle = nil; taskBindingStatus = "Связь сброшена: название изменено" }
        persist()
    }
    func persist() { guard !storageBlocked else { return }; do { try store.save(state) } catch { self.error = "Не удалось сохранить контекст: \(error.localizedDescription)" } }
    func emit(_ type: String, interval: (Date, Date)? = nil, reason: String? = nil) throws {
        try store.append(SessionEvent(state: state, type: type, now: Date(), interval: interval, reason: reason))
    }
    func start() {
        guard !storageBlocked, !state.taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        state.start(now: Date()); state.rootRequested = rootArmed && rootAcknowledged && !isolated; state.hostsRequested = rootHostsEnabled
        browser.start(); restored = false; tickTime = ProcessInfo.processInfo.systemUptime
        record("started")
        if state.rootRequested, let id = state.sessionId {
            Task { _ = await rootGuard.request(command: "start", sessionId: id, hosts: state.hostsRequested, category: state.category, isolated: isolated); rootStatus = rootGuard.status }
        }
    }
    func pause() {
        guard state.phase == .running, !state.usedPause else { return }
        browser.grantPause()
        let interval = state.pause(now: Date()); record("paused", interval: interval, reason: "bounded_break_300_seconds")
        if state.rootRequested, let id = state.sessionId {
            Task {
                let accepted = await rootGuard.request(command: "pause", sessionId: id, isolated: isolated)
                if accepted, state.phase == .paused, state.sessionId == id, let deadline = rootGuard.pauseUntil { state.pauseUntil = deadline; state.pauseRemainingSeconds = min(300, max(0, deadline.timeIntervalSinceNow)); persist() }
                rootStatus = rootGuard.status
            }
        }
    }
    func resume() {
        state.resume(now: Date()); tickTime = ProcessInfo.processInfo.systemUptime; restored = false
        record("resumed", reason: "explicit_return")
        if state.rootRequested, let id = state.sessionId { Task { _ = await rootGuard.request(command: "resume", sessionId: id, isolated: isolated); rootStatus = rootGuard.status } }
    }
    func finish(completed: Bool) {
        let interval = state.closeSegment(now: Date()); state.phase = .finished; state.pauseUntil = nil; browser.stop()
        record(completed ? "completed" : "stopped", interval: interval)
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
    func pollBrowser() async {
        await browser.poll(enabled: browserEnabled, active: state.phase == .running || (state.phase == .returnReady && !restored), category: state.category)
        browserStatus = browser.status
    }
    func tick() {
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
    func rootAdministratorAction(_ action: String) async {
        guard !rootBusy else { return }; rootBusy = true
        rootStatus = "Ожидается отдельное системное разрешение macOS; блокировка сама не включается"
        rootStatus = await rootGuard.administratorAction(action, isolated: isolated); rootBusy = false
    }
    func sync() async {
        await rootGuard.observe(expectedSession: state.sessionId, expectedTargets: blockedAppTargets, expectedDomains: blockedDomainTargets, isolated: isolated)
        rootStatus = rootGuard.status

        await receiver.sync(); connection = receiver.status
        guard ProcessInfo.processInfo.environment["FOCUSDECK_DATA_DIR"] == nil else { return }
        guard Date().timeIntervalSince(reportReadAt) > 30 else { return }; reportReadAt = Date()
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let report = support.appendingPathComponent("FocusDeckHarness/reports/current-day.json")
        guard let data = try? Data(contentsOf: report), let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let day = root["day"] as? String, let categories = root["categories"] as? [String: [String: Any]] else {
            reportSummary = "Сводка недоступна · часы неизвестны"; return
        }
        func hours(_ category: String) -> String {
            guard let byEvidence = categories[category]?["focusHoursByEvidence"] as? [String: Any],
                  let timer = byEvidence["timer"] as? Double else { return "нет данных" }
            return String(format: "%.2f ч по таймеру", timer)
        }
        let check = root["dailyCheck"] as? [String: Any]
        let freshness = check?["state"] as? String
        let suffix = freshness == "clock_skew" ? " · часы источника не согласованы; полнота неизвестна" : freshness == "stale" ? " · источники устарели" : freshness == "no_data" ? " · нет наблюдений" : ""
        reportSummary = "\(day) · Работа: \(hours("work")) · Учёба: \(hours("study"))\(suffix)"
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
@main
struct FocusDeckApp: App {
    @StateObject private var model = SessionModel()
    var body: some Scene {
        WindowGroup("FocusDeck") {
            FocusDeckView(model: model)
                .frame(minWidth: 680, idealWidth: 780, minHeight: 680, idealHeight: 760)
                .preferredColorScheme(.dark)
                .task { while !Task.isCancelled { try? await Task.sleep(for: .seconds(1)); model.tick() } }
                .task { while !Task.isCancelled { await model.sync(); try? await Task.sleep(for: .seconds(5)) } }
                .task { while !Task.isCancelled { await model.pollBrowser(); try? await Task.sleep(for: .seconds(2)) } }
        }
        .windowStyle(.hiddenTitleBar)
        .commands { CommandGroup(replacing: .newItem) {} }
    }
}

#endif

struct FocusDeckView: View {
    @ObservedObject var model: SessionModel
    private let ink = Color(red: 0.92, green: 0.94, blue: 0.95)
    private let accent = Color(red: 0.69, green: 0.94, blue: 0.44)
    var active: Bool { [.running, .paused, .returnReady].contains(model.state.phase) }
    var body: some View {
        ZStack {
            Color(red: 0.055, green: 0.065, blue: 0.07).ignoresSafeArea()
            ScrollView { VStack(alignment: .leading, spacing: 25) {
                HStack {
                    HStack(spacing: 9) { Image(systemName: "circle.hexagongrid.fill").foregroundStyle(accent); Text("FocusDeck").font(.system(size: 17, weight: .semibold)) }
                    Spacer()
                    Text("MAC · ЛОКАЛЬНО").font(.system(size: 10, weight: .bold, design: .monospaced)).foregroundStyle(.secondary)
                }
                HStack(alignment: .top, spacing: 28) {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(active ? (model.state.phase == .returnReady ? "Вернуться к делу" : "Одно дело сейчас") : "Что сделаем сейчас?")
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                        if active {
                            Text(model.state.taskTitle).font(.system(size: 20, weight: .medium)).textSelection(.enabled)
                            Text(model.state.category == "work" ? "Работа" : "Учёба").foregroundStyle(accent)
                        } else {
                            TextField("Ближайшее действие", text: $model.state.taskTitle)
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
                        if model.state.phase == .returnReady { Text("Перерыв окончен. Продолжите одним нажатием — работа сама не возобновляется.").font(.callout).foregroundStyle(.secondary) }
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
                HStack(alignment: .top, spacing: 13) {
                    Image(systemName: "shield.lefthalf.filled.trianglebadge.exclamationmark").font(.system(size: 23)).foregroundStyle(.orange)
                    VStack(alignment: .leading, spacing: 7) {
                        Text(model.rootGuard.observedAppsActive ? "Наблюдается частичная root-политика" : "Полная защита не подтверждена").font(.system(size: 15, weight: .semibold))
                        Text(model.rootGuard.observedAppsActive ? "Приложения: состояние root-политики наблюдается ниже. DNS ограничивает только точные системные имена; DoH, кэш, прокси и встроенные страницы могут обходить его. Это не полная сетевая защита." : "Доступны задача, таймер, перерыв и локальный журнал. Контроль приложений, точных DNS-имён и вкладок включается отдельно после проверки списка. Пока его состояние не подтверждено, защита не заявляется.")
                            .font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                    Text("НЕ ЗАЩИЩЕНО").font(.system(size: 9, weight: .bold, design: .monospaced)).padding(8).background(.orange.opacity(0.1), in: Capsule()).foregroundStyle(.orange)
                }.padding(17).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 14))
                Text("Уведомления: macOS Focus ещё не связан с сеансом").font(.system(size: 10)).foregroundStyle(.orange)
                DisclosureGroup("Контроль приложений от администратора · отдельная установка") {
                    Text(model.rootStatus).font(.system(size: 10)).foregroundStyle(.secondary)
                    Text("Только известные приложения и, по выбору, точные DNS-имена. 25 минут работы + один перерыв до 5 минут; общий предел 30 минут. После более долгого отсутствия нужен новый ограниченный запрос. Остановка таймера не снимает root-политику — доступно аварийное восстановление.").font(.system(size: 10)).foregroundStyle(.secondary)
                    HStack {
                        Button("Проверить список и установить…") { model.reviewingRoot = true }
                        Button("Аварийно снять ограничения…") { Task { await model.rootAdministratorAction("--recover") } }
                    }.disabled(model.isolated || model.rootBusy)
                    Toggle("Запросить root-политику при следующем старте", isOn: $model.rootArmed).disabled(!model.rootAcknowledged || active || model.isolated).font(.system(size: 11))
                }.font(.system(size: 11)).foregroundStyle(.secondary)
                DisclosureGroup("Пробный контроль вкладок · отдельное разрешение macOS") {
                    Toggle("Заменять отвлекающие вкладки пустой страницей", isOn: $model.browserEnabled).toggleStyle(.switch).font(.system(size: 11))
                    Text("Safari, Chrome и Opera GX; примерно каждые 2 секунды; 25 минут + один перерыв до 5 минут. Возможны обходы через другие браузеры, приватные окна и встроенные страницы. Вкладки могут потерять несохранённый ввод. По умолчанию выключено.").font(.system(size: 10)).foregroundStyle(.secondary)
                    Text(model.browserStatus).font(.system(size: 10)).foregroundStyle(.secondary)
                }.font(.system(size: 11)).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 9) {
                    Text("Где продолжить").font(.system(size: 13, weight: .semibold))
                    TextField("Оставьте себе следующий шаг — необязательно", text: $model.state.note)
                        .textFieldStyle(.plain).font(.system(size: 13)).padding(11).background(.white.opacity(0.035), in: RoundedRectangle(cornerRadius: 9))
                        .onChange(of: model.state.note) { model.persist() }
                    DisclosureGroup("Связать с существующей задачей") {
                        TextField("Устойчивый ID задачи, если он уже есть", text: $model.state.taskRef)
                            .textFieldStyle(.roundedBorder).disabled(active).onChange(of: model.state.taskRef) { model.persist() }
                    }.font(.system(size: 11)).foregroundStyle(.secondary)
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
                Text(model.reportSummary).font(.system(size: 10)).foregroundStyle(.secondary)
                Text(model.connection).font(.system(size: 10)).foregroundStyle(.secondary)
                if let error = model.error {
                    HStack { Text(error).foregroundStyle(.red).font(.caption)
                        if model.storageBlocked { Button("Сохранить исходник и начать заново") { model.archiveCorruptContext() } }
                    }
                }
            }.padding(30).foregroundStyle(ink) }
            .sheet(isPresented: $model.reviewingRoot) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Проверьте ограничения до установки").font(.title2)
                    Text("Установка сама не закрывает приложения. После отдельного старта выбранная root-политика может принудительно завершать эти приложения и их новые запуски. Несохранённая работа может быть потеряна.").font(.callout)
                    List {
                        Section("Максимальный доступный набор приложений · в рабочем профиле Telegram/Outlook/Teams сохраняются") { ForEach(model.reviewAppTargets, id: \.self) { Text($0).font(.system(.caption, design: .monospaced)).textSelection(.enabled) } }
                        Section("DNS-имена — отдельный выбор") { ForEach(model.blockedDomainTargets, id: \.self) { Text($0).font(.caption).textSelection(.enabled) } }
                    }
                    Toggle("При старте также ограничивать перечисленные точные системные DNS-имена", isOn: $model.rootHostsEnabled).font(.caption)
                    Text("DoH, кэш, прокси и встроенные страницы могут обходить DNS-правила. Полная сетевая защита не заявлена. GitLab, OpenAI, Codex и VPN исключены; совместимость ещё требует реальной пробы.").font(.caption).foregroundStyle(.secondary)
                    Toggle("Список проверен, работа сохранена; понимаю риск принудительного завершения", isOn: $model.rootAcknowledged).font(.caption)
                    HStack {
                        Button("Установить помощник БЕЗ блокировки…") { Task { await model.rootAdministratorAction("--install-idle"); model.reviewingRoot = false } }.disabled(!model.rootAcknowledged || model.rootBusy || model.isolated)
                        Button("Удалить помощник…") { Task { await model.rootAdministratorAction("--remove"); model.reviewingRoot = false } }.disabled(model.rootBusy || model.isolated)
                        Button("Готово") { model.reviewingRoot = false }
                    }
                }.padding(24).frame(width: 680, height: 650)
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

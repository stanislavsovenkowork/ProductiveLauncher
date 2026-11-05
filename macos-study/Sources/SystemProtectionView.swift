import SwiftUI
import AppKit

enum SystemProtectionInstaller {
    static var bundledService: URL { Bundle.main.bundleURL.appendingPathComponent("Contents/Library/HelperTools/FocusDeckProtectionService") }
    static func hash() throws -> String {
        guard let hash = RootSocketServer.verifiedHash(at: bundledService) else { throw RootSocketFailure.invalidResponse }
        return hash
    }
    static func quoted(_ string: String) -> String { "'" + string.replacingOccurrences(of: "'", with: "'\\''") + "'" }
    static func uninstall() throws -> String {
        let command = quoted(SystemProtectionPaths.helper + "/FocusDeckProtectionService") + " --uninstall"
        let escaped = command.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", "do shell script \"" + escaped + "\" with administrator privileges"]
        let pipe = Pipe(); process.standardOutput = pipe; process.standardError = pipe
        try process.run(); let output = pipe.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw NSError(domain: "FocusDeckRemoval", code: Int(process.terminationStatus), userInfo: [NSLocalizedDescriptionKey: String(decoding: output, as: UTF8.self)])
        }
        return String(decoding: output, as: UTF8.self)
    }
    static func install() throws -> String {
        guard let clientHash = RootSocketServer.verifiedHash(pid: getpid()), getuid() >= 500 else { throw RootSocketFailure.invalidResponse }
        let binary = bundledService
        let digestProcess = Process(); digestProcess.executableURL = URL(fileURLWithPath: "/sbin/sha256")
        digestProcess.arguments = ["-q", binary.path]
        let pipe = Pipe(); digestProcess.standardOutput = pipe
        try digestProcess.run(); let data = pipe.fileHandleForReading.readDataToEndOfFile(); digestProcess.waitUntilExit()
        let digest = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        guard digestProcess.terminationStatus == 0, digest.count == 64, digest.allSatisfy(\.isHexDigit) else { throw RootSocketFailure.invalidResponse }
        let helper = SystemProtectionPaths.helper
        let staging = helper + "/.install-" + UUID().uuidString
        // Only the copy inside the root-private directory is executed. A bad
        // source copy is removed on failure; its contents are never printed.
        let script = """
        set -eu
        umask 077
        base=\(quoted(helper))
        if [ -e "$base" ]; then
          [ ! -L "$base" ] && [ "$(/usr/bin/stat -f %u "$base")" = 0 ] || exit 71
        else /bin/mkdir -m 755 "$base"; fi
        /bin/chmod 755 "$base"
        stageDir=\(quoted(staging))
        /bin/mkdir -m 700 "$stageDir"
        stage="$stageDir/service"
        trap '/bin/rm -f "$stage"; /bin/rmdir "$stageDir"' EXIT
        /usr/bin/install -m 700 \(quoted(binary.path)) "$stage"
        [ "$(/sbin/sha256 -q "$stage")" = \(quoted(digest)) ] || exit 73
        /usr/bin/codesign --verify --strict "$stage"
        /bin/chmod 755 "$stage"
        /bin/mv "$stage" "$base/FocusDeckProtectionService"
        "$base/FocusDeckProtectionService" --install \(getuid()) \(clientHash)
        """
        let shell = "/usr/bin/env -i PATH=/usr/bin:/bin:/usr/sbin:/sbin LANG=C /bin/bash -c " + quoted(script)
        let escaped = shell.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"").replacingOccurrences(of: "\n", with: "\\n")
        let process = Process(); process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", "do shell script \"" + escaped + "\" with administrator privileges"]
        let result = Pipe(); process.standardOutput = result; process.standardError = result
        try process.run(); let output = result.fileHandleForReading.readDataToEndOfFile(); process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            throw NSError(domain: "FocusDeckInstallation", code: Int(process.terminationStatus), userInfo: [NSLocalizedDescriptionKey:String(decoding: output, as: UTF8.self)])
        }
        return "Системная служба установлена."
    }
}

@MainActor final class SystemProtectionModel: ObservableObject {
    @Published var reply: SystemProtectionReply?
    @Published var message = "Проверяю системную службу…"
    @Published var domains = UserDefaults.standard.string(forKey: "systemProtection.domains") ?? "youtube.com, instagram.com, tiktok.com"
    @Published var minutes: Int = {
        let saved = UserDefaults.standard.integer(forKey: "systemProtection.minutes")
        return (5...43_200).contains(saved) ? saved : 60
    }()
    @Published var busy = false
    @Published var targets: [ProtectionTarget] = []
    @Published var feedback: String?
    var domainList: [String] {
        Array(Set(domains.split(whereSeparator: { $0 == "," || $0.isWhitespace }).map { $0.lowercased() })).sorted()
    }
    func setDomains(_ values: [String]) {
        domains = Array(Set(values)).sorted().joined(separator: ", ")
        UserDefaults.standard.set(domains, forKey: "systemProtection.domains")
    }
    func addDomains(_ draft: String) -> Bool {
        guard let values = parseDomains(draft, allowEmpty: false), Set(domainList + values).count <= SiteDomainPolicy.maximumDomains else { return false }
        setDomains(domainList + values); feedback = nil; return true
    }
    func parseDomains(_ draft: String, allowEmpty: Bool = true) -> [String]? {
        if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && allowEmpty { return [] }
        let values = draft.split(whereSeparator: { $0 == "," || $0.isWhitespace }).compactMap { value -> String? in
            let raw = String(value).lowercased()
            guard let host = URL(string: raw.contains("://") ? raw : "https://" + raw)?.host else { return nil }
            return host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
        }
        let canonical = Array(Set(values)).sorted()
        guard !values.isEmpty, values.count == draft.split(whereSeparator: { $0 == "," || $0.isWhitespace }).count,
              canonical.count <= SiteDomainPolicy.maximumDomains, SiteDomainPolicy.validList(canonical, includeSubdomains: true) else {
            feedback = "Укажите домен сайта. Сетевые и рабочие средства исключены из запрета."; return nil
        }
        return canonical
    }
    var validDuration: Bool { (5...43_200).contains(minutes) }
    var durationLabel: String {
        let days = minutes / 1440, hours = minutes % 1440 / 60, rest = minutes % 60
        return [days > 0 ? "\(days) дн" : nil, hours > 0 ? "\(hours) ч" : nil, rest > 0 ? "\(rest) мин" : nil].compactMap { $0 }.joined(separator: " ")
    }
    func refresh() async {
        guard !busy else { return }
        do {
            let hash = try SystemProtectionInstaller.hash()
            reply = try await Task.detached { try SystemProtectionClient.send(.init(command: "status"), expectedHash: hash) }.value
            message = reply?.message ?? "Служба не ответила."
        } catch { reply = nil; message = "Системная служба ещё не установлена или недоступна." }
    }
    func install() async {
        guard !busy else { return }; busy = true
        do { message = try await Task.detached { try SystemProtectionInstaller.install() }.value; feedback = nil }
        catch { message = "Установка не завершена: " + error.localizedDescription; feedback = message }
        busy = false; await refresh()
    }
    func uninstall() async {
        guard !busy, reply?.active == false else { return }; busy = true
        do { message = try await Task.detached { try SystemProtectionInstaller.uninstall() }.value; reply = nil; feedback = message }
        catch { feedback = "Удаление не завершено: " + error.localizedDescription }
        busy = false
    }
    func requestExit(cancel: Bool = false) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            let hash = try SystemProtectionInstaller.hash()
            let command = cancel ? "cancel-exit" : "request-exit"
            reply = try await Task.detached { try SystemProtectionClient.send(.init(command: command), expectedHash: hash) }.value
            message = reply?.message ?? "Ответ не получен."
            feedback = message
        } catch { feedback = "Состояние выхода неизвестно. Запрет не отменён; проверяю службу." }
    }
    func chooseApps() {
        if let picked = pickApps() { targets = picked }
    }
    func pickApps() -> [ProtectionTarget]? {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.applicationBundle]; panel.allowsMultipleSelection = true
        guard panel.runModal() == .OK else { return nil }
        do { return try panel.urls.map { try ProtectionTarget.inspect($0) } }
        catch { message = "Приложение не поддерживается этой политикой: системные и сетевые средства исключены."; feedback = message; return nil }
    }
    func openBrowser() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        do {
            let hash = try SystemProtectionInstaller.hash()
            reply = try await Task.detached { try SystemProtectionClient.send(.init(command: "browser"), expectedHash: hash) }.value
            message = reply?.message ?? "Ответ не получен."
            feedback = reply?.accepted == false ? message : nil
        } catch { message = "Браузер не открыт: " + error.localizedDescription; feedback = message }
    }
    func start(validation: Bool = false, strictValidation: Bool = false) async {
        guard !busy else { return }; busy = true; defer { busy = false }
        guard validation || validDuration else { feedback = "Выберите срок от5 минут до30 дней."; return }
        let list = validation ? ["info.cern.ch"] : domains.split(whereSeparator: { $0 == "," || $0 == "\n" || $0 == " " }).map(String.init)
        UserDefaults.standard.set(domains, forKey: "systemProtection.domains")
        if !validation { UserDefaults.standard.set(minutes, forKey: "systemProtection.minutes") }
        let applications = targets.compactMap { target -> TrustedSigningRequirement? in
            guard let team = target.teamID, let requirement = target.requirement else { return nil }
            return TrustedSigningRequirement(signingID: target.id, teamID: team, requirement: requirement)
        }
        do {
            let hash = try SystemProtectionInstaller.hash()
            let request = SystemProtectionRequest(command: strictValidation ? "validate-strict" : validation ? "validate" : "start", seconds: minutes * 60, domains: list, applications: validation ? [] : applications)
            reply = try await Task.detached { try SystemProtectionClient.send(request, expectedHash: hash) }.value
            message = reply?.message ?? "Ответ не получен."
            feedback = reply?.accepted == false ? message : nil
        } catch { message = "Состояние неизвестно. Проверяю службу; не запускайте окно повторно."; feedback = message }
    }
    func addTargets(_ input: String, applications: [ProtectionTarget]) async -> Bool {
        guard !busy, let list = parseDomains(input), !list.isEmpty || !applications.isEmpty else { return false }
        busy = true; defer { busy = false }
        let trusted = applications.compactMap { target -> TrustedSigningRequirement? in
            guard let team = target.teamID, let requirement = target.requirement else { return nil }
            return .init(signingID: target.id, teamID: team, requirement: requirement)
        }
        do {
            let hash = try SystemProtectionInstaller.hash()
            reply = try await Task.detached { try SystemProtectionClient.send(.init(command: "add-targets", domains: list, applications: trusted), expectedHash: hash) }.value
            message = reply?.message ?? "Ответ не получен."
            feedback = reply?.accepted == false ? message : nil
            return reply?.accepted == true
        } catch { feedback = "Ответ о добавлении не получен. Проверяю состояние; прежний запрет не отменён."; return false }
    }
}

struct SystemProtectionView: View {
    @ObservedObject var model: SystemProtectionModel
    @State private var draft = ""
    @State private var confirming = false
    @State private var addingTargets = false
    @State private var requestingExit = false
    @State private var additionalSites = ""
    @State private var additionalApps: [ProtectionTarget] = []
    @State private var durationUnit = 60
    private let presets = [("YouTube", "youtube.com", "play.rectangle.fill"), ("Instagram", "instagram.com", "camera"), ("TikTok", "tiktok.com", "music.note")]
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let state = model.reply, state.active { activeWindow(state) }
            else if model.reply != nil { configuration }
            else { installation }
            if let feedback = model.feedback {
                HStack(alignment: .top, spacing: 9) {
                    CodexIcon(symbol: "info.circle")
                    Text(feedback).font(.system(size: 12)).textSelection(.enabled)
                    Spacer(minLength: 0)
                    Button { model.feedback = nil } label: { CodexIcon(symbol: "xmark") }.buttonStyle(.plain).help("Скрыть сообщение")
                }.foregroundStyle(.orange).padding(13).background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
            }
        }.disabled(model.busy)
            .confirmationDialog("Зафиксировать ограничения на \(model.minutes) минут?", isPresented: $confirming, titleVisibility: .visible) {
                Button("Включить на \(model.minutes) минут") { Task { await model.start() } }
                Button("Отмена", role: .cancel) { }
            } message: {
                Text("Сайтов: \(model.domainList.count). Выбранных приложений: \(model.targets.count). Другие браузеры будут закрыты, если выбраны сайты. Несохранённые данные могут потеряться. До конца срока быстрой отмены нет.")
            }
            .confirmationDialog("Запросить выход через час?", isPresented: $requestingExit, titleVisibility: .visible) {
                Button("Начать часовое ожидание") { Task { await model.requestExit() } }
                Button("Отмена", role: .cancel) { }
            } message: {
                Text("Запрет продолжит действовать. Выход произойдёт через час или в исходный конец окна, если он наступит раньше. Закрытие приложения и перезагрузка не ускоряют ожидание. Выключенное время не засчитывается.")
            }
            .sheet(isPresented: $addingTargets) { additionsSheet }
            .onChange(of: model.minutes) { if model.validDuration { UserDefaults.standard.set(model.minutes, forKey: "systemProtection.minutes") } }
    }
    private var configuration: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(spacing: 16) { sitesCard; applicationsCard }.frame(minWidth: 300, maxWidth: .infinity)
            VStack(spacing: 16) { durationCard; browserCard; commitment }.frame(minWidth: 240, maxWidth: 300)
        }
    }
    private var sitesCard: some View {
        DeckCard("Сайты", icon: "globe", subtitle: "Домен и его поддомены") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 6)], spacing: 6) {
                ForEach(presets, id: \.1) { preset in
                    let selected = model.domainList.contains(preset.1)
                    Button {
                        model.setDomains(selected ? model.domainList.filter { $0 != preset.1 } : model.domainList + [preset.1])
                    } label: {
                        HStack(spacing: 6) {
                            CodexIcon(symbol: preset.2, size: 14)
                            Text(preset.0).font(.system(size: 12)).lineLimit(1)
                            Spacer(minLength: 0)
                            if selected { CodexIcon(symbol: "checkmark", size: 12) }
                        }.foregroundStyle(selected ? DeckPalette.ink : DeckPalette.secondary)
                            .frame(maxWidth: .infinity).padding(.horizontal, 10).padding(.vertical, 10)
                            .background(Color.white.opacity(selected ? 0.078 : 0.032), in: RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(selected ? DeckPalette.borderStrong : DeckPalette.outline, lineWidth: 0.5))
                    }.buttonStyle(.plain).accessibilityLabel("\(preset.0), \(selected ? "выбран" : "не выбран")")
                }
            }
            let custom = model.domainList.filter { domain in !presets.contains(where: { $0.1 == domain }) }
            if !custom.isEmpty {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 135), spacing: 7)], alignment: .leading, spacing: 7) {
                    ForEach(custom, id: \.self) { domain in
                        HStack(spacing: 6) {
                            CodexIcon(symbol: "link").font(.system(size: 10))
                            Text(domain).font(.system(size: 11)).lineLimit(1).truncationMode(.middle)
                            Spacer(minLength: 0)
                            Button { model.setDomains(model.domainList.filter { $0 != domain }) } label: { CodexIcon(symbol: "xmark").font(.system(size: 9, weight: .semibold)) }
                                .buttonStyle(.plain).help("Убрать \(domain)")
                        }.foregroundStyle(DeckPalette.ink).padding(9).background(DeckPalette.ink.opacity(0.07), in: RoundedRectangle(cornerRadius: 9)).help(domain)
                    }
                }
            }
            HStack(spacing: 7) {
                TextField("", text: $draft, prompt: Text("Другой сайт, например reddit.com").foregroundStyle(DeckPalette.tertiary)).modifier(DeckField())
                    .accessibilityLabel("Добавить сайт")
                    .onSubmit { addDraft() }
                Button { addDraft() } label: { CodexIcon(symbol: "plus").font(.system(size: 13, weight: .semibold)) }
                    .buttonStyle(DeckButtonStyle()).help("Добавить сайт").disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Text(model.domainList.isEmpty ? "Можно оставить только запрет приложений" : "Выбрано сайтов: \(model.domainList.count) · медиа-домены популярных сервисов включены")
                .font(.system(size: 10)).foregroundStyle(DeckPalette.secondary.opacity(0.65))
        }
    }
    private var applicationsCard: some View {
        DeckCard("Приложения", icon: "square.grid.2x2", subtitle: "Дополнительно к ограничению браузеров") {
            if model.targets.isEmpty {
                HStack(spacing: 10) {
                    CodexIcon(symbol: "app.dashed").font(.system(size: 25)).foregroundStyle(DeckPalette.secondary.opacity(0.35))
                    Text("Добавь приложения, которые отвлекают от дела").font(.system(size: 12)).foregroundStyle(DeckPalette.secondary.opacity(0.7))
                }.padding(.vertical, 3)
            } else {
                ForEach(model.targets) { target in
                    HStack(spacing: 9) {
                        if let path = target.path { Image(nsImage: NSWorkspace.shared.icon(forFile: path)).resizable().frame(width: 27, height: 27) }
                        Text(target.name).font(.system(size: 12)).lineLimit(1)
                        Spacer()
                        Button { model.targets.removeAll { $0.id == target.id } } label: { CodexIcon(symbol: "minus.circle").foregroundStyle(DeckPalette.secondary) }.buttonStyle(.plain).help("Убрать \(target.name)")
                    }
                }
            }
            Button { model.chooseApps() } label: { DeckLabel("Выбрать приложения", icon: "plus") }.buttonStyle(DeckButtonStyle())
        }
    }
    private var durationCard: some View {
        DeckCard("Длительность запрета", icon: "hourglass", subtitle: "От5 минут до30 дней") {
            HStack(spacing: 10) {
                TextField("Срок", value: Binding(get: { Double(model.minutes) / Double(durationUnit) }, set: { value in
                    let minutes = (value * Double(durationUnit)).rounded()
                    if minutes.isFinite && abs(minutes) < 1_000_000_000 { model.minutes = Int(minutes) }
                }), format: .number.precision(.fractionLength(0...3))).modifier(DeckField()).frame(width: 86)
                    .accessibilityLabel("Длительность запрета")
                Picker("Единица", selection: $durationUnit) {
                    Text("минуты").tag(1); Text("часы").tag(60); Text("дни").tag(1440)
                }.labelsHidden().pickerStyle(.menu).frame(maxWidth: .infinity)
            }
            VStack(spacing: 5) {
                Slider(value: Binding(get: {
                    log(Double(max(5, min(43_200, model.minutes))) / 5) / log(8640)
                }, set: { model.minutes = Int((5 * pow(8640, $0)).rounded()) }), in: 0...1)
                    .accessibilityLabel("Длительность запрета").accessibilityValue(model.durationLabel)
                HStack { Text("5 мин"); Spacer(); Text("30 дней") }.font(.system(size: 10)).foregroundStyle(DeckPalette.tertiary)
            }
            HStack(spacing: 7) {
                ForEach([25, 60, 1440], id: \.self) { minutes in
                    Button { model.minutes = minutes; durationUnit = minutes < 60 ? 1 : minutes < 1440 ? 60 : 1440 } label: {
                        Text(minutes == 25 ? "25 мин" : minutes == 60 ? "1 час" : "1 день").font(.system(size: 12, weight: model.minutes == minutes ? .medium : .regular))
                            .frame(maxWidth: .infinity).padding(.vertical, 10)
                            .foregroundStyle(model.minutes == minutes ? DeckPalette.ink : DeckPalette.secondary)
                            .background(Color.white.opacity(model.minutes == minutes ? 0.078 : 0.032), in: RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(model.minutes == minutes ? DeckPalette.borderStrong : .clear, lineWidth: 0.5))
                    }.buttonStyle(.plain).accessibilityLabel("\(minutes) минут")
                }
            }
            HStack(spacing: 6) {
                CodexIcon(symbol: "clock")
                if model.validDuration { Text("До \(Date().addingTimeInterval(Double(model.minutes * 60)).formatted(date: model.minutes >= 1440 ? .abbreviated : .omitted, time: .shortened)), если начать сейчас") }
                else { Text("Укажите от5 минут до30 дней").foregroundStyle(.orange) }
            }.font(.system(size: 10)).foregroundStyle(DeckPalette.secondary.opacity(0.7))
        }
    }
    private var browserCard: some View {
        DeckCard("Рабочий браузер", icon: "safari", subtitle: "Отдельный сеанс Chrome") {
            Text("При запрете сайтов обычные браузеры закроются. Разрешённые сайты останутся доступны здесь.")
                .font(.system(size: 12)).foregroundStyle(DeckPalette.secondary).fixedSize(horizontal: false, vertical: true)
            HStack {
                Button { Task { await model.openBrowser() } } label: { DeckLabel("Открыть Chrome", icon: "arrow.up.right") }.buttonStyle(DeckButtonStyle())
                if model.reply?.managedBrowserPID != nil { CodexIcon(symbol: "checkmark.circle.fill").foregroundStyle(DeckPalette.ink).help("Браузер запущен службой") }
            }
        }
    }
    private var commitment: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button { confirming = true } label: {
                HStack { CodexIcon(symbol: "lock.fill"); Text("Закрыть на \(model.durationLabel)"); Spacer(minLength: 0); CodexIcon(symbol: "arrow.right") }.frame(maxWidth: .infinity)
            }.buttonStyle(DeckButtonStyle(primary: true)).disabled(!model.validDuration || (model.domainList.isEmpty && model.targets.isEmpty))
            Text("Quit и удаление интерфейса не снимут запрет. Сон входит в срок; после выключения оставшееся время продолжается.")
                .font(.system(size: 10)).foregroundStyle(DeckPalette.secondary.opacity(0.65)).fixedSize(horizontal: false, vertical: true)
        }
    }
    private func activeWindow(_ state: SystemProtectionReply) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 20) {
                VStack(alignment: .leading, spacing: 10) {
                    DeckBadge(text: "Границы действуют")
                    Text("Ограничения действуют").font(.system(size: 20, weight: .medium))
                    Text("Можно закрыть FocusDeck. Запрет останется до конца срока.").font(.system(size: 12)).foregroundStyle(DeckPalette.secondary)
                }
                Spacer(minLength: 0)
                VStack(alignment: .trailing, spacing: 5) {
                    Text(state.effectiveSecondsRemaining >= 86400 ? String(format: "%d д %02d:%02d", state.effectiveSecondsRemaining / 86400, state.effectiveSecondsRemaining / 3600 % 24, state.effectiveSecondsRemaining / 60 % 60) : String(format: "%02d:%02d:%02d", state.effectiveSecondsRemaining / 3600, state.effectiveSecondsRemaining / 60 % 60, state.effectiveSecondsRemaining % 60))
                        .font(.system(size: 36, weight: .regular)).monospacedDigit().foregroundStyle(DeckPalette.ink)
                    Text("ДО ОТКРЫТИЯ").font(.system(size: 9, weight: .medium)).tracking(1.4).foregroundStyle(DeckPalette.secondary.opacity(0.6))
                }
            }.padding(22).background(DeckPalette.surface, in: RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(DeckPalette.ink.opacity(0.2), lineWidth: 1))
            HStack(alignment: .top, spacing: 16) {
                DeckCard("В запрете", icon: "lock", subtitle: "Цели зафиксированы до конца срока") {
                    ForEach(state.domains, id: \.self) { domain in DeckLabel(domain, icon: "globe").font(.system(size: 13)) }
                    if state.applicationCount > 0 { DeckLabel("Приложений: \(state.applicationCount)", icon: "square.grid.2x2").font(.system(size: 13)) }
                    Button {
                        additionalSites = ""; additionalApps = []; model.feedback = nil; addingTargets = true
                    } label: { DeckLabel("Добавить в запрет", icon: "plus") }.buttonStyle(DeckButtonStyle())
                    Text("Можно добавлять цели. Убирать уже закрытые или сокращать срок нельзя.").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                    if let remaining = state.exitSecondsRemaining {
                        Divider().overlay(DeckPalette.outline)
                        DeckLabel(String(format: "Выход через %02d:%02d", remaining / 60, remaining % 60), icon: "hourglass")
                            .font(.system(size: 13)).monospacedDigit()
                        Text("Запрет сохраняется до окончания ожидания или исходного срока. Повторное нажатие не ускоряет выход.")
                            .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                        Button("Отменить запрос выхода") { Task { await model.requestExit(cancel: true) } }.buttonStyle(DeckButtonStyle())
                    } else {
                        Button("Запросить выход через час…") { requestingExit = true }.buttonStyle(DeckButtonStyle())
                    }
                    if (state.bootResumes ?? 0) > 0 {
                        Text("Блок восстановлен после перезагрузки. Выключенное время не сокращает срок.")
                            .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                    }
                    if state.recoveredFromBackup == true {
                        Text("Восстановлена защищённая резервная запись блока.").font(.system(size: 11)).foregroundStyle(.orange)
                    }
                    if !state.domains.isEmpty {
                        DeckBadge(text: state.networkActive && state.dnsActive ? "Сетевые правила установлены" : "Проверьте состояние службы", color: state.networkActive && state.dnsActive ? DeckPalette.ink : .orange)
                    }
                }
                if state.managedBrowserOnly {
                    DeckCard("Рабочая среда", icon: "safari", subtitle: "Разрешённые сайты доступны") {
                        Text("Другие браузеры закрываются до конца окна. Для работы используй защищённый Chrome.").font(.system(size: 12)).foregroundStyle(DeckPalette.secondary)
                        Button { Task { await model.openBrowser() } } label: { DeckLabel("Открыть Chrome", icon: "arrow.up.right") }.buttonStyle(DeckButtonStyle(primary: true))
                    }.frame(maxWidth: 300)
                }
            }
        }
    }
    private var additionsSheet: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                VStack(alignment: .leading, spacing: 5) {
                    Text("Добавить в действующий запрет").font(.system(size: 19, weight: .medium))
                    Text("Текущие цели и время окончания сохраняются").font(.system(size: 12)).foregroundStyle(DeckPalette.secondary)
                }
                Spacer()
                Button { addingTargets = false } label: { CodexIcon(symbol: "xmark") }.buttonStyle(.plain).disabled(model.busy).help("Закрыть")
            }
            DeckCard("Дополнительные сайты", icon: "globe", subtitle: "Через запятую или с новой строки") {
                TextField("", text: $additionalSites, prompt: Text("instagram.com, reddit.com").foregroundStyle(DeckPalette.secondary))
                    .modifier(DeckField()).accessibilityLabel("Дополнительные сайты")
                if let state = model.reply { Text("Уже закрыты: " + (state.domains.isEmpty ? "нет сайтов" : state.domains.joined(separator: ", "))).font(.system(size: 11)).foregroundStyle(DeckPalette.tertiary) }
            }
            DeckCard("Дополнительные приложения", icon: "square.grid.2x2") {
                ForEach(additionalApps) { target in
                    HStack {
                        Text(target.name).font(.system(size: 12)); Spacer()
                        Button { additionalApps.removeAll { $0.id == target.id } } label: { CodexIcon(symbol: "xmark", size: 12) }.buttonStyle(.plain)
                    }
                }
                Button("Выбрать приложения…") { if let selected = model.pickApps() { additionalApps = selected } }.buttonStyle(DeckButtonStyle())
            }
            if let feedback = model.feedback { Text(feedback).font(.system(size: 12)).foregroundStyle(.orange) }
            HStack {
                Button("Отмена") { addingTargets = false }.buttonStyle(DeckButtonStyle()).disabled(model.busy)
                Spacer()
                Button {
                    Task { if await model.addTargets(additionalSites, applications: additionalApps) { addingTargets = false; additionalSites = ""; additionalApps = [] } }
                } label: { DeckLabel(model.busy ? "Добавление…" : "Добавить до конца окна", icon: "lock.fill") }.buttonStyle(DeckButtonStyle(primary: true))
                    .disabled(model.busy || (additionalSites.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && additionalApps.isEmpty))
            }
        }.padding(22).frame(width: 560).background(DeckPalette.background).foregroundStyle(DeckPalette.ink)
    }
    private var installation: some View {
        DeckCard("Подключить системную защиту", icon: "lock.shield", subtitle: "Один раз на этом Mac") {
            Text("Служба хранит срок отдельно от интерфейса и продолжает блокировку после Quit. macOS запросит разрешение администратора.")
                .font(.system(size: 14)).foregroundStyle(DeckPalette.secondary).frame(maxWidth: 520, alignment: .leading)
            Button { Task { await model.install() } } label: { DeckLabel(model.busy ? "Подключение…" : "Подключить защиту…", icon: "checkmark.shield") }.buttonStyle(DeckButtonStyle(primary: true))
        }
    }
    private func addDraft() { if model.addDomains(draft) { draft = "" } }
}

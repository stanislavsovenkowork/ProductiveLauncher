import SwiftUI
import AppKit

// Exact default dark values exported from Codex's app-theme module.
// See Assets/CodexAppearance/source.json for the reference and token names.
enum DeckPalette {
    static let background = Color(white: 24/255)
    static let low = Color(white: 20/255)
    static let surface = Color.white.opacity(0.032)
    static let raised = Color(white: 45/255).opacity(0.96)
    static let ink = Color(white: 223/255)
    static let secondary = Color.white.opacity(0.71)
    static let tertiary = Color.white.opacity(0.498)
    static let outline = Color.white.opacity(0.084)
    static let borderStrong = Color.white.opacity(0.156)
    static let blue = Color(red: 51/255, green: 156/255, blue: 1)
}

struct DeckCard<Content: View>: View {
    var title: String
    var icon: String
    var subtitle: String?
    private let content: Content
    init(_ title: String, icon: String, subtitle: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title; self.icon = icon; self.subtitle = subtitle; self.content = content()
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                CodexIcon(symbol: icon).foregroundStyle(DeckPalette.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: 13, weight: .medium))
                    if let subtitle { Text(subtitle).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary) }
                }
                Spacer(minLength: 0)
            }
            content
        }.padding(16).frame(maxWidth: .infinity, alignment: .topLeading)
            .background(DeckPalette.surface, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(DeckPalette.outline, lineWidth: 0.5))
    }
}

struct DeckButtonStyle: ButtonStyle {
    var primary = false
    @Environment(\.isEnabled) private var enabled
    @State private var hovering = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 12).padding(.vertical, 8)
            .foregroundStyle(!enabled ? DeckPalette.tertiary : primary ? DeckPalette.background : DeckPalette.ink)
            .background(!enabled ? Color.white.opacity(0.038) : primary ? DeckPalette.ink : Color.white.opacity(configuration.isPressed ? 0.12 : hovering ? 0.078 : 0.052), in: RoundedRectangle(cornerRadius: 8))
            .opacity(configuration.isPressed && enabled ? 0.75 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .onHover { hovering = $0 }
    }
}

struct DeckBadge: View {
    var text: String
    var color: Color = DeckPalette.ink
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color.opacity(0.6)).frame(width: 4, height: 4)
            Text(text).font(.system(size: 11, weight: .medium)).lineLimit(1)
        }.foregroundStyle(color).padding(.horizontal, 8).padding(.vertical, 5)
            .background(Color.white.opacity(0.052), in: RoundedRectangle(cornerRadius: 6))
    }
}

struct DeckField: ViewModifier {
    func body(content: Content) -> some View {
        content.textFieldStyle(.plain).font(.system(size: 13))
            .padding(10).background(DeckPalette.raised.opacity(0.55), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(DeckPalette.outline, lineWidth: 0.5))
    }
}

private enum DeckPage: String, CaseIterable {
    case protection = "Защита", session = "Сеанс", overview = "Обзор", settings = "Настройки"
    var icon: String {
        switch self { case .protection: "lock.shield"; case .session: "timer"; case .overview: "chart.bar.xaxis"; case .settings: "slider.horizontal.3" }
    }
    var subtitle: String {
        switch self {
        case .protection: "Сайты, приложения и срок ограничения"
        case .session: "Текущее дело и рабочий отрезок"
        case .overview: "Время и наблюдения из доступных источников."
        case .settings: "Системная служба и локальные данные"
        }
    }
    var shortcut: KeyEquivalent {
        switch self { case .protection: "1"; case .session: "2"; case .overview: "3"; case .settings: "4" }
    }
}

struct DeckWorkspaceView: View {
    @ObservedObject var session: SessionModel
    @StateObject private var protection = SystemProtectionModel()
    @AppStorage("workspace.page") private var selectedPage = DeckPage.protection.rawValue
    private var page: DeckPage { DeckPage(rawValue: selectedPage) ?? .protection }
    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Rectangle().fill(DeckPalette.outline).frame(width: 1)
            VStack(spacing: 0) {
                header
                ScrollView {
                    Group {
                        switch page {
                        case .protection: SystemProtectionView(model: protection)
                        case .session: DeckSessionPage(model: session, protectionActive: protection.reply?.active == true)
                        case .overview: DeckOverviewPage(model: session)
                        case .settings: DeckSettingsPage(model: session, protection: protection)
                        }
                    }.frame(maxWidth: 1100, alignment: .topLeading).padding(20)
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                }.id(page).scrollIndicators(.hidden)
            }
        }.background { CodexGlass(material: .underWindowBackground).overlay(DeckPalette.background.opacity(0.94)) }
            .background(CodexWindowConfigurator().allowsHitTesting(false)).foregroundStyle(DeckPalette.ink)
            .font(.system(size: 13)).tint(DeckPalette.ink)
            .task { while !Task.isCancelled { await protection.refresh(); try? await Task.sleep(for: .seconds(2)) } }
            .sheet(isPresented: $session.choosingTask) { taskPicker }
    }
    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                CodexIcon(symbol: "square.stack.3d.up.fill", size: 18).foregroundStyle(DeckPalette.ink)
                Text("FocusDeck").font(.system(size: 15, weight: .medium))
            }.padding(.top, 32).padding(.bottom, 24).padding(.horizontal, 14)
            Text("Рабочее пространство").font(.system(size: 11, weight: .medium))
                .foregroundStyle(DeckPalette.secondary.opacity(0.55)).padding(.horizontal, 16).padding(.bottom, 8)
            ForEach(DeckPage.allCases, id: \.self) { item in
                Button { withAnimation(.easeOut(duration: 0.16)) { selectedPage = item.rawValue } } label: {
                    HStack(spacing: 11) {
                        CodexIcon(symbol: item.icon).font(.system(size: 15, weight: .medium)).frame(width: 20)
                        Text(item.rawValue).font(.system(size: 13, weight: page == item ? .semibold : .medium))
                        Spacer(minLength: 0)

                    }.padding(.horizontal, 12).frame(height: 36).contentShape(Rectangle())
                        .foregroundStyle(page == item ? DeckPalette.ink : DeckPalette.secondary.opacity(0.75))
                        .background(page == item ? Color.white.opacity(0.078) : .clear, in: RoundedRectangle(cornerRadius: 12))
                }.buttonStyle(.plain).keyboardShortcut(item.shortcut, modifiers: .command).padding(.horizontal, 8).padding(.bottom, 2)
            }
            Spacer(minLength: 24)
            VStack(alignment: .leading, spacing: 9) {
                CodexIcon(symbol: protection.reply?.active == true ? "lock.shield.fill" : "checkmark.shield")
                    .font(.system(size: 20)).foregroundStyle(DeckPalette.ink)
                Text(protection.reply?.active == true ? "Границы действуют" : protection.reply != nil ? "Всё готово к старту" : "Подключить защиту")
                    .font(.system(size: 12, weight: .medium))
                Text(protection.reply?.active == true ? "\(Int(ceil(Double(protection.reply?.secondsRemaining ?? 0) / 60))) мин до открытия" : "Локально на этом Mac")
                    .font(.system(size: 10)).foregroundStyle(DeckPalette.secondary.opacity(0.6))
            }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(DeckPalette.surface, in: RoundedRectangle(cornerRadius: 10)).padding(12)
            Text("НА ЭТОМ MAC").font(.system(size: 8, weight: .medium, design: .monospaced)).tracking(1.2)
                .foregroundStyle(DeckPalette.secondary.opacity(0.35)).padding(.horizontal, 18).padding(.bottom, 16)
        }.frame(width: 200).background { CodexGlass(material: .sidebar).overlay(DeckPalette.low.opacity(0.82)) }
    }
    private var header: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 5) {
                Text(page.rawValue).font(.system(size: 20, weight: .medium))
                Text(page.subtitle).font(.system(size: 12)).foregroundStyle(DeckPalette.secondary.opacity(0.7))
            }
            Spacer(minLength: 16)
            DeckBadge(text: protection.reply?.active == true ? "Защита действует" : protection.reply != nil ? "Служба готова" : "Нет связи",
                      color: protection.reply != nil ? DeckPalette.ink : .orange)
        }.padding(.horizontal, 20).padding(.top, 20).padding(.bottom, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .bottom) { Rectangle().fill(DeckPalette.outline).frame(height: 1) }
    }
    private var taskPicker: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Ближайшее действие").font(.system(size: 22, weight: .semibold))
            Text(session.taskSourceStatus).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
            if session.taskChoices.isEmpty { Text("В этой заметке нет открытых задач").foregroundStyle(DeckPalette.secondary) }
            List(session.taskChoices) { choice in
                Button { session.selectTask(choice) } label: {
                    HStack { Text(choice.title).lineLimit(2); Spacer(); CodexIcon(symbol: "arrow.up.right") }
                }.buttonStyle(.plain).padding(.vertical, 7)
            }.scrollContentBackground(.hidden)
            HStack {
                Button("Другая заметка…") {
                    session.choosingTask = false
                    DispatchQueue.main.async { session.chooseTaskNote() }
                }.buttonStyle(DeckButtonStyle())
                Spacer()
                Button("Закрыть") { session.choosingTask = false }.buttonStyle(DeckButtonStyle())
            }
        }.padding(24).frame(width: 560, height: 420).background(DeckPalette.background)
    }
}

private struct DeckSessionPage: View {
    @ObservedObject var model: SessionModel
    let protectionActive: Bool
    private var active: Bool { [.running, .paused, .returnReady].contains(model.state.phase) }
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            DeckCard("Одно дело сейчас", icon: "scope", subtitle: model.state.phase == .paused ? "Перерыв · задача сохранена" : "Работа и учёба учитываются отдельно") {
                HStack(alignment: .top, spacing: 24) {
                    VStack(alignment: .leading, spacing: 15) {
                        if active { Text(model.state.taskTitle).font(.system(size: 23, weight: .medium)).textSelection(.enabled) }
                        else {
                            TextField("", text: $model.state.taskTitle, prompt: Text("Что сделаем за этот отрезок?").foregroundStyle(DeckPalette.tertiary)).modifier(DeckField())
                                .accessibilityLabel("Ближайшее действие")
                                .onChange(of: model.state.taskTitle) { model.titleChanged() }
                            Button { model.chooseExistingTask() } label: { DeckLabel("Из существующей задачи", icon: "arrow.down.doc") }
                                .buttonStyle(.plain).foregroundStyle(DeckPalette.ink).font(.system(size: 12))
                        }
                        HStack(spacing: 7) { category("Работа", "work", "briefcase"); category("Учёба", "study", "book") }.disabled(active)
                        if !model.taskBindingStatus.isEmpty { Text(model.taskBindingStatus).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary) }
                        if !model.taskSourceStatus.isEmpty { Text(model.taskSourceStatus).font(.system(size: 10)).foregroundStyle(DeckPalette.tertiary) }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    VStack(spacing: 6) {
                        Text(model.clock).font(.system(size: 36, weight: .regular)).monospacedDigit().foregroundStyle(DeckPalette.ink)
                        Text(model.state.phase == .paused ? "ПЕРЕРЫВ" : "ОДИН ОТРЕЗОК").font(.system(size: 9, weight: .medium)).tracking(1.5).foregroundStyle(DeckPalette.secondary)
                    }.frame(width: 164).padding(.vertical, 7)
                }
                HStack(spacing: 10) {
                    if model.state.phase == .running {
                        Button { model.finish(completed: true) } label: { DeckLabel("Завершить отрезок", icon: "checkmark") }.buttonStyle(DeckButtonStyle(primary: true))
                        Button("Перерыв · 5 мин") { model.pause() }.buttonStyle(DeckButtonStyle()).disabled(model.state.usedPause)
                        Button("Остановить сеанс") { model.finish(completed: false) }.buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                    } else if model.state.phase == .paused || model.state.phase == .returnReady {
                        Button { model.resume() } label: { DeckLabel("Продолжить", icon: "play.fill") }.buttonStyle(DeckButtonStyle(primary: true))
                        Button("Закончить сеанс") { model.finish(completed: false) }.buttonStyle(DeckButtonStyle())
                    } else {
                        Button { model.start() } label: { DeckLabel("Начать · 25 минут", icon: "play.fill") }.buttonStyle(DeckButtonStyle(primary: true))
                            .disabled(model.state.taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                if model.canCompleteLinkedTask {
                    Button { model.completeLinkedTask() } label: { DeckLabel("Задача выполнена · отметить в Obsidian", icon: "checkmark") }
                        .buttonStyle(DeckButtonStyle())
                    Text("Завершение отрезка выше сохраняет только сеанс. Эта кнопка отмечает саму задачу.")
                        .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                } else if model.state.taskCompleted {
                    DeckLabel("Задача выполнена в Obsidian", icon: "checkmark").font(.system(size: 12))
                }
                if protectionActive { DeckLabel("Остановка сеанса не снимает системный запрет", icon: "lock.fill").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary) }
                if model.restored { Text("Контекст сохранён. Время вне приложения неизвестно.").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary) }
            }
            DeckCard("Продолжить с этого места", icon: "bookmark", subtitle: "Короткая подсказка себе на следующий заход") {
                Text(model.returnReview.nextStep(taskRef: model.state.taskRef, taskTitle: model.state.taskTitle, note: model.state.note))
                    .font(.system(size: 13)).foregroundStyle(DeckPalette.secondary)
                TextField("", text: $model.state.note, prompt: Text("Следующий шаг — необязательно").foregroundStyle(DeckPalette.tertiary), axis: .vertical).lineLimit(2...4).modifier(DeckField())
                    .accessibilityLabel("Следующий шаг")
                    .onChange(of: model.state.note) { model.persist() }
            }
            if let error = model.error {
                Text(error).font(.system(size: 12)).foregroundStyle(.orange)
                if model.storageBlocked { Button("Сохранить исходник и начать заново") { model.archiveCorruptContext() }.buttonStyle(DeckButtonStyle()) }
            }
        }
    }
    private func category(_ text: String, _ value: String, _ icon: String) -> some View {
        Button { model.state.category = value; model.persist() } label: {
            DeckLabel(text, icon: icon).font(.system(size: 12, weight: .medium)).padding(.horizontal, 12).padding(.vertical, 8)
                .foregroundStyle(model.state.category == value ? DeckPalette.ink : DeckPalette.secondary)
                .background(model.state.category == value ? DeckPalette.ink.opacity(0.12) : DeckPalette.raised, in: Capsule())
        }.buttonStyle(.plain)
    }
}

private struct DeckOverviewPage: View {
    @ObservedObject var model: SessionModel
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 16) {
                metric("Работа", icon: "briefcase", hours: model.report.workTimerHours)
                metric("Учёба", icon: "book", hours: model.report.studyTimerHours)
            }
            DeckCard("Качество данных", icon: "waveform.path.ecg", subtitle: model.report.day ?? "День не определён") {
                Text(model.report.quality).font(.system(size: 13)).foregroundStyle(DeckPalette.secondary)
                HStack(spacing: 24) {
                    context("Рабочий контекст", model.report.workContextHours)
                    context("Учебный контекст", model.report.studyContextHours)
                }
                Text("Таймер и наблюдаемый контекст не измеряют внимание. Отсутствие данных не означает ноль работы.")
                    .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary.opacity(0.65))
            }
            DeckCard("Недельный обзор", icon: "calendar", subtitle: "По доступным локальным источникам") {
                Text(model.returnReview.weeklyText).font(.system(size: 13)).foregroundStyle(DeckPalette.secondary).textSelection(.enabled)
                Button { model.revealJournal() } label: { DeckLabel("Открыть журнал", icon: "arrow.up.right.square") }.buttonStyle(DeckButtonStyle())
            }
        }
    }
    private func metric(_ title: String, icon: String, hours: Double?) -> some View {
        DeckCard(title, icon: icon, subtitle: "Учтено по таймеру") {
            Text(DailyReportSnapshot.hoursText(hours)).font(.system(size: 35, weight: .medium)).foregroundStyle(DeckPalette.ink)
        }
    }
    private func context(_ title: String, _ hours: Double?) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
            Text(DailyReportSnapshot.hoursText(hours)).font(.system(size: 20, weight: .medium))
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct DeckSettingsPage: View {
    @ObservedObject var model: SessionModel
    @ObservedObject var protection: SystemProtectionModel
    @State private var confirmingRemoval = false
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            DeckCard("Системная служба", icon: "lock.shield", subtitle: "Работает независимо от окна приложения") {
                HStack {
                    DeckBadge(text: protection.reply == nil ? "Нет связи" : protection.reply?.active == true ? "Действует запрет" : "Подключена", color: protection.reply == nil ? .orange : DeckPalette.ink)
                    Spacer()
                    Button("Проверить связь") { Task { await protection.refresh() } }.buttonStyle(DeckButtonStyle()).disabled(protection.busy)
                }
                Text("Закрытие и обычное удаление интерфейса не снимают активный запрет. Срок хранится системной службой.")
                    .font(.system(size: 12)).foregroundStyle(DeckPalette.secondary)
                Button("Удалить системную службу…") { confirmingRemoval = true }
                    .buttonStyle(DeckButtonStyle()).disabled(protection.reply?.active != false || protection.busy)
                Text("Во время блока удаление недоступно. Запрос выхода находится на странице «Защита» и требует час ожидания.")
                    .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                DisclosureGroup("Проверка и технические подробности") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(protection.message).font(.system(size: 11)).textSelection(.enabled)
                        Button("Обновить системный компонент…") { Task { await protection.install() } }
                            .buttonStyle(DeckButtonStyle()).disabled(protection.busy)
                        Text("Только для обновления компонента macOS запросит администратора. Проверка связи, блок и выход этого не требуют.")
                            .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                        if let recovery = protection.reply?.lastBootRecovery {
                            Text(recovery.confirmedAt != nil ? "Блок восстановлен после перезагрузки" : "После перезагрузки восстановление ещё не завершено")
                                .font(.system(size: 12, weight: .medium))
                            Text(recovery.restoredAt.formatted(date: .abbreviated, time: .shortened))
                                .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                            Text("Сохранено: \(recovery.secondsRemaining / 60) мин блока" + (recovery.exitSecondsRemaining.map { ", \($0 / 60) мин ожидания выхода" } ?? ""))
                                .font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                            if let error = recovery.error { Text(error).font(.system(size: 11)).foregroundStyle(.orange) }
                        } else {
                            Text("Служба ещё не зафиксировала восстановление действующего блока после перезагрузки.").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                        }
                        Text("Владелец с административным root-доступом сохраняет контроль над ОС.").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                        Button("Проверить CERN · 90 секунд") { Task { await protection.start(validation: true) } }.buttonStyle(DeckButtonStyle()).disabled(protection.reply?.active == true || protection.busy)
                        Button("Строгая проверка · 90 секунд, браузеры закроются") { Task { await protection.start(validation: true, strictValidation: true) } }.buttonStyle(DeckButtonStyle()).disabled(protection.reply?.active == true || protection.busy)
                    }.padding(.top, 10)
                }.font(.system(size: 12))
            }
            DeckCard("Локальные данные", icon: "internaldrive", subtitle: "Без содержимого окон, клавиш и адресов вкладок") {
                Text("Контекст хранится 7 дней, отчёты — 90 дней. Сбор может работать после закрытия приложения; его можно остановить отдельно от блокировки.").font(.system(size: 12)).foregroundStyle(DeckPalette.secondary)
                HStack {
                    Button("Остановить сбор") { Task { await model.controlCollection(start: false) } }.buttonStyle(DeckButtonStyle())
                    Button("Включить сбор") { Task { await model.controlCollection(start: true) } }.buttonStyle(DeckButtonStyle())
                    Button("Журнал") { model.revealJournal() }.buttonStyle(.plain).foregroundStyle(DeckPalette.ink)
                }.disabled(model.isolated || model.controllingCollection)
                if !model.collectionControlStatus.isEmpty { Text(model.collectionControlStatus).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary) }
                DisclosureGroup("Расположение данных") {
                    Text("~/Library/Application Support/FocusDeckHarness\n~/Library/Application Support/FocusDeckStudy").font(.system(size: 11, design: .monospaced)).textSelection(.enabled).padding(.top, 8)
                }.font(.system(size: 12))
            }
            DeckCard("Сеанс и возвращение", icon: "arrow.uturn.backward", subtitle: "Не меняет активную системную блокировку") {
                Toggle("Бережный Focus в новых учебных сеансах", isOn: $model.quietEnabled)
                    .toggleStyle(.switch).disabled(!model.quietConfigured || model.isolated).onChange(of: model.quietEnabled) { model.saveQuietPreference() }
                Text(model.quietStatus).font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                DisclosureGroup("Напоминания и источник возвращения") {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(model.recoveryStatus).font(.system(size: 12))
                        Text("\(model.reminderStatus) · Разрешение: \(model.reminderPermission)").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                        HStack {
                            Button("Статус") { Task { await model.reminderAction("status") } }
                            Button("Разрешение…") { Task { await model.reminderAction("request-permission") } }
                            Button("Включить") { Task { await model.reminderAction("enable") } }
                            Button("Остановить") { Task { await model.reminderAction("stop") } }
                        }.buttonStyle(DeckButtonStyle()).disabled(model.reminderBusy || model.isolated)
                        Text("Тихие часы22:00–08:00, не чаще раза в сутки, до3попыток. Доставка не подтверждена. Следующий шаг может появиться на экране блокировки.").font(.system(size: 11)).foregroundStyle(DeckPalette.secondary)
                        if let enabled = model.recoveryEnabled {
                            Button(enabled ? "Отключить проверку источника" : "Включить прежний источник") { Task { await model.setRecovery(!enabled) } }
                                .buttonStyle(DeckButtonStyle()).disabled(model.recoveryBusy || model.isolated)
                        }
                    }.padding(.top, 10)
                }.font(.system(size: 12))
            }
        }.confirmationDialog("Удалить системную службу?", isPresented: $confirmingRemoval, titleVisibility: .visible) {
            Button("Удалить службу", role: .destructive) { Task { await protection.uninstall() } }
            Button("Отмена", role: .cancel) { }
        } message: {
            Text("macOS запросит разрешение администратора. Активный запрет удалить этим действием нельзя. Заметки Obsidian, история и настройки сохранятся.")
        }
    }
}

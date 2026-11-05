import Foundation
import Darwin
import Security

private struct Configuration: Codable {
    let uid: UInt32
    let clientHash: String
}

private enum ServiceError: Error { case invalidConfiguration, invalidPolicy, processFailed, unavailableHook }

private func command(_ path: String, _ arguments: [String], input: Data? = nil) throws -> String {
    try ServiceCommand.run(path, arguments, input: input)
}

private func continuous() -> Double {
    var base = mach_timebase_info_data_t(); mach_timebase_info(&base)
    return Double(mach_continuous_time()) * Double(base.numer) / Double(base.denom) / 1e9
}

private func resolve(_ domain: String) -> Set<String> {
    var hints = addrinfo(); hints.ai_family = AF_UNSPEC; hints.ai_socktype = SOCK_STREAM
    var result: UnsafeMutablePointer<addrinfo>?
    guard getaddrinfo(domain, nil, &hints, &result) == 0 else { return [] }
    defer { freeaddrinfo(result) }
    var addresses = Set<String>(); var current = result
    while let value = current {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(value.pointee.ai_addr, value.pointee.ai_addrlen, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            let address = String(cString: host)
            if !address.contains("%"), !address.hasPrefix("127."), !address.hasPrefix("10."),
               !address.hasPrefix("192.168."), address != "::1", !address.hasPrefix("fe80:") { addresses.insert(address) }
        }
        current = value.pointee.ai_next
    }
    return addresses
}

@main private enum SystemProtectionService {
    static let anchor = "com.apple/focusdeck.protection"
    static let ledgerURL = URL(fileURLWithPath: SystemProtectionPaths.directory + "/window.json")

    static func main() {
        do {
            if CommandLine.arguments == [CommandLine.arguments[0], "--uninstall"] {
                try uninstall(); return
            }
            if CommandLine.arguments.count == 4, CommandLine.arguments[1] == "--install" {
                try install(); return
            }
            try run()
        }
        catch { fputs("FocusDeck system protection could not start: \(error)\n", stderr); exit(1) }
    }

    static func uninstall() throws {
        guard geteuid() == 0, let boot = WindowPolicy.bootID() else { throw ServiceError.invalidConfiguration }
        let root = URL(fileURLWithPath: SystemProtectionPaths.directory)
        let plist = "/Library/LaunchDaemons/" + SystemProtectionPaths.label + ".plist"
        func check() throws -> ProtectionLedger {
            var info = stat()
            guard lstat(root.path, &info) == 0, info.st_uid == 0, info.st_mode & S_IFMT == S_IFDIR,
                  info.st_mode & 0o077 == 0 else { throw ServiceError.invalidConfiguration }
            var value = try JSONDecoder().decode(ProtectionLedger.self, from: Data(contentsOf: ledgerURL))
            if var window = value.window {
                guard window.valid else { throw ServiceError.invalidPolicy }
                window.advance(boot: boot, tick: continuous())
                guard window.ended else {
                    fputs("Блок ещё действует. Запросите выход в приложении и дождитесь часа или исходного конца окна.\n", stderr)
                    throw ServiceError.invalidPolicy
                }
                value.window = nil
            }
            return value
        }
        _ = try check()
        _ = try command("/bin/launchctl", ["bootout", "system/" + SystemProtectionPaths.label])
        do {
            // A start accepted between the first check and bootout must not be
            // erased. Verify the final committed ledger after the job stopped.
            let fd = open(root.appendingPathComponent("service.lock").path, O_RDWR | O_NOFOLLOW | O_CLOEXEC)
            guard fd >= 0 else { throw ServiceError.invalidConfiguration }
            defer { close(fd) }
            guard flock(fd, LOCK_EX | LOCK_NB) == 0 else { throw ServiceError.invalidConfiguration }
            let final = try check()
            _ = try DomainResolver.apply([], removing: final.resolverDomains ?? [])
            _ = try command("/sbin/pfctl", ["-a", anchor, "-f", "-"], input: Data())
            let data = try JSONEncoder().encode(final)
            for path in [root.appendingPathComponent("window.backup.json"), ledgerURL] {
                try data.write(to: path, options: .atomic); chmod(path.path, 0o600)
            }
            try FileManager.default.removeItem(atPath: plist)
            // Keep private ledger/history for reinstall. Remove only our job,
            // socket and executable, never global PF rules or network settings.
            unlink(SystemProtectionPaths.socket)
            try FileManager.default.removeItem(atPath: SystemProtectionPaths.helper + "/FocusDeckProtectionService")
            print("Системная служба удалена. Записи сохранены. Обычный Chrome можно открыть отдельно.")
        } catch {
            _ = try? command("/bin/launchctl", ["bootstrap", "system", plist])
            throw error
        }
    }

    static func install() throws {
        guard geteuid() == 0, let uid = UInt32(CommandLine.arguments[2]), uid >= 500,
              CommandLine.arguments[3].count == 40, CommandLine.arguments[3].allSatisfy(\.isHexDigit) else { throw ServiceError.invalidConfiguration }
        let root = URL(fileURLWithPath: SystemProtectionPaths.directory)
        var info = stat()
        if lstat(root.path, &info) == 0 {
            guard info.st_uid == 0, info.st_mode & S_IFMT == S_IFDIR, info.st_mode & 0o077 == 0 else { throw ServiceError.invalidConfiguration }
            if let data = try? Data(contentsOf: root.appendingPathComponent("configuration.json")),
               let old = try? JSONDecoder().decode(Configuration.self, from: data), old.uid != uid { throw ServiceError.invalidConfiguration }
        } else {
            guard errno == ENOENT, mkdir(root.path, 0o700) == 0 else { throw ServiceError.invalidConfiguration }
        }
        let configURL = root.appendingPathComponent("configuration.json")
        try JSONEncoder().encode(Configuration(uid: uid, clientHash: CommandLine.arguments[3])).write(to: configURL, options: .atomic)
        chmod(configURL.path, 0o600)
        let plistPath = "/Library/LaunchDaemons/" + SystemProtectionPaths.label + ".plist"
        let plist: [String: Any] = ["Label":SystemProtectionPaths.label,
            "ProgramArguments":[SystemProtectionPaths.helper + "/FocusDeckProtectionService"],
            "RunAtLoad":true, "KeepAlive":true, "ThrottleInterval":2,
            "SoftResourceLimits":["NumberOfFiles":4096],
            "StandardOutPath":SystemProtectionPaths.directory + "/service.log",
            "StandardErrorPath":SystemProtectionPaths.directory + "/service.log"]
        try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0).write(to: URL(fileURLWithPath: plistPath), options: .atomic)
        chmod(plistPath, 0o644)
        _ = try? command("/bin/launchctl", ["bootout", "system/" + SystemProtectionPaths.label])
        _ = try command("/bin/launchctl", ["bootstrap", "system", plistPath])
        print("FocusDeck system service installed; existing protection ledger preserved.")
    }

    static func run() throws {
        guard geteuid() == 0 else { throw ServiceError.invalidConfiguration }
        umask(0o077); signal(SIGPIPE, SIG_IGN)
        let root = URL(fileURLWithPath: SystemProtectionPaths.directory)
        var rootInfo = stat()
        guard lstat(root.path, &rootInfo) == 0, rootInfo.st_uid == 0,
              rootInfo.st_mode & S_IFMT == S_IFDIR, rootInfo.st_mode & 0o077 == 0 else { throw ServiceError.invalidConfiguration }
        let config = try JSONDecoder().decode(Configuration.self, from: Data(contentsOf: root.appendingPathComponent("configuration.json")))
        guard config.uid >= 500, config.clientHash.count == 40 else { throw ServiceError.invalidConfiguration }
        let lock = open(root.appendingPathComponent("service.lock").path, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0o600)
        guard lock >= 0, flock(lock, LOCK_EX | LOCK_NB) == 0 else { throw ServiceError.invalidConfiguration }
        defer { close(lock) }
        let backupURL = root.appendingPathComponent("window.backup.json")
        func readLedger(_ url: URL) throws -> ProtectionLedger {
            var info = stat()
            guard lstat(url.path, &info) == 0, info.st_uid == 0,
                  info.st_mode & S_IFMT == S_IFREG, info.st_mode & 0o077 == 0 else { throw ServiceError.invalidConfiguration }
            let value = try JSONDecoder().decode(ProtectionLedger.self, from: Data(contentsOf: url))
            guard value.window?.valid != false else { throw ServiceError.invalidPolicy }
            return value
        }
        var ledger = ProtectionLedger()
        var recoveredBackup = false
        if FileManager.default.fileExists(atPath: ledgerURL.path) || FileManager.default.fileExists(atPath: backupURL.path) {
            do { ledger = try readLedger(ledgerURL) }
            catch { ledger = try readLedger(backupURL); recoveredBackup = true }
        }
        func save() throws {
            let data = try JSONEncoder().encode(ledger)
            // Two root-private atomic snapshots survive a torn/missing primary.
            // Backup first means an interrupted expiry can only retain a ban.
            for url in [backupURL, ledgerURL] {
                try data.write(to: url, options: .atomic)
                guard chmod(url.path, 0o600) == 0 else { throw ServiceError.invalidConfiguration }
            }
        }
        // Establish both snapshots even on a fresh, idle installation.
        try save()
        let server = socket(AF_UNIX, SOCK_STREAM, 0)
        guard server >= 0 else { throw RootSocketFailure.systemCall }
        defer { close(server) }
        var old = stat()
        if lstat(SystemProtectionPaths.socket, &old) == 0 {
            guard old.st_uid == 0, old.st_mode & S_IFMT == S_IFSOCK else { throw ServiceError.invalidConfiguration }
            unlink(SystemProtectionPaths.socket)
        }
        var address = try guardAddress(SystemProtectionPaths.socket)
        guard withUnsafePointer(to: &address, { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            Darwin.bind(server, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
        } }) == 0, chmod(SystemProtectionPaths.socket, 0o666) == 0, listen(server, 8) == 0 else { throw RootSocketFailure.systemCall }
        var applied = Set<String>(); var network = false; var rulesKnown = false; var lastDNS = -Double.infinity
        var lastPFVerification = -Double.infinity
        var errorText: String?; var enforcer = BlockedApplications()
        let dns = try DomainResolver()
        // Restore the deny policy BEFORE binding/listening on the proxy. There is
        // no temporary allow-all listener during startup or service updates.
        let proxy = try BrowserProxy(domains: DomainResolver.expanded(ledger.window?.domains ?? []), addresses: ledger.window?.addresses ?? [], active: ledger.window != nil)
        let browser = ManagedBrowser(uid: config.uid)
        var appliedResolvers = ledger.resolverDomains ?? []
        guard let boot = WindowPolicy.bootID() else { throw ServiceError.invalidConfiguration }

        func setRules(_ addresses: Set<String>) throws {
            let tick = continuous()
            if !addresses.isEmpty && tick - lastPFVerification >= 5 {
                lastPFVerification = tick
                // Cached success must not survive PF disable or an external
                // removal of our own anchor/table. Read the global hook only;
                // repair exclusively our anchor and never reload main/NAT or
                // flush states belonging to VPNs and other applications.
                let main = try command("/sbin/pfctl", ["-sr"])
                guard main.split(separator: "\n").contains(where: {
                    let line = $0.trimmingCharacters(in: .whitespaces)
                    return line == "anchor \"com.apple/*\" all" || line == "anchor \"com.apple/*\""
                }) else { network = false; rulesKnown = false; throw ServiceError.unavailableHook }
                let info = try command("/sbin/pfctl", ["-s", "info"])
                let rules = (try? command("/sbin/pfctl", ["-a", anchor, "-sr"])) ?? ""
                let table = (try? command("/sbin/pfctl", ["-a", anchor, "-t", "focusdeck_targets", "-T", "show"])) ?? ""
                let actual = Set(table.split(separator: "\n").map {
                    $0.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "/32", with: "").replacingOccurrences(of: "/128", with: "")
                })
                let lines = rules.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
                if !info.contains("Status: Enabled") || actual != addresses ||
                    lines != ["block drop out quick from any to <focusdeck_targets>"] {
                    network = false; rulesKnown = false
                }
            }
            guard !rulesKnown || addresses != applied || (!addresses.isEmpty && !network) else { return }
            if addresses.isEmpty {
                _ = try command("/sbin/pfctl", ["-a", anchor, "-f", "-"], input: Data())
                applied = []; network = false; rulesKnown = true; return
            }
            let main = try command("/sbin/pfctl", ["-sr"])
            guard main.split(separator: "\n").contains(where: { $0.trimmingCharacters(in: .whitespaces) == "anchor \"com.apple/*\" all" || $0.trimmingCharacters(in: .whitespaces) == "anchor \"com.apple/*\"" }) else { throw ServiceError.unavailableHook }
            let rules = "table <focusdeck_targets> persist { " + addresses.sorted().joined(separator: ", ") + " }\nblock drop out quick to <focusdeck_targets>\n"
            _ = try command("/sbin/pfctl", ["-a", anchor, "-f", "-"], input: Data(rules.utf8))
            let info = try command("/sbin/pfctl", ["-s", "info"])
            if info.contains("Status: Disabled") {
                // The installed system service keeps PF available. No global
                // rule reload, state flush, VPN or route change is performed.
                _ = try command("/sbin/pfctl", ["-e"])
            }
            applied = addresses; network = true; rulesKnown = true
        }

        func iteration() throws {
            let now = continuous()
            if var window = ledger.window {
                if window.boot != boot {
                    ledger.lastBootRecovery = SystemProtectionRecovery(windowID: window.id,
                        previousBoot: window.boot, currentBoot: boot, restoredAt: Date(),
                        secondsRemaining: Int(ceil(window.remaining)),
                        exitSecondsRemaining: window.exitRemaining.map { Int(ceil($0)) },
                        domains: window.domains, applicationCount: window.applications.count)
                }
                window.advance(boot: boot, tick: now)
                if window.ended { ledger.window = nil }
                else {
                    if now - lastDNS >= 60 {
                        for domain in window.domains { window.addresses.formUnion(resolve(domain)); window.addresses.formUnion(resolve("www." + domain)) }
                        lastDNS = now
                    }
                    ledger.window = window
                }
                try save()
            }
            do {
                let domains = DomainResolver.expanded(ledger.window?.domains ?? [])
                let ownershipChanged = domains != (ledger.resolverDomains ?? [])
                if ownershipChanged { ledger.resolverDomains = Array(Set(appliedResolvers + domains)); try save() }
                let changed = try DomainResolver.apply(domains, removing: appliedResolvers)
                appliedResolvers = domains; ledger.resolverDomains = domains
                if changed || ownershipChanged { try save() }
                if changed {
                    _ = try? command("/usr/bin/dscacheutil", ["-flushcache"])
                    _ = try? command("/usr/bin/killall", ["-HUP", "mDNSResponder"])
                }
                try setRules(ledger.window?.addresses ?? [])
                errorText = nil
            } catch { errorText = "Сетевое ограничение не подтверждено: \(error)"; network = false }
            proxy.update(domains: DomainResolver.expanded(ledger.window?.domains ?? []), addresses: ledger.window?.addresses ?? [], active: ledger.window != nil)
            let strictBrowser = ledger.window.map { !$0.domains.isEmpty && $0.validation != true } ?? false
            browser.tick(enforce: strictBrowser)
            if strictBrowser, browser.pid == nil { try? browser.open() }
            enforcer.tick(targets: ledger.window?.applications ?? [], uid: config.uid, now: now)
            if var recovery = ledger.lastBootRecovery, recovery.currentBoot == boot,
               recovery.windowID == ledger.window?.id {
                let previous = recovery
                let sites = !(ledger.window?.domains.isEmpty ?? true)
                recovery.networkReady = !sites || network
                recovery.dnsReady = !sites || !appliedResolvers.isEmpty
                recovery.browserReady = !strictBrowser || browser.pid != nil
                recovery.error = errorText
                if recovery.confirmedAt == nil, recovery.networkReady && recovery.dnsReady && recovery.browserReady {
                    recovery.confirmedAt = Date()
                }
                if recovery != previous { ledger.lastBootRecovery = recovery; try save() }
            }
            var ready = pollfd(fd: server, events: Int16(POLLIN), revents: 0)
            guard poll(&ready, 1, 500) > 0 else { return }
            let peer = accept(server, nil, nil); guard peer >= 0 else { return }
            defer { close(peer) }
            var uid: uid_t = 0, gid: gid_t = 0, pid: Int32 = 0
            var size = socklen_t(MemoryLayout<Int32>.size)
            guard getpeereid(peer, &uid, &gid) == 0, uid == config.uid,
                  getsockopt(peer, SOL_LOCAL, LOCAL_PEERPID, &pid, &size) == 0,
                  let frame = try? readRootFrame(peer, timeout: 1),
                  let request = try? JSONDecoder().decode(SystemProtectionRequest.self, from: frame) else { return }
            var accepted = true; var message = recoveredBackup ? "Окно восстановлено из защищённой резервной записи." : "Системная служба подключена."
            if let window = ledger.window, (window.bootResumes ?? 0) > 0 {
                message = "Блок восстановлен после перезагрузки. Выключенное время не сокращает срок."
            }
            if request.command != "status" {
                guard RootSocketServer.verifiedHash(pid: pid) == config.clientHash else { return }
                if request.command == "browser" {
                    do { try browser.open(); message = "Открывается защищённый Chrome." }
                    catch { accepted = false; message = "Не удалось открыть Chrome: \(error)" }
                } else if request.command == "request-exit" {
                    if var window = ledger.window {
                        window.advance(boot: boot, tick: continuous())
                        window.requestExit(); ledger.window = window; try save()
                        message = "Выход запрошен. Ограничения останутся ещё на час или до исходного конца окна, если он наступит раньше. Повторный запрос не ускоряет выход."
                    } else { accepted = false; message = "Действующего блока нет." }
                } else if request.command == "cancel-exit" {
                    if var window = ledger.window, window.exitRemaining != nil {
                        window.advance(boot: boot, tick: continuous())
                        window.exitRemaining = nil; ledger.window = window; try save()
                        message = "Запрос выхода отменён. Исходный запрет продолжается."
                    } else { accepted = false; message = "Запроса выхода нет." }
                } else if request.command == "add-targets" {
                    if let current = ledger.window {
                        let additions = Array(Set(request.domains.map { $0.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) })).sorted()
                        let domains = Array(Set(current.domains + additions)).sorted()
                        var applications = current.applications
                        for target in request.applications where !applications.contains(where: { $0.signingID == target.signingID }) { applications.append(target) }
                        let valid = (!additions.isEmpty || !request.applications.isEmpty) && domains.count <= SiteDomainPolicy.maximumDomains && applications.count <= 40 &&
                            SiteDomainPolicy.validList(domains.isEmpty ? nil : domains, includeSubdomains: true) &&
                            request.applications.allSatisfy { ProcessSignatureRequirement.valid($0) && !WindowGuardV2.isProtected($0.signingID) }
                        if current.diagnostic == true || current.validation == true { accepted = false; message = "Диагностическое окно имеет фиксированные цели. Дождитесь его окончания." }
                        else if !valid { accepted = false; message = "Допустимы только дополнительные сайты и подписанные приложения; сетевые средства защищены." }
                        else if !domains.isEmpty && applications.contains(where: { $0.signingID == "com.google.Chrome" }) {
                            accepted = false; message = "Chrome уже закрыт как приложение. Добавление сайтов требует рабочего Chrome; текущий запрет сохранён."
                        } else if !domains.isEmpty && browser.pid == nil {
                            try? browser.open(); accepted = false; message = "Рабочий Chrome открывается. Повторите добавление после его запуска."
                        } else {
                            do {
                                try DomainResolver.prepare(DomainResolver.expanded(domains))
                                var next = current
                                for domain in additions where !current.domains.contains(domain) {
                                    next.addresses.formUnion(resolve(domain)); next.addresses.formUnion(resolve("www." + domain))
                                }
                                let commitTime = continuous()
                                next.advance(boot: boot, tick: commitTime)
                                if next.ended {
                                    ledger.window = nil; try save(); accepted = false; message = "Окно уже закончилось. Новые цели не добавлены."
                                } else {
                                    next.domains = domains; next.applications = applications
                                    // Existing targets and the original deadline are retained.
                                    // request.seconds is intentionally ignored for additions.
                                    ledger.window = next; try save()
                                    proxy.update(domains: DomainResolver.expanded(domains), addresses: next.addresses, active: true)
                                    try setRules(next.addresses)
                                    message = "Цели добавлены. Прежние запреты и срок сохранены."
                                }
                            } catch { accepted = false; message = "Добавление не подтверждено: \(error). Действующее окно не отменено." }
                        }
                    } else { accepted = false; message = "Активного окна уже нет. Выберите цели для нового запуска." }
                } else if ["start", "validate", "validate-strict", "validate-app"].contains(request.command) {
                    if ledger.window != nil { accepted = false; message = "Защита уже действует. Изменить или сократить окно нельзя." }
                    else {
                        let validation = request.command != "start"
                        let seconds = validation ? 90 : request.seconds
                        let domains = Array(Set(request.domains.map { $0.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) })).sorted()
                        // The built-in connectivity check is restricted to one
                        // public diagnostic page. It cannot create a short
                        // window for personal targets or alter an active one.
                        let fixedDiagnostic = request.command == "validate-app" ?
                            (domains.isEmpty && request.applications.count == 1 && request.applications[0].signingID == "com.google.Chrome") :
                            (domains == ["info.cern.ch"] && request.applications.isEmpty)
                        let valid = (!validation || fixedDiagnostic) && (validation || (300...30*24*3600).contains(seconds)) &&
                            domains.count <= SiteDomainPolicy.maximumDomains && SiteDomainPolicy.validList(domains.isEmpty ? nil : domains, includeSubdomains: true) &&
                            request.applications.count <= 40 && Set(request.applications.map(\.signingID)).count == request.applications.count &&
                            request.applications.allSatisfy { ProcessSignatureRequirement.valid($0) && !WindowGuardV2.isProtected($0.signingID) } &&
                            (!domains.isEmpty || !request.applications.isEmpty)
                        if !valid { accepted = false; message = "Выберите разрешённые цели и срок от5 минут до30 дней. Короткие диагностики ограничены фиксированными целями." }
                        else if !domains.isEmpty && request.command != "validate" && request.applications.contains(where: { $0.signingID == "com.google.Chrome" }) {
                            accepted = false; message = "Chrome нужен для защищённого доступа. Уберите его из выбранных приложений."
                        } else if !domains.isEmpty && request.command != "validate" && browser.pid == nil {
                            try? browser.open(); accepted = false; message = "Сначала дождитесь открытия защищённого Chrome, затем включите окно."
                        } else {
                          do {
                            try DomainResolver.prepare(DomainResolver.expanded(domains))
                            var ips = Set<String>(); for domain in domains { ips.formUnion(resolve(domain)); ips.formUnion(resolve("www." + domain)) }
                            if !domains.isEmpty && ips.isEmpty { accepted = false; message = "Адреса сайтов не получены; защита не включена." }
                            else {
                                ledger.window = ProtectionWindow(id: UUID(), domains: domains, applications: request.applications, remaining: Double(seconds), boot: boot, lastTick: continuous(), addresses: ips, validation: request.command == "validate", diagnostic: validation)
                                if validation { ledger.validationUsed = true }
                                try save(); lastDNS = now
                                proxy.update(domains: DomainResolver.expanded(domains), addresses: ips, active: true)
                                do { try setRules(ips); message = "Защита включена. Закрытие приложения её не снимает." }
                                catch { accepted = false; errorText = "Сетевое ограничение не подтверждено: \(error)"; message = errorText! }
                            }
                          } catch { accepted = false; message = "Окно не подготовлено: \(error)" }
                        }
                    }
                } else { accepted = false; message = "Активное окно снимается только по истечении срока." }
            }
            let window = ledger.window
            let replyStrictBrowser = window.map { !$0.domains.isEmpty && $0.validation != true } ?? false
            let reply = SystemProtectionReply(accepted: accepted, message: errorText ?? message, active: window != nil,
                secondsRemaining: Int(ceil(window?.remaining ?? 0)), domains: window?.domains ?? [], applicationCount: window?.applications.count ?? 0,
                networkActive: network, blockedAddressCount: applied.count, validationAvailable: window == nil, servicePID: getpid(),
                dnsActive: !appliedResolvers.isEmpty, dnsDeniedQueries: dns.deniedQueries,
                managedBrowserPID: browser.pid, managedBrowserOnly: replyStrictBrowser,
                exitSecondsRemaining: window?.exitRemaining.map { Int(ceil($0)) }, bootResumes: window?.bootResumes ?? 0,
                recoveredFromBackup: recoveredBackup, windowID: window?.id, lastBootRecovery: ledger.lastBootRecovery)
            try? writeRootFrame(JSONEncoder().encode(reply), fd: peer, timeout: 1)
        }
        while true { try autoreleasepool { try iteration() } }
    }
}

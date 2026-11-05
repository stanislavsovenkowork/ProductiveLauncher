import AppKit
import Foundation
import Darwin

@main
struct ObserverMain {
    static func main() throws {
        let args = CommandLine.arguments
        func value(_ flag: String) -> String? {
            guard let index = args.firstIndex(of: flag), index + 1 < args.count else { return nil }
            return args[index + 1]
        }
        guard let output = value("--output"), output.hasPrefix("/"),
              let retention = Int(value("--retention-days") ?? "7"), (1...365).contains(retention) else {
            throw NSError(domain: "ObserverArguments", code: 1)
        }
        let snapshotPath = value("--session-snapshot")
        if let snapshotPath, !snapshotPath.hasPrefix("/") { throw NSError(domain: "ObserverArguments", code: 2) }
        let snapshot = snapshotPath.map { URL(fileURLWithPath: $0) }
        let parent = URL(fileURLWithPath:output).deletingLastPathComponent()
        try FileManager.default.createDirectory(at:parent,withIntermediateDirectories:true,attributes:[.posixPermissions:0o700])
        let lock = open(output + ".lock",O_CREAT | O_RDWR,mode_t(0o600))
        guard lock >= 0, flock(lock,LOCK_EX | LOCK_NB) == 0 else { throw NSError(domain:"ObserverAlreadyRunning",code:1) }
        defer { close(lock) }
        // Explicit fixture inputs disable all AppKit collection and never consult global configuration.
        if let fixture = value("--fixture") {
            let journal = ContextJournal(path: URL(fileURLWithPath: output), retentionDays: retention)
            let data = try Data(contentsOf: URL(fileURLWithPath: fixture))
            let samples = try JSONSerialization.jsonObject(with: data) as! [[String: Any]]
            var recorder = ContextRecorder()
            for item in samples {
                let sample = ContextSample(wall: Date(timeIntervalSince1970: item["wall"] as! Double),
                    monotonic: item["monotonic"] as! Double, bundleID: item["bundleId"] as? String,
                    available: item["available"] as! Bool, session: nil)
                try journal.append(recorder.sample(sample, reason: "fixture"), now: sample.wall)
            }
            return
        }
        let application = NSApplication.shared
        application.setActivationPolicy(.prohibited)
        let observer = PassiveObserver(journal: ContextJournal(path: URL(fileURLWithPath: output),retentionDays:retention), snapshot:snapshot)
        observer.start()
        application.run()
        withExtendedLifetime(observer) {}
    }
}

final class PassiveObserver {
    let journal: ContextJournal
    let snapshot: URL?
    var recorder = ContextRecorder()
    var available = true
    var subscriptions: [NSObjectProtocol] = []
    var timer: Timer?
    var exitSignals: [DispatchSourceSignal] = []

    init(journal: ContextJournal, snapshot: URL?) { self.journal = journal; self.snapshot = snapshot }

    func emit(_ reason: String) {
        let now = Date()
        let link = snapshot.flatMap { try? Data(contentsOf: $0) }
            .flatMap { try? JSONDecoder().decode(PassiveSessionSnapshot.self, from: $0) }?.link(now: now)
        let sample = ContextSample(wall: now, monotonic: ProcessInfo.processInfo.systemUptime,
            bundleID: available ? NSWorkspace.shared.frontmostApplication?.bundleIdentifier : nil,
            available: available, session: link)
        do { try journal.append(recorder.sample(sample, reason: reason), now: now) }
        catch {
            // A bounded status line only; no app identifiers, paths or record contents.
            FileHandle.standardError.write(Data("observer_journal_unavailable\n".utf8))
            exit(1)
        }
    }

    func start() {
        let center = NSWorkspace.shared.notificationCenter
        subscriptions.append(center.addObserver(forName: NSWorkspace.didActivateApplicationNotification,object:nil,queue:.main) { [weak self] _ in self?.emit("frontmost_transition") })
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.screensDidSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            subscriptions.append(center.addObserver(forName:name,object:nil,queue:.main) { [weak self] _ in
                guard let self else { return }; self.emit("availability_ended"); self.available = false; self.emit("unavailable")
            })
        }
        for name in [NSWorkspace.didWakeNotification, NSWorkspace.screensDidWakeNotification, NSWorkspace.sessionDidBecomeActiveNotification] {
            subscriptions.append(center.addObserver(forName:name,object:nil,queue:.main) { [weak self] _ in
                guard let self else { return }; self.emit("availability_returned"); self.available = true; self.emit("available")
            })
        }
        timer = Timer.scheduledTimer(withTimeInterval:30,repeats:true) { [weak self] _ in self?.emit("sample") }
        for code in [SIGTERM,SIGINT] {
            signal(code,SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal:code,queue:.main)
            source.setEventHandler { [weak self] in self?.emit("shutdown"); NSApplication.shared.terminate(nil) }
            source.resume(); exitSignals.append(source)
        }
        emit("startup")
    }
}

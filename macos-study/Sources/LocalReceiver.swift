import Foundation

// No personal text or token is sent outside IPv4 loopback. Journal is authoritative;
// cursor advances only after accepted/duplicate response. Retry uses stable event IDs.
private final class LoopbackRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping @Sendable (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
protocol ReceiverSyncing { var status: String { get }; func sync() async }

@MainActor
final class LocalReceiver: ReceiverSyncing {
    struct Configuration: Decodable {
        let schemaVersion: Int
        let host: String
        let port: Int
        let tokenFile: String
    }
    private var inFlight = false
    private var lastAttempt = Date.distantPast
    private(set) var status = "Локальная связь ещё не проверена"
    let journal: SessionStore
    init(journal: SessionStore) { self.journal = journal }
    func sync() async {
        #if FOCUSDECK_SM_FIXTURE || USER_GUARD_QA
        status = "Тестовая сборка · передача отключена"
        return
        #else
        guard !inFlight, Date().timeIntervalSince(lastAttempt) > 10 else { return }
        let environment = ProcessInfo.processInfo.environment
        if environment["FOCUSDECK_DATA_DIR"] != nil && environment["FOCUSDECK_RECEIVER_CONFIG"] == nil {
            status = "Изолированная проверка · передача отключена"; return
        }
        inFlight = true; lastAttempt = Date(); defer { inFlight = false }
        do {
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            let path = environment["FOCUSDECK_RECEIVER_CONFIG"].map { URL(fileURLWithPath: $0) } ??
                support.appendingPathComponent("FocusDeckHarness/receiver/receiver-config.json")
            let config = try JSONDecoder().decode(Configuration.self, from: Data(contentsOf: path))
            guard config.schemaVersion == 1, config.host == "127.0.0.1", config.port > 0, config.port <= 65535,
                  (config.port == 48731 || environment["FOCUSDECK_RECEIVER_CONFIG"] != nil),
                  config.tokenFile.hasPrefix("/") else { throw SyncError.invalidConfiguration }
            let token = try String(contentsOfFile: config.tokenFile, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !token.isEmpty, !token.contains("\n") else { throw SyncError.invalidConfiguration }
            let lines = (try? String(contentsOf: journal.eventsURL, encoding: .utf8))?.split(separator: "\n").map(String.init) ?? []
            let cursorURL = journal.directory.appendingPathComponent("receiver-cursor.json")
            let cursor = (try? JSONDecoder().decode(Int.self, from: Data(contentsOf: cursorURL))) ?? 0
            guard cursor >= 0, cursor <= lines.count else { throw SyncError.invalidCursor }
            let batch = Array(lines.dropFirst(cursor).prefix(128))
            let suffix = batch.isEmpty ? "/v1/health" : "/v1/events"
            var request = URLRequest(url: URL(string: "http://127.0.0.1:\(config.port)" + suffix)!)
            request.timeoutInterval = 3
            request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
            if !batch.isEmpty {
                request.httpMethod = "POST"
                request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                let events = try batch.map { try JSONSerialization.jsonObject(with: Data($0.utf8)) }
                request.httpBody = try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "events": events])
                guard request.httpBody!.count <= 128 * 1024 else { throw SyncError.oversize }
            }
            let settings = URLSessionConfiguration.ephemeral
            settings.connectionProxyDictionary = [:]
            let client = URLSession(configuration: settings, delegate: LoopbackRedirectPolicy(), delegateQueue: nil)
            defer { client.finishTasksAndInvalidate() }
            let (_, response) = try await client.data(for: request)
            guard let response = response as? HTTPURLResponse else { throw SyncError.badResponse }
            if response.statusCode == 409 { status = "Конфликт записей: журнал сохранён"; return }
            guard [200, 202].contains(response.statusCode) else { throw SyncError.badResponse }
            if !batch.isEmpty {
                try journal.prepare()
                try JSONEncoder().encode(cursor + batch.count).write(to: cursorURL, options: .atomic)
            }
            status = cursor + batch.count == lines.count ? "Локальная связь готова · журнал передан" : "Локальная связь готова · очередь передаётся"
        } catch {
            status = "Локальная связь недоступна · журнал сохранён на Mac"
        }
        #endif
    }
    enum SyncError: Error { case invalidConfiguration, invalidCursor, badResponse, oversize }
}

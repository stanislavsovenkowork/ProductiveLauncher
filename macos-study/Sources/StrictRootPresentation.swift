import Foundation

// Local UI adapter only. The task/context is not sent to root, and a countdown
// is never advanced locally to authorize a transition. Every mutation requires
// an authenticated, recent authority snapshot; root validates again on receipt.
struct StrictRootPresentation {
    let taskContext: String?
    private(set) var snapshot: StrictRootSnapshot?
    private(set) var confirmedAt: Double?
    private(set) var authorityConfirmed = false
    private(set) var clockUnknown = false
    private(set) var noWindow = false
    static let freshnessSeconds = 5.0

    init(taskContext: String?) { self.taskContext = taskContext }
    mutating func accept(_ reply: StrictRootReply?, peerVerified: Bool, at monotonic: Double) {
        guard peerVerified, monotonic.isFinite, monotonic >= 0, let reply, reply.schemaVersion == 1 else {
            authorityConfirmed = false; clockUnknown = false; noWindow = false; confirmedAt = nil; return
        }
        if reply.accepted, reply.reason == "not_enrolled", reply.snapshot == nil, snapshot == nil {
            authorityConfirmed = true; clockUnknown = false; noWindow = true; confirmedAt = monotonic; return
        }
        guard let current = reply.snapshot else {
            authorityConfirmed = false; clockUnknown = false; noWindow = false; confirmedAt = nil; return
        }
        if let previous = snapshot, previous.plan != current.plan,
           ![.exit, .expired, .emergencyExit].contains(previous.phase) {
            authorityConfirmed = false; clockUnknown = false; noWindow = false; confirmedAt = nil; return
        }
        if current.clock == .unknown {
            snapshot = current; authorityConfirmed = false; clockUnknown = true; noWindow = false; confirmedAt = nil; return
        }
        guard reply.accepted else { authorityConfirmed = false; clockUnknown = false; noWindow = false; confirmedAt = nil; return }
        snapshot = current; confirmedAt = monotonic; authorityConfirmed = true; clockUnknown = false; noWindow = false
    }
    var connectionText: String {
        if clockUnknown, snapshot?.phase == .emergencyExit { return "Аварийный выход записан; действие на цели нужно проверить отдельно." }
        if clockUnknown { return "Служба ответила, но время неизвестно; действие на цели не подтверждено. Доступно отдельное аварийное восстановление." }
        if noWindow && authorityConfirmed { return "Служба ответила; окно ещё не настроено." }
        return authorityConfirmed ? "Состояние службы подтверждено; действие на цели проверяется отдельно." : "Связь неизвестна; защита не подтверждена."
    }
    func command(_ action: StrictRootCommand, at monotonic: Double) -> StrictRootRequest? {
        guard action != .status, authorityConfirmed, let snapshot, snapshot.clock == .verified,
              let confirmedAt, monotonic.isFinite, monotonic >= confirmedAt,
              monotonic - confirmedAt <= Self.freshnessSeconds else { return nil }
        let allowed: Bool
        switch action {
        case .rest: allowed = snapshot.phase == .active
        case .return: allowed = snapshot.phase == .rest
        case .requestExit: allowed = [.inactive, .active, .rest].contains(snapshot.phase)
        case .cancelExit: allowed = snapshot.phase == .exitPending
        case .confirmExit: allowed = snapshot.phase == .exitPending && snapshot.exitRemainingSeconds == 0
        case .status: allowed = false
        }
        guard allowed else { return nil }
        return StrictRootRequest(schemaVersion:1, revisionId:snapshot.plan.revisionId, windowId:snapshot.plan.windowId, command:action)
    }
}

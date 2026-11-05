import Foundation

// V2 is separate from the legacy 25-minute guard. The configured root trust is
// authoritative for targets. This candidate never touches DNS or hosts.
struct WindowGuardRequest: Codable, Sendable {
    let policy: WindowPolicy
    let command: String // publish, update, status
    var signingRequirements: [TrustedSigningRequirement]? = nil
    var schedule: WindowSchedule? = nil
}
struct WindowGuardReply: Codable, Sendable {
    let accepted: Bool
    let reason: String
    let windowId: UUID?
    let enforcement: String
    let policy: WindowPolicy?
    var scheduleStatus: String? = nil
}
struct WindowGuardV2 {
    let allowedTargets: Set<String>
    static func isProtected(_ id: String) -> Bool {
        let value = id.lowercased()
        return StudyPolicy.isInfrastructure(id) || ["vpn", "tunnel", "wireguard", "tailscale", "zerotier", "globalprotect", "fortinet", "cisco", "gitlab", "codex"].contains(where: { value.contains($0) })
    }
    static func validSigningID(_ id: String) -> Bool {
        !id.isEmpty && id.count <= 255 && id.contains(".") && id.unicodeScalars.allSatisfy { CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._").contains($0) }
    }
    let trustedUID: UInt32
    let trustedHash: String
    var requireCalendar = false
    func apply(_ request: WindowGuardRequest, current: WindowPolicy?, peerUID: UInt32, peerHash: String?, now: Date, uptime: Double = ProcessInfo.processInfo.systemUptime, boot: String? = WindowPolicy.bootID()) -> (WindowPolicy?, WindowGuardReply) {
        func reply(_ accepted: Bool, _ reason: String, _ result: WindowPolicy?) -> (WindowPolicy?, WindowGuardReply) {
            let enforced = result.map { Self.enforcing($0, now: now) } ?? false
            return (result, WindowGuardReply(accepted: accepted, reason: reason, windowId: accepted ? result?.windowId : nil, enforcement: accepted ? (enforced ? "selected_apps_requested" : "released") : "unknown", policy: accepted ? result : nil))
        }
        guard peerUID == trustedUID, peerHash?.lowercased() == trustedHash.lowercased() else { return reply(false, "unauthorized", current) }
        if request.command == "status" { return reply(true, "observed", current) }
        let p = request.policy
        guard p.policyVersion == 2, p.startsAt < p.endsAt, p.endsAt > now,
              p.endsAt.timeIntervalSince(p.startsAt) <= 9 * 3600,
              (0...3600).contains(p.reliefBudgetSeconds), (1...900).contains(p.reliefMaxGrantSeconds),
              (1...120).contains(p.exitCoolingOffSeconds), p.reliefRemainingSeconds >= 0,
              p.reliefRemainingSeconds <= p.reliefBudgetSeconds,
              (!p.targetRefs.isEmpty || !(p.siteDomains ?? []).isEmpty), !(p.siteSubdomains == true && p.siteDomains == nil), SiteDomainPolicy.validList(p.siteDomains, includeSubdomains: p.siteSubdomains == true), Set(p.targetRefs) == allowedTargets,
              allowedTargets.count <= 64, allowedTargets.allSatisfy(Self.validSigningID),
              !allowedTargets.contains(where: Self.isProtected) else { return reply(false, "invalid_or_untrusted_targets", current) }
        if requireCalendar {
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(identifier: "Europe/Minsk")!
            let parts = calendar.dateComponents([.year, .month, .day, .hour], from: p.startsAt)
            let day = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
            guard p.timezone == "Europe/Minsk", (parts.hour ?? -1) >= 15, calendar.isDate(p.startsAt, inSameDayAs: p.endsAt), p.localDate == day else { return reply(false, "invalid_calendar_window", current) }
        }
        if let old = current, old.windowId == p.windowId, (old.state == .exit || old.state == .expired || now >= old.endsAt) {
            if p == old { return reply(true, "terminal_window_observed", old) }
            return reply(false, "terminal_window", old)
        }
        if let old = current, old.endsAt > now && old.state != .exit && old.state != .expired {
            guard p.windowId == old.windowId, p.policyRevisionId == old.policyRevisionId, p.startsAt == old.startsAt, p.endsAt == old.endsAt,
                  p.targetRefs == old.targetRefs, p.siteDomains == old.siteDomains, p.siteSubdomains == old.siteSubdomains, p.reliefBudgetSeconds == old.reliefBudgetSeconds,
                  p.reliefMaxGrantSeconds == old.reliefMaxGrantSeconds,
                  p.exitCoolingOffSeconds == old.exitCoolingOffSeconds,
                  p.reliefRemainingSeconds <= old.reliefRemainingSeconds,
                  old.state != .exit && old.state != .expired else { return reply(false, "active_window_immutable", old) }
            let legal: Bool
            switch old.state {
            case .inactive: legal = [.inactive, .active, .exitPending].contains(p.state)
            case .active: legal = [.active, .limitedRelief, .rest, .exitPending].contains(p.state)
            case .limitedRelief: legal = [.limitedRelief, .active, .exitPending].contains(p.state)
            case .rest: legal = [.rest, .active, .limitedRelief, .exitPending].contains(p.state)
            case .exitPending: legal = [.exitPending, .active, .rest, .limitedRelief, .exit].contains(p.state)
            case .exit, .expired: legal = false
            }
            guard legal else { return reply(false, "invalid_transition", old) }
            if p.state == .limitedRelief && old.state != .limitedRelief && old.state != .exitPending {
                guard let until = p.reliefUntil, until > now, until <= p.endsAt,
                      until.timeIntervalSince(now) <= Double(p.reliefMaxGrantSeconds) + 1,
                      old.reliefRemainingSeconds > p.reliefRemainingSeconds,
                      until.timeIntervalSince(now) <= Double(old.reliefRemainingSeconds - p.reliefRemainingSeconds) + 1 else { return reply(false, "invalid_grant", old) }
            }
            if old.state == .limitedRelief && p.state == .limitedRelief {
                guard p.reliefUntil == old.reliefUntil && p.reliefRemainingSeconds == old.reliefRemainingSeconds else { return reply(false, "grant_immutable", old) }
            }
            if old.state != .exitPending && p.state == .exitPending {
                guard p.stateBeforeExitPending == old.state, p.exitBootID == boot,
                      let deadline = p.exitUptimeDeadline, deadline >= uptime, deadline <= uptime + Double(p.exitCoolingOffSeconds) + 2,
                      let wall = p.exitAvailableAt, wall >= now,
                      wall.timeIntervalSince(now) <= Double(p.exitCoolingOffSeconds) + 2 else { return reply(false, "invalid_exit_request", old) }
            }
            if old.state == .exitPending && p.state == .exitPending {
                guard p.exitAvailableAt == old.exitAvailableAt && p.exitUptimeDeadline == old.exitUptimeDeadline && p.exitBootID == old.exitBootID else { return reply(false, "exit_deadline_immutable", old) }
            }
            if p.state == .exit {
                guard old.canConfirmExit(now: now, uptime: uptime, boot: boot) else { return reply(false, "exit_delay_not_elapsed", old) }
            }
        } else {
            guard request.command == "publish", p.state == .inactive || p.state == .active,
                  p.reliefRemainingSeconds == p.reliefBudgetSeconds, p.reliefUntil == nil,
                  p.exitAvailableAt == nil, current.map({ p.localDate > $0.localDate }) ?? true else {
                return reply(false, "new_window_requires_clean_state_and_new_day", current)
            }
        }
        guard request.command == "publish" || request.command == "update" else { return reply(false, "unknown_command", current) }
        return reply(true, "saved_candidate", p)
    }
    static func enforcing(_ p: WindowPolicy, now: Date) -> Bool {
        guard now >= p.startsAt, now < p.endsAt, p.state != .exit, p.state != .expired else { return false }
        if p.state == .limitedRelief || (p.state == .exitPending && p.stateBeforeExitPending == .limitedRelief) {
            if let relief = p.reliefUntil, now < relief { return false }
        }
        return true
    }
    func attempt(_ signingID: String, policy: WindowPolicy, now: Date, terminate: (String) -> Bool) -> Bool {
        guard Self.enforcing(policy, now: now), allowedTargets.contains(signingID), policy.targetRefs.contains(signingID),
              !Self.isProtected(signingID) else { return false }
        return terminate(signingID)
    }
}

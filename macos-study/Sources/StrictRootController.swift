import Foundation
import Combine
import Darwin

enum StrictRootContinuousClock {
    static func seconds() -> Double {
        var info = mach_timebase_info_data_t(); guard mach_timebase_info(&info) == KERN_SUCCESS, info.denom != 0 else { return .nan }
        return Double(mach_continuous_time()) * Double(info.numer) / Double(info.denom) / 1_000_000_000
    }
}

// Real UI-facing client of the authenticated IPC. No mutation is sent until a
// fresh discover response proves the same immutable plan and window identity.
@MainActor final class StrictRootController: ObservableObject {
    @Published private(set) var presentation: StrictRootPresentation
    @Published private(set) var busy = false
    @Published private(set) var message = "Связь со строгой службой ещё не проверена."
    private let transport: any StrictRootAuthorityTransport
    init(transport: any StrictRootAuthorityTransport, taskContext: String?) {
        self.transport = transport; presentation = StrictRootPresentation(taskContext:taskContext)
    }
    private func send(_ value: StrictRootEnvelope) async -> StrictRootReply? {
        let transport = self.transport
        return await Task.detached(priority:.userInitiated) { try? transport.send(value) }.value
    }
    private func discover() async -> StrictRootReply? {
        let reply = await send(.init(schemaVersion:1,kind:.discover,enrollment:nil,request:nil))
        presentation.accept(reply,peerVerified:reply != nil,at:StrictRootContinuousClock.seconds())
        message = presentation.connectionText
        return reply
    }
    func refresh() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        _ = await discover()
    }
    @discardableResult func perform(_ action: StrictRootCommand) async -> Bool {
        guard !busy, let expected = presentation.snapshot?.plan else { return false }
        busy = true; defer { busy = false }
        let current = await discover()
        guard current?.accepted == true, current?.snapshot?.plan == expected,
              let request = presentation.command(action,at:StrictRootContinuousClock.seconds()) else { return false }
        let reply = await send(.init(schemaVersion:1,kind:.command,enrollment:nil,request:request))
        guard let reply, reply.snapshot?.plan == expected else {
            presentation.accept(nil,peerVerified:false,at:StrictRootContinuousClock.seconds()); message = presentation.connectionText; return false
        }
        presentation.accept(reply,peerVerified:true,at:StrictRootContinuousClock.seconds())
        message = presentation.connectionText
        guard reply.accepted, let snapshot = reply.snapshot, snapshot.plan == expected,
              presentation.authorityConfirmed else { return false }
        return true
    }
    // The caller must obtain explicit user consent for this exact plan first.
    @discardableResult func enroll(_ plan: StrictRootPlan) async -> Bool {
        guard !busy, plan.valid(requirement:{_ in true}) else { return false }
        busy = true; defer { busy = false }
        let existing = await discover()
        guard existing?.accepted == true,
              (existing?.reason == "not_enrolled" && existing?.snapshot == nil || existing?.snapshot?.plan == plan),
              presentation.authorityConfirmed else { return false }
        let enrollment = StrictRootEnrollment(schemaVersion:1,plan:plan)
        let reply = await send(.init(schemaVersion:1,kind:.enroll,enrollment:enrollment,request:nil))
        guard let reply, reply.snapshot?.plan == plan else {
            presentation.accept(nil,peerVerified:false,at:StrictRootContinuousClock.seconds()); message = presentation.connectionText; return false
        }
        presentation.accept(reply,peerVerified:true,at:StrictRootContinuousClock.seconds())
        message = presentation.connectionText
        return reply.accepted && presentation.authorityConfirmed
    }
}

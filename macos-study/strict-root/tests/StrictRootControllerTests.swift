import Foundation

final class FakeTransport: StrictRootAuthorityTransport, @unchecked Sendable {
    private let lock=NSLock();private var queue:[StrictRootReply?];private var sent:[StrictRootEnvelopeKind]=[]
    init(_ values:[StrictRootReply?]) { queue=values }
    func send(_ envelope: StrictRootEnvelope) throws -> StrictRootReply {
        lock.lock();defer { lock.unlock() };sent.append(envelope.kind)
        guard !queue.isEmpty, let reply=queue.removeFirst() else { throw Failure.missing };return reply
    }
    var calls:[StrictRootEnvelopeKind] { lock.lock();defer { lock.unlock() };return sent }
    enum Failure:Error { case missing }
}
@main enum StrictRootControllerTests {
 @MainActor static func main() async throws {
    let wall=Date(timeIntervalSince1970:1_800_000_000), requirement=TrustedSigningRequirement(signingID:"dev.focusdeck.fixture",teamID:"SYNTHETIC",requirement:"fixture")
    let plan=StrictRootPlan(schemaVersion:1,revisionId:UUID(),windowId:UUID(),startsAt:wall,endsAt:wall.addingTimeInterval(7200),appRequirements:[requirement],siteDomains:[],siteSubdomains:false)
    let other=StrictRootPlan(schemaVersion:1,revisionId:UUID(),windowId:UUID(),startsAt:wall,endsAt:wall.addingTimeInterval(7200),appRequirements:[requirement],siteDomains:[],siteSubdomains:false)
    let clock=StrictRootClock(wall:wall,continuous:100,bootID:"test")
    let record=try StrictRootRecord.enroll(plan,at:clock,requirement:{_ in true})
    let otherRecord=try StrictRootRecord.enroll(other,at:clock,requirement:{_ in true})
    let status=StrictRootReply(schemaVersion:1,accepted:true,reason:"observed",snapshot:record.snapshot())
    let different=StrictRootReply(schemaVersion:1,accepted:true,reason:"observed",snapshot:otherRecord.snapshot())
    let absent=StrictRootReply(schemaVersion:1,accepted:true,reason:"not_enrolled",snapshot:nil)
    let missing=FakeTransport([nil]);let unknown=StrictRootController(transport:missing,taskContext:"keep task")
    await unknown.refresh();let missingResult=await unknown.enroll(plan)
    precondition(!missingResult && missing.calls == [.discover,.discover] && unknown.presentation.taskContext == "keep task")
    let changed=FakeTransport([status,different]);let strict=StrictRootController(transport:changed,taskContext:"keep task")
    await strict.refresh();let changedResult=await strict.perform(.requestExit)
    precondition(!changedResult && changed.calls == [.discover,.discover] && strict.presentation.taskContext == "keep task")
    let create=FakeTransport([absent,status]);let new=StrictRootController(transport:create,taskContext:"keep task")
    let created=await new.enroll(plan);precondition(created && create.calls == [.discover,.enroll] && new.presentation.snapshot?.plan == plan)
    var restRecord=record;_=restRecord.apply(.init(schemaVersion:1,revisionId:plan.revisionId,windowId:plan.windowId,command:.rest),at:.init(wall:wall,continuous:101,bootID:"test"))
    let rest=StrictRootReply(schemaVersion:1,accepted:true,reason:"rest",snapshot:restRecord.snapshot())
    let correct=FakeTransport([status,status,rest]);let ui=StrictRootController(transport:correct,taskContext:"keep task")
    await ui.refresh();let rested=await ui.perform(.rest)
    precondition(rested && correct.calls == [.discover,.discover,.command] && ui.presentation.snapshot?.phase == .rest && ui.presentation.taskContext == "keep task")
    let malformed=FakeTransport([status,status,different]);let guarded=StrictRootController(transport:malformed,taskContext:"keep task")
    await guarded.refresh();let mismatchedReply=await guarded.perform(.rest)
    precondition(!mismatchedReply && !guarded.presentation.authorityConfirmed && guarded.presentation.snapshot?.plan == plan)
    let badEnrollment=FakeTransport([absent,different]);let untrusted=StrictRootController(transport:badEnrollment,taskContext:nil)
    let badCreated=await untrusted.enroll(plan);precondition(!badCreated && !untrusted.presentation.authorityConfirmed && untrusted.presentation.snapshot == nil)
    let stale=FakeTransport([status,nil]);let disconnected=StrictRootController(transport:stale,taskContext:nil)
    await disconnected.refresh();let rejected=await disconnected.perform(.requestExit)
    precondition(!rejected && stale.calls == [.discover,.discover])
    print("PASS: UI controller requires fresh authenticated discovery and matching immutable window before mutation; context retained, no writes on missing/mismatched status.")
 }
}

import Foundation
@main enum ProtectionTargetTests {
    static func main() throws {
        guard CommandLine.arguments.count == 4 else { fatalError("synthetic signed, tampered and protected bundles required") }
        do { _ = try ProtectionTarget.inspect(URL(fileURLWithPath: CommandLine.arguments[1])); fatalError("ad-hoc target accepted") }
        catch ProtectionTarget.Failure.untrustedTeam { }
        let target = try ProtectionTarget.inspect(URL(fileURLWithPath: CommandLine.arguments[1]), allowSyntheticAdHoc: true)
        precondition(target.id == "dev.stanislav.focusdeck.guard-fixture-two")
        do { _ = try ProtectionTarget.inspect(URL(fileURLWithPath: CommandLine.arguments[2]), allowSyntheticAdHoc: true); fatalError("tampered app accepted") }
        catch ProtectionTarget.Failure.invalidSignature { }
        do { _ = try ProtectionTarget.inspect(URL(fileURLWithPath: CommandLine.arguments[3]), allowSyntheticAdHoc: true); fatalError("protected app accepted") }
        catch ProtectionTarget.Failure.protected { }
        print("App selector verified signed synthetic bundle, rejected tampering and protected ID; no personal app read or altered.")
    }
}

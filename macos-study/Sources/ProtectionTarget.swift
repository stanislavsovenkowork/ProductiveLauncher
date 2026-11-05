import Foundation
import Security

struct ProtectionTarget: Identifiable, Equatable {
    let id: String
    let name: String
    var path: String? = nil
    var teamID: String? = nil
    var requirement: String? = nil
    static func inspect(_ url: URL, allowSyntheticAdHoc: Bool = false) throws -> ProtectionTarget {
        guard url.pathExtension.lowercased() == "app", let bundle = Bundle(url: url), let bundleID = bundle.bundleIdentifier else { throw Failure.invalidBundle }
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, SecCSFlags(), &code) == errSecSuccess, let code,
              SecStaticCodeCheckValidity(code, SecCSFlags(rawValue: kSecCSStrictValidate), nil) == errSecSuccess else { throw Failure.invalidSignature }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let map = info as? [String: Any], let signingID = map[kSecCodeInfoIdentifier as String] as? String,
              signingID == bundleID, WindowGuardV2.validSigningID(signingID) else { throw Failure.identityMismatch }
        guard !WindowGuardV2.isProtected(signingID) else { throw Failure.protected }
        let team = map[kSecCodeInfoTeamIdentifier as String] as? String
        var designated: SecRequirement?
        var requirementString: CFString?
        guard SecCodeCopyDesignatedRequirement(code, SecCSFlags(), &designated) == errSecSuccess, let designated,
              SecRequirementCopyString(designated, SecCSFlags(), &requirementString) == errSecSuccess,
              let requirement = requirementString as String?, !requirement.isEmpty else { throw Failure.invalidSignature }
        guard allowSyntheticAdHoc || (team?.count == 10 && team!.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").contains($0) })) else { throw Failure.untrustedTeam }
        return ProtectionTarget(id: signingID, name: (bundle.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String) ?? (bundle.object(forInfoDictionaryKey: "CFBundleName") as? String) ?? url.deletingPathExtension().lastPathComponent, path: url.standardizedFileURL.path, teamID: team, requirement: requirement)
    }
    enum Failure: Error { case invalidBundle, invalidSignature, identityMismatch, protected, untrustedTeam }
}

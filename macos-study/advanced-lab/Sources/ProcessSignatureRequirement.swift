import Foundation
import Security

// Production target authorization requires all three: signing ID, Team ID, and
// the exact designated requirement captured from the user-selected application.
enum ProcessSignatureRequirement {
    static func permits(_ expected: TrustedSigningRequirement, signingID: String?, teamID: String?, requirementMatches: Bool) -> Bool {
        signingID == expected.signingID && teamID == expected.teamID && requirementMatches
    }
    static func valid(_ expected: TrustedSigningRequirement) -> Bool {
        guard WindowGuardV2.validSigningID(expected.signingID), !WindowGuardV2.isProtected(expected.signingID),
              expected.teamID.count == 10, expected.teamID.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").contains($0) }),
              !expected.requirement.isEmpty, expected.requirement.utf8.count <= 4096 else { return false }
        var requirement: SecRequirement?
        return SecRequirementCreateWithString(expected.requirement as CFString, SecCSFlags(), &requirement) == errSecSuccess && requirement != nil
    }
    static func matches(pid: Int32, expected: TrustedSigningRequirement) -> Bool {
        var code: SecCode?, requirement: SecRequirement?
        guard SecRequirementCreateWithString(expected.requirement as CFString, SecCSFlags(), &requirement) == errSecSuccess, let requirement,
              SecCodeCopyGuestWithAttributes(nil, [kSecGuestAttributePid: NSNumber(value: pid)] as CFDictionary, SecCSFlags(), &code) == errSecSuccess,
              let code, SecCodeCheckValidity(code, SecCSFlags(), requirement) == errSecSuccess else { return false }
        var staticCode: SecStaticCode?, details: CFDictionary?
        guard SecCodeCopyStaticCode(code, SecCSFlags(), &staticCode) == errSecSuccess, let staticCode,
              SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &details) == errSecSuccess,
              let values = details as? [String: Any] else { return false }
        return permits(expected, signingID: values[kSecCodeInfoIdentifier as String] as? String,
                       teamID: values[kSecCodeInfoTeamIdentifier as String] as? String, requirementMatches: true)
    }
}

import Foundation

/// Where the family's web app lives. Set at build time (APP_URL), the same
/// value as the Android build, and carried through Info.plist.
enum Config {
    static let appURL: URL = {
        let s = Bundle.main.object(forInfoDictionaryKey: "FamilyAppURL") as? String ?? ""
        if let u = URL(string: s), u.scheme == "https", u.host != nil { return u }
        return URL(string: "https://example.com/")!
    }()
    static let host: String = appURL.host ?? ""
    static let version: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0"
    static let build: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"

    /// Only the family app's own address opens inside the app (it can reach the bridges).
    static func allowed(_ u: URL?) -> Bool {
        guard let u, u.scheme == "https" else { return false }
        return u.host == host
    }
}

/// A Swift string as a JavaScript string literal.
func jsQuote(_ s: String) -> String {
    guard let d = try? JSONSerialization.data(withJSONObject: [s], options: []),
          let t = String(data: d, encoding: .utf8) else { return "\"\"" }
    return String(t.dropFirst().dropLast())
}

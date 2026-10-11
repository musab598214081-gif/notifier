import Foundation

/// What the page has told the app (the same things it tells sw.js), kept between launches.
enum Store {
    private static let d = UserDefaults.standard

    /// The app is on screen right now.
    static var foreground = false

    /// { send, me, sec, dk, name, url } - where the family server is and who this phone is.
    static var cfg: [String: Any]? {
        get { d.dictionary(forKey: "cfg") }
        set { if let v = newValue { d.set(v, forKey: "cfg") } else { d.removeObject(forKey: "cfg") } }
    }
    static func cfgString(_ k: String) -> String? {
        (cfg?[k] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    static var lang: String {
        get { d.string(forKey: "lang") ?? "en" }
        set { d.set(newValue == "ar" ? "ar" : "en", forKey: "lang") }
    }
    static var token: String {
        get { d.string(forKey: "token") ?? "" }
        set { d.set(newValue, forKey: "token") }
    }
    static var quiet: [String: Any] {
        get { d.dictionary(forKey: "quiet") ?? [:] }
        set { d.set(newValue, forKey: "quiet") }
    }
    static var askedNotify: Bool {
        get { d.bool(forKey: "askedNotify") }
        set { d.set(newValue, forKey: "askedNotify") }
    }

    /// Calls answered, declined or ended here: their later rings stay quiet (as sw.js does).
    static func silence(_ tag: String) {
        var o = (d.dictionary(forKey: "silenced") as? [String: Double]) ?? [:]
        let now = Date().timeIntervalSince1970
        o[tag] = now
        o = o.filter { now - $0.value < 15 * 60 }
        d.set(o, forKey: "silenced")
    }
    static func isSilenced(_ tag: String) -> Bool {
        guard let t = (d.dictionary(forKey: "silenced") as? [String: Double])?[tag] else { return false }
        return Date().timeIntervalSince1970 - t < 15 * 60
    }

    /// Quiet hours: { on, from: "22:00", to: "07:00" }.
    static var quietNow: Bool {
        let q = quiet
        guard (q["on"] as? Bool) == true,
              let f = mins(q["from"] as? String), let t = mins(q["to"] as? String), f != t else { return false }
        let c = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let now = (c.hour ?? 0) * 60 + (c.minute ?? 0)
        return f < t ? (now >= f && now < t) : (now >= f || now < t)
    }
    private static func mins(_ s: String?) -> Int? {
        guard let p = s?.split(separator: ":"), p.count == 2, let h = Int(p[0]), let m = Int(p[1]) else { return nil }
        return h * 60 + m
    }
}

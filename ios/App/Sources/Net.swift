import Foundation

/// The few things the app says to the family server by itself.
enum Net {
    private static func post(_ url: String, _ body: [String: Any]) {
        guard url.hasPrefix("https://"), let u = URL(string: url),
              let data = try? JSONSerialization.data(withJSONObject: body) else { return }
        var r = URLRequest(url: u, timeoutInterval: 8)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = data
        URLSession.shared.dataTask(with: r).resume()
    }

    private static func asMe() -> [String: Any]? {
        guard let me = Store.cfgString("me") else { return nil }
        var b: [String: Any] = ["me": me]
        if let s = Store.cfgString("sec") { b["sec"] = s }
        if let k = Store.cfgString("dk") { b["dk"] = k }
        return b
    }

    /// Picked up here: the server stops the repeat rings, and this person's other phones go quiet.
    static func ringStop(tag: String) {
        guard let send = Store.cfgString("send"), var b = asMe() else { return }
        b["k"] = "ringstop"; b["tag"] = tag
        post(send, b)
    }

    /// A new push address: registered from here, as sw.js does.
    static func sendSub(token: String) {
        guard let send = Store.cfgString("send"), var b = asMe() else { return }
        b["k"] = "sub"; b["sub"] = ["fcm": token]
        post(send, b)
    }
}

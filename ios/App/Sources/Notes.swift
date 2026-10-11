import UserNotifications

/// Notification housekeeping - the iOS side of what Notes.kt does on Android.
/// The alert itself is shown by iOS from the push's own payload.
enum Notes {
    /// Takes down what is showing for a call (answered, declined or cancelled elsewhere).
    static func clear(tag: String) {
        let nc = UNUserNotificationCenter.current()
        nc.getDeliveredNotifications { list in
            let ids = list.filter { n in
                n.request.identifier == tag || (payload(n.request.content.userInfo)["tag"] as? String) == tag
            }.map { $0.request.identifier }
            nc.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    /// Messages from one person, read.
    static func clearChat(from: String) {
        let nc = UNUserNotificationCenter.current()
        nc.getDeliveredNotifications { list in
            let ids = list.filter { (payload($0.request.content.userInfo)["from"] as? String) == from }
                .map { $0.request.identifier }
            nc.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    /// The page's "send a test notification".
    static func local(title: String, body: String) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.sound = .default
        let r = UNNotificationRequest(identifier: "fmn-test", content: c,
                                      trigger: UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false))
        UNUserNotificationCenter.current().add(r)
    }

    /// The server's JSON (the same a browser's sw.js receives), carried in the push's field "p".
    static func payload(_ userInfo: [AnyHashable: Any]) -> [String: Any] {
        if let s = userInfo["p"] as? String, let d = s.data(using: .utf8),
           let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any] { return o }
        var o: [String: Any] = [:]
        for (k, v) in userInfo { if let k = k as? String { o[k] = v } }
        return o
    }
}

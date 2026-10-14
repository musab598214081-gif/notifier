import SwiftUI
import UIKit
import UserNotifications
import WebKit

/// Links and notification taps that arrive before the page exists wait here.
enum Router {
    static var pending: URL?
    static func open(_ u: URL?) {
        guard let u, Config.allowed(u) else { return }
        if let w = WebViewController.current { w.open(u) } else { pending = u }
    }
}

struct WebScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> WebViewController { WebViewController() }
    func updateUIViewController(_ vc: WebViewController, context: Context) {}

    /// Runs a line of JavaScript in the page, if the app is open.
    static func js(_ code: String) {
        DispatchQueue.main.async { WebViewController.current?.web?.evaluateJavaScript(code, completionHandler: nil) }
    }
}

/// The family's web app (APP_URL) in a WKWebView, with what a browser can't do:
/// the earpiece (AudioRoute), pushes (AppDelegate), files saved from a chat.
final class WebViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    static weak var current: WebViewController?

    private(set) var web: WKWebView!
    private let audio = AudioRoute()
    private var pageReady = false

    /* ---------------------------------------------------------------- start */

    override func viewDidLoad() {
        super.viewDidLoad()
        WebViewController.current = self
        view.backgroundColor = UIColor(named: "LaunchBg")

        let cfg = WKWebViewConfiguration()
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []     /* a call's sound starts by itself */
        cfg.applicationNameForUserAgent = "FamilyNotifierApp/\(Config.version)"
        let uc = cfg.userContentController
        uc.addUserScript(WKUserScript(source: shim(), injectionTime: .atDocumentStart, forMainFrameOnly: true))
        uc.add(WeakHandler(self), name: "app")
        uc.add(WeakHandler(self), name: "audio")

        web = WKWebView(frame: .zero, configuration: cfg)
        web.isOpaque = false
        web.backgroundColor = view.backgroundColor
        web.scrollView.backgroundColor = view.backgroundColor
        web.navigationDelegate = self
        web.uiDelegate = self
        web.allowsBackForwardNavigationGestures = true
        web.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(web)
        /* Clear of the notch and the home indicator; WKWebView follows the keyboard itself. */
        let g = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: g.topAnchor),
            web.bottomAnchor.constraint(equalTo: g.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: g.leadingAnchor),
            web.trailingAnchor.constraint(equalTo: g.trailingAnchor),
        ])

        NotificationCenter.default.addObserver(self, selector: #selector(becameActive),
                                               name: UIApplication.didBecomeActiveNotification, object: nil)
        web.load(URLRequest(url: Router.pending ?? Config.appURL))
        Router.pending = nil
        refreshNotifState()
    }

    deinit { audio.release() }

    @objc private func becameActive() { refreshNotifState(thenTellPage: true) }

    /// A link or a tapped notification, while the app is open or just started.
    func open(_ u: URL) {
        guard Config.allowed(u) else { return }
        if pageReady {
            web.evaluateJavaScript("window.familyOpen && familyOpen(\(jsQuote(u.absoluteString)))", completionHandler: nil)
        } else {
            web.load(URLRequest(url: u))
        }
    }

    /* ---------------------------------------------------------------- the page */

    /// window.FamilyApp / window.FamilyAudio, as the Android app's bridges look to the page.
    /// WKWebView can't answer a script synchronously, so the answers the page reads
    /// (token, notification state, route) are kept in the page and refreshed from native code.
    private func shim() -> String {
        """
        (function(){
          var S = window.__fam = { token: \(jsQuote(Store.token)), notif: \(jsQuote(Store.askedNotify ? "denied" : "default")) };
          function post(n, m){ try { window.webkit.messageHandlers[n].postMessage(m); } catch(e){} }
          window.FamilyApp = {
            helper: function(j){ post('app', {m:'helper', a:j}); },
            pushToken: function(){ return S.token; },
            notifState: function(){ return S.notif; },
            askNotify: function(){ post('app', {m:'askNotify'}); },
            saveFile: function(n, t, b){ post('app', {m:'saveFile', n:n, t:t, b:b}); return 'ok'; }
          };
          window.FamilyUpdate = {
            check: function(){ post('app', {m:'updCheck'}); },
            install: function(){ post('app', {m:'updOpen'}); }
          };
          window.FamilyAudio = {
            setRoute: function(r){ post('audio', {r:r}); return r === 'speaker' ? 'speaker' : r === 'earpiece' ? 'earpiece' : ''; }
          };
        })();
        """
    }

    private func refreshNotifState(thenTellPage: Bool = false) {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let state: String
            switch s.authorizationStatus {
            case .authorized, .provisional, .ephemeral: state = "granted"
            case .denied: state = "denied"
            default: state = "default"
            }
            DispatchQueue.main.async {
                self.web?.evaluateJavaScript("window.__fam && (window.__fam.notif = \(jsQuote(state)))", completionHandler: nil)
                if state == "granted" { UIApplication.shared.registerForRemoteNotifications() }
                if thenTellPage {
                    self.web?.evaluateJavaScript("window.familyPushToken && familyPushToken()", completionHandler: nil)
                }
            }
        }
    }

    func tokenChanged(_ t: String) {
        web?.evaluateJavaScript("window.__fam && (window.__fam.token = \(jsQuote(t))); window.familyPushToken && familyPushToken()",
                                completionHandler: nil)
    }

    /* ---------------------------------------------------------------- bridge */

    func userContentController(_ c: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.frameInfo.isMainFrame, Config.allowed(message.frameInfo.request.url),
              let m = message.body as? [String: Any] else { return }
        if message.name == "audio" { audio.set(m["r"] as? String ?? "off"); return }
        switch m["m"] as? String {
        case "helper": helper(m["a"] as? String ?? "")
        case "askNotify": askNotify()
        case "saveFile": saveFile(name: m["n"] as? String ?? "", b64: m["b"] as? String ?? "")
        case "updCheck": updateCheck()
        case "updOpen": if let u = storeURL { UIApplication.shared.open(u) }
        default: break
        }
    }

    /// What the page also posts to sw.js.
    private func helper(_ json: String) {
        guard let d = json.data(using: .utf8),
              var o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              let t = o.removeValue(forKey: "t") as? String else { return }
        switch t {
        case "cfg": Store.cfg = o
        case "lang": Store.lang = o["v"] as? String ?? "en"
        case "quiet": Store.quiet = o
        case "silence":
            if let tag = o["tag"] as? String { Store.silence(tag); Notes.clear(tag: tag) }
        case "forget": Store.cfg = nil
        case "readchat":
            if let from = o["from"] as? String { Notes.clearChat(from: from) }
        case "test":
            Notes.local(title: o["title"] as? String ?? "Family Notifier", body: o["body"] as? String ?? "")
        default: break   /* photos, audible: only used by Android's own notification code */
        }
    }

    /* ---------------------------------------------------------------- updates */

    /// The app's own page in the App Store, once a newer version is there.
    private var storeURL: URL?

    /// iPhones can't update an app from inside it: a newer version on the
    /// App Store is offered on the page's update bar, and its button opens
    /// the app's own App Store page - its Update button, no searching.
    /// (With Automatic Updates on, the default, it arrives by itself anyway.)
    private func updateCheck() {
        guard let id = Bundle.main.bundleIdentifier,
              let q = URL(string: "https://itunes.apple.com/lookup?bundleId=\(id)&t=\(Int(Date().timeIntervalSince1970))") else { return }
        URLSession.shared.dataTask(with: q) { data, _, _ in
            guard let data, let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let r = (o["results"] as? [[String: Any]])?.first,
                  let v = r["version"] as? String, let page = r["trackViewUrl"] as? String,
                  newer(v, than: Config.version) else {
                self.tellUpdate(["state": "none"]); return
            }
            DispatchQueue.main.async {
                self.storeURL = URL(string: page.replacingOccurrences(of: "https://", with: "itms-apps://"))
                self.tellUpdate(["state": "available", "name": v, "store": true])
            }
        }.resume()
    }

    private func tellUpdate(_ d: [String: Any]) {
        guard let j = try? JSONSerialization.data(withJSONObject: d),
              let s = String(data: j, encoding: .utf8) else { return }
        WebScreen.js("window.familyUpdate && familyUpdate(\(jsQuote(s)))")
    }

    /// The page's "turn on notifications" button.
    private func askNotify() {
        let nc = UNUserNotificationCenter.current()
        nc.getNotificationSettings { s in
            DispatchQueue.main.async {
                switch s.authorizationStatus {
                case .authorized, .provisional, .ephemeral:
                    UIApplication.shared.registerForRemoteNotifications()
                    self.web.evaluateJavaScript("window.familyNotifyResult && familyNotifyResult(true)", completionHandler: nil)
                case .notDetermined:
                    Store.askedNotify = true
                    nc.requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in
                        DispatchQueue.main.async {
                            if ok { UIApplication.shared.registerForRemoteNotifications() }
                            self.refreshNotifState()
                            self.web.evaluateJavaScript("window.familyNotifyResult && familyNotifyResult(\(ok))", completionHandler: nil)
                        }
                    }
                default:
                    /* Refused before: only the phone's settings can turn them on now. */
                    if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
                }
            }
        }
    }

    /// A picture or file from a chat: the share sheet (Save to Photos / Files), since blob: links can't download.
    private func saveFile(name: String, b64: String) {
        guard let data = Data(base64Encoded: b64, options: .ignoreUnknownCharacters) else { return }
        let bad = CharacterSet(charactersIn: "\\/:*?\"<>|")
        var safe = name.components(separatedBy: bad).joined(separator: "_")
        if safe.trimmingCharacters(in: .whitespaces).isEmpty { safe = "file" }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(safe)
        guard (try? data.write(to: url, options: .atomic)) != nil else { return }
        let vc = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        vc.popoverPresentationController?.sourceView = view
        vc.completionWithItemsHandler = { _, _, _, _ in try? FileManager.default.removeItem(at: url) }
        present(vc, animated: true)
    }

    /* ---------------------------------------------------------------- navigation */

    func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        let main = a.targetFrame?.isMainFrame ?? true
        guard main, a.request.url?.scheme != "about" else { decisionHandler(.allow); return }
        if Config.allowed(a.request.url) { decisionHandler(.allow); return }
        /* Maps, web links and the like: in the phone's own apps. */
        if let u = a.request.url, ["https", "http", "tel", "mailto", "sms", "facetime", "maps"].contains(u.scheme ?? "") {
            UIApplication.shared.open(u)
        }
        decisionHandler(.cancel)
    }

    func webView(_ w: WKWebView, didStartProvisionalNavigation n: WKNavigation!) { pageReady = false }
    func webView(_ w: WKWebView, didFinish n: WKNavigation!) { pageReady = true }
    func webViewWebContentProcessDidTerminate(_ w: WKWebView) { pageReady = false; w.reload() }

    /* target=_blank and window.open */
    func webView(_ w: WKWebView, createWebViewWith c: WKWebViewConfiguration,
                 for a: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let u = a.request.url {
            if Config.allowed(u) { w.load(URLRequest(url: u)) } else { UIApplication.shared.open(u) }
        }
        return nil
    }

    /* The microphone and camera, for the family's own address only. */
    func webView(_ w: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame f: WKFrameInfo, type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(origin.host == Config.host ? .grant : .deny)
    }

    /* JavaScript alert / confirm, which WKWebView would otherwise swallow. */
    func webView(_ w: WKWebView, runJavaScriptAlertPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo,
                 completionHandler: @escaping () -> Void) {
        let a = UIAlertController(title: nil, message: m, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler() })
        present(a, animated: true)
    }

    func webView(_ w: WKWebView, runJavaScriptConfirmPanelWithMessage m: String, initiatedByFrame f: WKFrameInfo,
                 completionHandler: @escaping (Bool) -> Void) {
        let a = UIAlertController(title: nil, message: m, preferredStyle: .alert)
        a.addAction(UIAlertAction(title: "Cancel", style: .cancel) { _ in completionHandler(false) })
        a.addAction(UIAlertAction(title: "OK", style: .default) { _ in completionHandler(true) })
        present(a, animated: true)
    }
}

/// WKUserContentController keeps its handlers alive; this stops that from holding the controller.
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ t: WKScriptMessageHandler) { target = t }
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        target?.userContentController(c, didReceive: m)
    }
}

/// "1.0.12" after "1.0.9": compared number by number.
private func newer(_ a: String, than b: String) -> Bool {
    let x = a.split(separator: ".").map { Int($0) ?? 0 }, y = b.split(separator: ".").map { Int($0) ?? 0 }
    for i in 0..<max(x.count, y.count) {
        let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
        if p != q { return p > q }
    }
    return false
}

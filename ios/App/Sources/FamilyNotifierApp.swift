import FirebaseCore
import FirebaseMessaging
import SwiftUI
import UserNotifications

@main
struct FamilyNotifierApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            WebScreen()
                .background(Color(red: 0.059, green: 0.106, blue: 0.165).ignoresSafeArea())
                .onOpenURL { Router.open($0) }                       /* invitation / universal links */
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { Router.open($0.webpageURL) }
        }
        .onChange(of: phase) { Store.foreground = ($0 == .active) }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate, MessagingDelegate {
    func application(_ app: UIApplication,
                     didFinishLaunchingWithOptions o: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        /* Without GoogleService-Info.plist the app still runs, just without push. */
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
            Messaging.messaging().delegate = self
        }
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    func application(_ a: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken t: Data) {
        if FirebaseApp.app() != nil { Messaging.messaging().apnsToken = t }
    }

    /// FCM's address for this phone (the same "fcm" subscription the Android app registers).
    func messaging(_ m: Messaging, didReceiveRegistrationToken t: String?) {
        guard let t, !t.isEmpty else { return }
        Store.token = t
        Net.sendSub(token: t)
        DispatchQueue.main.async { WebViewController.current?.tokenChanged(t) }
    }

    /// A silent push (content-available): a call cancelled or answered elsewhere.
    func application(_ a: UIApplication, didReceiveRemoteNotification u: [AnyHashable: Any],
                     fetchCompletionHandler done: @escaping (UIBackgroundFetchResult) -> Void) {
        let d = Notes.payload(u)
        if (d["kind"] as? String) == "cancel", let tag = d["tag"] as? String {
            Store.silence(tag)
            Notes.clear(tag: tag)
        }
        WebScreen.js("window.familyPush && familyPush()")
        done(.newData)
    }

    /// With the app on screen the page shows things itself: nothing on top (except the test).
    func userNotificationCenter(_ c: UNUserNotificationCenter, willPresent n: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        let d = Notes.payload(n.request.content.userInfo)
        WebScreen.js("window.familyPush && familyPush()")
        if (d["kind"] as? String) == "test" || n.request.identifier == "fmn-test" { done([.banner, .sound]); return }
        done([])
    }

    /// A notification tapped: open what it is about, and stop a call's ringing everywhere.
    func userNotificationCenter(_ c: UNUserNotificationCenter, didReceive r: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let d = Notes.payload(r.notification.request.content.userInfo)
        let kind = d["kind"] as? String
        if let tag = d["tag"] as? String, kind == "call" || kind == "group" {
            Store.silence(tag)
            Net.ringStop(tag: tag)
        }
        let s = d["url"] as? String
        Router.open(s.flatMap(URL.init(string:)) ?? Config.appURL)
        done()
    }
}

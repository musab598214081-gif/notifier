# Family Notifier — iOS / App Store

The iOS app is a native Swift shell (WKWebView around `APP_URL`) that offers the
page the same bridges as the Android app: `window.FamilyApp` and `window.FamilyAudio`
(earpiece / speaker routing, push token, notification permission, saving files).

    ios/
      project.yml              XcodeGen spec -> FamilyNotifier.xcodeproj (not committed)
      ExportOptions.plist      App Store export
      App/Info.plist           permissions text, background modes
      App/FamilyNotifier.entitlements   push, universal links, time-sensitive alerts
      App/PrivacyInfo.xcprivacy         Apple's privacy manifest
      App/Assets.xcassets      1024px app icon (no alpha), launch colour
      App/Sources/*.swift      the app

Built on a macOS runner by `.github/workflows/ios-build.yml` (no Mac needed).

## 1. Apple Developer account (one time, ~US$99/yr)

1. Enrol at developer.apple.com/programs. Note your **Team ID** (Membership page).
2. Certificates, Identifiers & Profiles → Identifiers → **+** → App ID, bundle id =
   your `APP_ID` (e.g. `app.family.notifyer`). Enable **Push Notifications**,
   **Associated Domains** and **Time Sensitive Notifications**.
3. App Store Connect → Apps → **+** → New App (iOS, same bundle id, SKU anything).
4. App Store Connect → Users and Access → Integrations → **App Store Connect API**
   → generate a key with role **App Manager** (or Admin). Download the `.p8` once;
   note the **Key ID** and **Issuer ID**.

## 2. Repository settings (Settings → Secrets and variables → Actions)

| Kind     | Name                      | Value |
|----------|---------------------------|-------|
| variable | `APP_URL`                 | same as Android (`https://…/`) |
| variable | `APP_ID`                  | bundle id from step 1.2 |
| variable | `APPLE_TEAM_ID`           | 10-character Team ID |
| secret   | `APPSTORE_API_KEY_P8`     | full text of the `.p8` file |
| secret   | `APPSTORE_API_KEY_ID`     | Key ID |
| secret   | `APPSTORE_API_ISSUER_ID`  | Issuer ID |
| secret   | `GOOGLE_SERVICE_INFO_PLIST` | text of `GoogleService-Info.plist` (below) |

Signing is automatic: the API key lets Xcode create the distribution certificate and
provisioning profile in the cloud, so no certificates are stored in the repo.

## 3. Push notifications

1. Apple Developer → Keys → **+** → enable **Apple Push Notifications service (APNs)**;
   download the `.p8` (this is a different key from step 1.4).
2. Firebase console → Project settings → **Add app → iOS**, bundle id = `APP_ID`.
   Download `GoogleService-Info.plist` → save its text as the secret above.
3. Firebase → Project settings → Cloud Messaging → *Apple app configuration* →
   upload the APNs key (Key ID + Team ID).

**Server change (not in this repo):** the family server sends Android pushes as
FCM *data* messages (field `p`). iOS shows nothing for data-only messages while the
app is closed, so for iOS subscriptions the server must also send:

    "notification": { "title": <title>, "body": <body> },
    "data": { "p": <same JSON as for Android> },
    "apns": { "headers": { "apns-priority": "10" },
              "payload": { "aps": { "sound": "default", "interruption-level": "time-sensitive",
                                    "thread-id": <tag> } } }

and for `kind: "cancel"` a silent push (`"apns": {"headers": {"apns-push-type":
"background", "apns-priority": "5"}, "payload": {"aps": {"content-available": 1}}}`).
The app reads `p` (or the top-level keys) for `url`, `tag`, `kind` and `from`.

## 4. Universal links (invitation links open the app)

Serve this at `https://<APP_HOST>/.well-known/apple-app-site-association`
(no extension, `Content-Type: application/json`, no redirect):

    { "applinks": { "details": [ { "appIDs": ["<TEAM_ID>.<APP_ID>"], "components": [ { "/": "/*" } ] } ] } }

## 5. Ship it

1. Actions → **Build the iOS app** → Run workflow. It archives, exports the `.ipa`
   (kept as a build artifact for 30 days) and uploads it to App Store Connect.
2. After Apple finishes processing (10–30 min) the build appears under TestFlight.
   Test on a real iPhone (push and earpiece routing do not work in a simulator).
3. App Store Connect → the app → fill in the **App Store listing**, below, choose the
   build, **Submit for Review**.

Version = `1.0`, build number = `100 + run number` (always increasing, as Apple requires).
Bump `MARKETING_VERSION` in `project.yml` for a new store version.

## App Store listing checklist

- **Screenshots**: 6.9" iPhone (1320×2868) — required; 6.5" optional. iPad not needed (iPhone-only build).
- **Privacy Policy URL** — required. It must say what is kept (name, push address, messages on the family server).
- **Support URL**, description, keywords, category (Social Networking or Lifestyle), age rating questionnaire.
- **App Privacy** (nutrition label): *Device ID* (push token) and *User Content / Contact Info* as stored by
  your server, linked to the user, used for App Functionality, **not** for tracking. Declare exactly what
  the server keeps. `PrivacyInfo.xcprivacy` in the app already declares no tracking.
- **Export compliance**: already answered (`ITSAppUsesNonExemptEncryption = false`; HTTPS only).
- **App Review notes**: give a demo family/invite code or test login — reviewers cannot test an
  invite-only app otherwise, and this is the most common reason for rejection.

## Review risks to know about

- **Guideline 4.2 (minimum functionality)**: Apple rejects apps that are only a website in a frame. This one
  has native push, earpiece/speaker/headset call routing, proximity sensor, file saving and universal links;
  mention them in the review notes. Further native features strengthen the case.
- **Guideline 5.1.1(v)**: if people can create an account (or join a family) inside the app, the app must also
  let them **delete the account/data inside the app**. Make sure the web page has this.
- **Calls when the app is closed**: iOS rings like a phone call only through **CallKit + PushKit (VoIP push)**,
  which this version does not use; incoming calls arrive as time-sensitive notifications. Adding CallKit is a
  larger follow-up (server must send VoIP pushes) and Apple requires such pushes to report a call immediately.
- User-generated content (chat, photos): Apple expects a way to **report/block** users (Guideline 1.2).

## Not verified

This was written on Windows with no Mac available, so it has **not been compiled**. The first workflow run is
the first build; fix any compiler message it shows (most likely small Swift/XcodeGen version issues).

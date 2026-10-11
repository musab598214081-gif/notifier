# Family Notifier — Android app

The family's web app, wrapped as a real Android app: calls in the earpiece by
default (Speaker only when tapped), full-screen ringing when the app is closed,
and calls that keep going in the background.

Built automatically by GitHub Actions and delivered through Google Play's
internal testing track. **Start with [SETUP.md](SETUP.md).**

    android/                      the app (Kotlin, a WebView around APP_URL)
      app/src/main/java/.../
        MainActivity.kt           the page, permissions, links, files
        FamilyAudioBridge.kt      earpiece / speaker / headset routing
        FamilyAppBridge.kt        what the page tells the app (as it tells sw.js)
        PushService.kt, Notes.kt  Firebase pushes -> ringing calls, messages
        CallService.kt            keeps a call alive in the background
    .github/workflows/
      android-build.yml           builds, and uploads to Play
      make-upload-key.yml         run once: makes the signing key
      ios-build.yml               builds the iOS app and uploads it to TestFlight
    ios/                          the iPhone app (Swift, WKWebView) - see ios/APPSTORE.md

## Build locally with Docker (any PC)

Only Docker is needed — no JDK, Gradle or Android SDK.

    cp .env.example .env          # set APP_URL / APP_ID (optional)
    docker compose build          # once per PC (downloads the Android SDK)
    docker compose run --rm build # APKs land in ./dist

- Put your real `google-services.json` in `android/app/` for working push
  notifications; without it a placeholder is used and the app still builds.
- Release/Play bundle: set `GRADLE_TASKS="bundlePlayRelease assembleDirectRelease"`
  in `.env`. Local release builds are unsigned; official signed builds come from
  GitHub Actions.
- Toolchain versions (JDK 17, Gradle 8.14.3, SDK 36) match CI.

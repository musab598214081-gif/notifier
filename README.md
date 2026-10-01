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

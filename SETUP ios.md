# Family Notifier for iPhone: setup

This turns the Family Notifier web app into a real iPhone app that your
family installs through **TestFlight** like any other app. The app shows
the same page you already host and adds what Safari can't do:

- **Earpiece by default.** Calls play in the earpiece and switch to the
  loudspeaker only when you tap Speaker. The screen turns off when the phone
  is held to your ear.
- **Real ringing.** An incoming call fills the screen and rings with the
  phone's ringtone, even when the app is closed or the phone is locked —
  exactly like a phone call, through CallKit.
- **Calls keep going.** A call continues while you use another app.

Once it's set up, GitHub builds the app for you and sends every new version to
your family's phones through TestFlight. You never need a Mac or Xcode.

The page itself is still loaded from your website, so when you change
`index.html`, everyone gets the change the next time the app opens. A new
app version is only needed when something under `ios/` changes.

You do steps 1 to 6 once. They take an afternoon, mostly waiting for Apple.

---

## What you need

- The address where your `index.html` is hosted, for example
  `https://yourname.github.io/family/`. This is called **APP_URL** below.
- An **Apple Developer Program** membership (Apple charges a yearly fee).
  Sign up at <https://developer.apple.com/programs/>; it can take a day or
  two to be approved.
- A free **GitHub** account.
- A name for the app's ID that is unique to you, for example
  `com.yourname.familynotifier`. This is called **APP_ID** below. It can never
  be changed after the first upload to App Store Connect.

The web app, `sw.js` and the privacy page stay as they are; only the family
server changes.

---

## Step 1: The family server

1. Replace your Worker's code with `server/family-server.js` from this
   folder and deploy it as usual. It is the same server with Apple's push
   service added; the browsers' and the Android app's notifications are
   untouched. You'll add four secrets to it in step 2.

## Step 2: Apple (this is what makes calls ring)

Everything here is at <https://developer.apple.com/account>.

1. **Identifiers → App IDs → +** → *App*. Description "Family Notifier",
   Bundle ID *Explicit*: your **APP_ID**. Under Capabilities tick
   **Associated Domains**, **App Groups**, **Push Notifications** and
   **Time Sensitive Notifications**. Register.
2. **Identifiers → App IDs → +** again → *App*, Bundle ID: your APP_ID
   followed by `.nse` (the notification extension). Tick **App Groups**.
   Register.
3. **Identifiers → App Groups → +**: `group.` followed by your APP_ID.
   Then open each of the two App IDs, edit App Groups and assign this group.
4. **Keys → +**: name "Family Notifier push", tick **Apple Push
   Notifications service (APNs)**, Continue, Register, **Download**. This is
   the only time it can be downloaded. Note the **Key ID** shown (10
   characters; it is also in the file name `AuthKey_XXXXXXXXXX.p8`).
5. Your **Team ID** is at the top right of the Membership page (10 characters).
6. Give these to your family server as secrets, with the Cloudflare dashboard
   (Workers → your worker → Settings → Variables and Secrets → Add →
   *Secret*) or the command line:
   - `APNS_KEY`: the whole contents of the `.p8` file
     (`npx wrangler secret put APNS_KEY < AuthKey_XXXXXXXXXX.p8`)
   - `APNS_KEY_ID`: the Key ID
   - `APNS_TEAM_ID`: your Team ID
   - `APNS_BUNDLE_ID`: your APP_ID
7. To check it worked, open your family server's address
   (`https://….workers.dev/`). The list of features should now include
   `"apns"`.

## Step 3: App Store Connect and GitHub (this builds the app)

1. At <https://appstoreconnect.apple.com> → **Apps → +** → *New App*:
   platform iOS, name "Family Notifier", your language, Bundle ID: pick your
   APP_ID, SKU anything (for example `familynotifier`).
2. **Users and Access → Integrations → App Store Connect API → Team Keys →
   +**: name "GitHub builds", access **Admin** (needed so the build can
   create its signing certificate and profiles by itself). Download the
   `.p8` file — again the only time it can be downloaded — and note the
   **Key ID** and the **Issuer ID** shown above the list.
3. Create a **private** repository on GitHub and upload everything in this
   folder: the `ios` folder, the `.github` folder and the two `.md` files.
   (It can be the same repository as the Android app; the workflows don't
   get in each other's way.)
4. In the repository, open **Settings → Secrets and variables → Actions**.
   On the **Variables** tab add:
   - `APP_URL`: your app's address, for example `https://yourname.github.io/family/`
   - `APP_ID`: for example `com.yourname.familynotifier`
   - `APPLE_TEAM_ID`: your Team ID
   - `FAMILY_SERVER`: your Worker's address, e.g. `https://….workers.dev`
     (only if it is not the one already written in the workflow)
   - `TESTFLIGHT_URL`: leave for step 5
5. On the **Secrets** tab add:
   - `APPSTORE_API_KEY_P8`: the whole contents of the App Store Connect `.p8`
   - `APPSTORE_API_KEY_ID`: its Key ID
   - `APPSTORE_API_ISSUER_ID`: the Issuer ID

## Step 4: The first build

1. Open the **Actions** tab, choose **Build the iPhone app**, and click
   **Run workflow**. It takes about 10 minutes (Apple's build machines are
   slower than Android's).
2. When it turns green, the build is already in App Store Connect →
   **TestFlight**. The first time, Apple runs a short automatic check
   (usually minutes, occasionally a day) before it can be given to testers.

If the run turns red, open it, click the failed step, and copy the last
lines of the log. They say what went wrong. The most common first-time
problem is signing:

- *"No signing certificate… / cloud signing…"*: Apple refused to create a
  cloud-managed certificate for the API key. Then make one yourself, still
  without a Mac: run **Make a signing certificate request**, download its
  files, upload `request.certSigningRequest` at developer.apple.com →
  **Certificates → + → Apple Distribution**, download the `.cer`, add the
  two text files from the run as the secrets `IOS_SIGNING_KEY_BASE64` and
  `IOS_SIGNING_KEY_PASSWORD`, then run **Finish the signing certificate**
  with the `.cer` (base64) pasted in. Add its two outputs as the secrets
  `IOS_CERT_P12_BASE64` and `IOS_CERT_PASSWORD`, and build again.

## Step 5: TestFlight (this delivers the app to your family)

1. In App Store Connect → your app → **TestFlight**, the build appears
   under iOS builds. Fill in the short *Test Information* form (what to test,
   your email) the first time.
2. **Internal Testing → +**: a group "Family". Tick *Enable automatic
   distribution* so every new build goes out by itself. Add your family
   members' Apple IDs as testers (up to 100). Each gets an email from Apple.
   - Internal testers need to be in your App Store Connect team (Users and
     Access → + ; the *Customer Support* role is enough). If you would
     rather not add them there, make an **External Testing** group with a
     **public link** instead: it needs Apple's one-time review of the first
     build (usually a day), and after that works the same.
3. Copy the group's **public link** (External) or tell your family to accept
   the email (Internal), and put the link into the GitHub variable
   `TESTFLIGHT_URL` — that is where the app's "Update" button sends people.
4. **App Information → Privacy Policy URL**: your APP_URL followed by
   `privacy.html` (the same page as for Android; it already covers the
   microphone, camera and location).

TestFlight builds expire after 90 days; a new build (any change under
`ios/`, or **Run workflow**) resets that.

## Step 6: Make the family's links open the app

So an invitation or a notification's link opens in the app rather than in
Safari, your website must name the app:

1. Open `site/.well-known/apple-app-site-association`, replace `TEAMID`
   with your Team ID and `APP_ID` with your APP_ID (so it reads
   `"ABCDE12345.com.yourname.familynotifier"`), and upload it to your
   website next to the existing `.well-known/assetlinks.json`. It must be
   served over https **without** a file extension, as `application/json`
   (GitHub Pages does this). Keep the `.nojekyll` file there.
2. Apple fetches it when the app is installed. On a phone that already has
   the app, reinstalling (or waiting a day) makes it take effect.

---

## For each family member

1. On their iPhone, install **TestFlight** from the App Store, then open the
   invitation email or the public link and tap **Install**.
2. Open the app. If it shows the setup screen, open their personal invite
   link, or tap **Paste my link**.
3. Allow the microphone and notifications when asked.
4. If they used to have Family Notifier on the Home Screen as a Safari web
   app on the same phone, remove it. Otherwise the phone rings twice.

iOS 15 or newer is needed. Android phones keep using the Android app.

## Good to know

- **To check notifications:** in the app, open Settings → Sounds →
  **Show a test notification**.
- **Calls and the silent switch:** a call rings through CallKit, so the
  silent switch, Do Not Disturb and Focus apply to it exactly as to a phone
  call. The app's own Sounds setting has no effect on an iPhone.
- **A missed call** is a normal notification with *Call back*; calls never
  appear in the Phone app's Recents.
- **Updates:** TestFlight installs new builds by itself when *automatic
  updates* is on (it is, by default). The page's "Update" bar is a reminder
  for phones where it is off.
- **Running from Xcode** (optional, on a Mac): `brew install xcodegen`, edit
  `ios/Config.xcconfig`, run `xcodegen generate` in `ios/` and open
  `FamilyNotifier.xcodeproj`. A debug build uses Apple's sandbox push
  service; the server picks that up from the token by itself.
- **Nothing here costs money to run** beyond the Apple Developer membership:
  Apple's push service and TestFlight are free, and GitHub's macOS build
  minutes are free for a private repository of this size at a few builds a
  month (they cost 10× Linux minutes; the free allowance covers about 200
  macOS minutes, i.e. 15–20 builds).

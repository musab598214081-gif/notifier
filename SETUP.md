# Family Notifier for Android: setup

This turns the Family Notifier web app into a real Android app that your
family installs from the **Google Play Store** like any other app. There is no
APK file to install and no "unknown sources" warning.

The app shows the same page you already host, and adds what a browser can't do:

- **Earpiece by default.** Calls play in the earpiece and switch to the
  loudspeaker only when you tap Speaker. The screen turns off when the phone
  is held to your ear.
- **Real ringing.** An incoming call fills the screen and rings with the
  phone's ringtone, even when the app is closed or the phone is locked.
- **Calls keep going.** A call continues while you use another app.

Once it's set up, GitHub builds the app for you and sends every new version to
your family's phones through the Play Store. You never need Android Studio.

The page itself is still loaded from your website. So when you change
`index.html`, everyone gets the change the next time the app opens, without a
new app version. A new app version is only needed when something under
`android/` changes.

You do steps 1 to 6 once. They take an afternoon, mostly waiting for Google.

---

## What you need

- The address where your `index.html` is hosted, for example
  `https://yourname.github.io/family/`. This is called **APP_URL** below.
- A Google account for a **Google Play developer account**. Google charges a
  one-time registration fee; Play Console shows the current amount.
- A free **GitHub** account.
- A name for the app's ID that is unique to you, for example
  `com.yourname.familynotifier`. This is called **APP_ID** below. It can never
  be changed after the first upload to Play.

---

## Step 1: Update the website and the family server

1. Replace `index.html` and `sw.js` on your website with the new versions.
2. Replace your Worker's code with the new `family-server.js` and deploy it as
   usual. You'll add one secret to it in step 2.

The web app keeps working exactly as before in browsers and on iPhones.

## Step 2: Firebase (this is what makes calls ring)

Firebase is Google's free service that wakes the app when a call comes in.

1. Go to <https://console.firebase.google.com>, click **Create a project**,
   and give it a name such as "Family Notifier". You can switch Analytics off.
2. In the project, click **Add app → Android**. For the package name, enter
   your **APP_ID** exactly. Click **Register app**.
3. Download **google-services.json**. Keep it; you'll paste it into GitHub in
   step 3. You can skip the remaining Firebase "add SDK" screens.
4. Open **Project settings (gear icon) → Service accounts → Generate new
   private key**. This downloads a second JSON file, the *server key*.
5. Give the server key to your family server as the secret
   **FCM_SERVICE_ACCOUNT**, using either of these:
   - **Cloudflare dashboard:** Workers → your worker → Settings → Variables and
     Secrets → Add → Type: *Secret*, Name: `FCM_SERVICE_ACCOUNT`, Value: paste
     the whole file → Deploy.
   - **Command line:** `npx wrangler secret put FCM_SERVICE_ACCOUNT < the-downloaded-file.json`
6. To check it worked, open your family server's address
   (`https://….workers.dev/`). The list of features should now include `"fcm"`.

## Step 3: GitHub (this builds the app)

1. Create a **private** repository on GitHub and upload everything in this
   folder: the `android` folder, the `.github` folder and this file.
2. In the repository, open **Settings → Secrets and variables → Actions**.
3. On the **Variables** tab, add these two:
   - `APP_URL`: your app's address, for example `https://yourname.github.io/family/`
   - `APP_ID`: for example `com.yourname.familynotifier`
4. Make the signing key, once:
   - Open the **Actions** tab, choose **Make the upload key (once)**, and click
     **Run workflow**.
   - When it finishes, open the run and download **upload-key-KEEP-PRIVATE**.
   - Keep `upload.jks` from it somewhere safe, such as a password manager.
5. On the **Secrets** tab, add these secrets. The first four come from the
   files you just downloaded:
   - `UPLOAD_KEYSTORE_BASE64`: contents of `UPLOAD_KEYSTORE_BASE64.txt`
   - `UPLOAD_KEYSTORE_PASSWORD`: contents of `UPLOAD_KEYSTORE_PASSWORD.txt`
   - `UPLOAD_KEY_PASSWORD`: contents of `UPLOAD_KEY_PASSWORD.txt`
   - `UPLOAD_KEY_ALIAS`: `upload`
   - `GOOGLE_SERVICES_JSON`: the whole contents of `google-services.json` from step 2
6. Delete the key run from the Actions tab. Its download disappears by itself
   after a day anyway.

## Step 4: The first build

1. Open the **Actions** tab, choose **Build the Android app**, and click
   **Run workflow**. It takes about 5 minutes.
2. When it turns green, open the run and download **family-notifier-…**. It
   contains `app-release.aab`, which is for Play, and `app-release.apk`.
   - Optional: install the `.apk` on your own phone first to try the app
     before anyone else does.

If the run turns red, open it, click the failed step, and copy the last
lines of the log. They say what went wrong.

## Step 5: Google Play (this delivers the app to your family)

1. Sign up at <https://play.google.com/console>. This is where the one-time
   fee is paid.
2. Click **Create app** and give it a name, the default language, *App*, and
   *Free*.
3. Go to **Testing → Internal testing → Testers**:
   - Create an email list with your family members' Google account addresses.
     Up to 100 people can be added.
4. Go to **Internal testing → Create new release**, upload `app-release.aab`,
   then click **Next → Save → Roll out**.
   - Let Google manage the signing key when it asks. That's "Play App Signing",
     and it means a lost upload key can be replaced later.
5. Play Console will ask you to fill in a few forms under **App content**
   before the release goes out. Answer them honestly. For this app they are
   likely to be:
   - **Privacy policy:** upload `privacy.html` next to your `index.html`, after
     replacing *[your email address]* in it (it appears twice: once in English,
     once in Arabic). Then give Play its address, which is your APP_URL followed
     by `privacy.html`. Play requires a policy because the app uses the
     microphone, camera and location.
   - **Full-screen intent:** choose *Calling*.
   - **Foreground service:** choose *Microphone*. Explain that it keeps a call
     going while the app is in the background. Google may ask for a short
     screen recording of a call.
   - **Data safety**, **Target audience** and **Content rating:** short
     questionnaires.
6. On the **Testers** tab, copy the **opt-in link**. That's what you send your
   family.

Internal testing has no review queue and no "12 testers for 14 days" rule;
that rule only applies to publishing publicly, which you never need to do.

## Step 6: From now on, automatic

So new versions go to your family's phones without you uploading anything:

1. In the Google Cloud console (<https://console.cloud.google.com>, same
   project as Firebase), open **APIs & Services → Library**, search for
   **Google Play Android Developer API**, and click **Enable**.
2. Open **IAM & Admin → Service accounts → Create service account**, name it
   "play-upload", then open it and choose **Keys → Add key → JSON**. This
   downloads a key file.
3. In Play Console, open **Users and permissions → Invite new users**:
   - Enter the service account's email address (it ends in
     `iam.gserviceaccount.com`).
   - Under **App permissions**, add your app and tick **Release to testing tracks**.
4. In GitHub, add the secret `PLAY_SERVICE_ACCOUNT_JSON` with the whole key
   file as its value.

From then on, every change under `android/` builds by itself and arrives on
your family's phones as a normal Play Store update.

---

## For each family member

1. On their Android phone, open the opt-in link, tap **Become a tester**, then
   **Download it on Google Play**, and install the app.
2. Open the app. If it shows the setup screen, open their personal invite link
   in it, or tap **Paste my link**.
3. Allow the microphone and notifications when asked.
4. On Android 14 and newer, also allow **full-screen notifications** when the
   app asks, so calls fill the screen.
5. If they used to have Family Notifier as a Chrome or home-screen shortcut on
   the same phone, remove it, or turn off that site's notifications in Chrome.
   Otherwise the phone rings twice.

iPhones keep using the web app as before. Apple doesn't allow this kind of
earpiece control or ringing from a web page, and an iPhone app would be a
separate project.

## Good to know

- **To check notifications:** in the app, open Settings → Sounds →
  **Show a test notification**.
- **Battery savers:** some phones (Xiaomi, Huawei, Oppo, Samsung's "deep
  sleep") stop apps from waking. If calls don't ring while the app is closed,
  set Family Notifier's battery use to **Unrestricted** in the phone's
  settings.
- **Nothing here costs money to run.** Firebase messaging is free, GitHub
  builds are free for this size of project, and internal testing is free after
  the one-time Play fee.

package app.family.notifyer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/* In-app updates.
 *
 * The page asks (FamilyUpdate.check) whether a newer app exists: a small
 * file published with every build (UPDATE_URL, see the workflow) says the
 * newest versionCode, its name, what it brings, where the APK is and its
 * SHA-256. If it is newer than this app, the page shows its "Update" bar;
 * install() downloads the APK to this app's own cache, checks the hash,
 * and hands it to Android's installer. Android replaces the app in place
 * - same package, same signing key - so the family, chats, settings and
 * permissions all stay. Progress and results go to the page through
 * window.familyUpdate({...}). */
class Updater(private val a: MainActivity) {
    companion object {
        const val UPDATE_URL = BuildConfig.UPDATE_URL        /* …/latest.json */
        private const val APK_NAME = "update.apk"
    }
    @Volatile private var latest: JSONObject? = null
    @Volatile private var busy = false

    private fun tell(o: JSONObject) =
        MainActivity.js("window.familyUpdate && familyUpdate(" + JSONObject.quote(o.toString()) + ")")

    private fun say(state: String, extra: (JSONObject) -> Unit = {}) {
        val o = JSONObject().put("state", state)
            .put("current", BuildConfig.VERSION_CODE).put("currentName", BuildConfig.VERSION_NAME)
        latest?.let { o.put("code", it.optInt("versionCode")).put("name", it.optString("versionName"))
                        .put("notes", it.optString("notes")) }
        extra(o); tell(o)
    }

    @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
    @JavascriptInterface fun versionCode(): Int = BuildConfig.VERSION_CODE

    /** Looks for a newer build; answers through familyUpdate: "none" | "available" | "checkfailed". */
    @JavascriptInterface fun check() {
        if (UPDATE_URL.isBlank()) { say("none"); return }
        Thread {
            try {
                val c = (URL(UPDATE_URL + (if (UPDATE_URL.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis())
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 10000; useCaches = false
                    setRequestProperty("Cache-Control", "no-cache")
                }
                val txt = c.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(txt)
                if (j.optInt("versionCode") > BuildConfig.VERSION_CODE && j.optString("url").startsWith("https://")) {
                    latest = j; say("available")
                } else { latest = null; say("none") }
            } catch (_: Exception) { say("checkfailed") }
        }.start()
    }

    /** Downloads the newer APK and opens the installer. States: "downloading" (pct), "installing", "failed" (why). */
    @JavascriptInterface fun install() {
        val j = latest ?: run { say("failed") { it.put("why", "nocheck") }; return }
        if (busy) return
        /* Android asks once per app for permission to install from it. */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !a.packageManager.canRequestPackageInstalls()) {
            say("failed") { it.put("why", "permission") }
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.packageName)))
            } catch (_: Exception) {}
            return
        }
        busy = true
        Thread {
            val out = File(a.cacheDir, APK_NAME)
            try {
                val c = (URL(j.getString("url")).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
                }
                val total = c.contentLengthLong
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L; var lastPct = -1
                c.inputStream.use { inp -> out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        o.write(buf, 0, n); md.update(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; say("downloading") { it.put("pct", pct) } }
                    }
                } }
                val want = j.optString("sha256").lowercase().replace(":", "")
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (want.isNotEmpty() && want != got) { out.delete(); say("failed") { it.put("why", "hash") }; busy = false; return@Thread }
                val uri = FileProvider.getUriForFile(a, a.packageName + ".files", out)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                say("installing")
                a.runOnUiThread { try { a.startActivity(i) } catch (e: Exception) { say("failed") { it.put("why", "installer") } } }
            } catch (_: Exception) {
                out.delete(); say("failed") { it.put("why", "download") }
            }
            busy = false
        }.start()
    }

    /** The downloaded file, once the new version is running (or the install was abandoned). */
    fun cleanUp() { try { File(a.cacheDir, APK_NAME).delete() } catch (_: Exception) {} }
}

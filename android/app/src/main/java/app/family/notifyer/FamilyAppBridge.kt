package app.family.notifyer

import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * window.FamilyApp, for the page (see "inside the family's Android app" in
 * index.html). helper() receives what the page also posts to sw.js.
 */
class FamilyAppBridge(private val a: MainActivity) {

    @JavascriptInterface
    fun helper(json: String) {
        val m = try { JSONObject(json) } catch (_: Exception) { return }
        val c = a.applicationContext
        when (m.str("t")) {
            "cfg" -> { m.remove("t"); Store.setCfg(c, m) }
            "lang" -> Store.setLang(c, m.str("v") ?: "en")
            "quiet" -> { m.remove("t"); Store.setQuiet(c, m) }
            "photos" -> m.optJSONObject("map")?.let { Store.setPhotos(c, it) }
            "silence" -> m.str("tag")?.let { Store.silence(c, it); Notes.stopRing(c, it); FamilySounds.CallRinger.stop(c) }
            "audible" -> m.str("tag")?.let {
                if (m.optBoolean("on")) {
                    Store.audible.add(it)
                    /* The app is on screen and ringing by itself: one ring, not two. */
                    if (Store.foreground) Notes.stopRing(c, it)
                } else Store.audible.remove(it)
            }
            "forget" -> Store.forget(c)
            "readchat" -> m.str("from")?.let { Notes.clearChat(c, it) }
            "test" -> Notes.other(c, JSONObject().put("title", m.str("title") ?: "Family Notifier")
                .put("body", m.str("body") ?: "").put("tag", "fmn-test"), "test")
        }
    }

    @JavascriptInterface
    fun pushToken(): String = Store.token(a)

    @JavascriptInterface
    fun notifState(): String = a.notifState()

    @JavascriptInterface
    fun askNotify() { a.runOnUiThread { a.askNotify() } }

    /** "ok" when an incoming call can fill the locked screen, "off" when the
        phone (Android 14+) still has to allow it - see openFullScreen(). */
    @JavascriptInterface
    fun fullScreenState(): String = a.fullScreenState()

    @JavascriptInterface
    fun openFullScreen() { a.runOnUiThread { a.openFullScreenSettings() } }

    @JavascriptInterface
    fun saveFile(name: String, mime: String, b64: String): String = a.saveFile(name, mime, b64)

    /* ---- the phone's own sounds, for the page while the app is on screen.
       With these present the page plays nothing of its own (see
       appSounds() in index.html). */

    /** An incoming call: on = ring with the phone's ringtone, at its ring
        volume, vibrating as the phone does; off = stop. "ok" when ringing
        out loud, "silent" when the phone is on silent, vibrate or Do Not
        Disturb. One ring, never two: the notification's own ring for the
        same call is taken down first. */
    @JavascriptInterface
    fun ring(on: Boolean, tag: String): String {
        val c = a.applicationContext
        return if (on) {
            if (tag.isNotEmpty()) Notes.stopRing(c, tag)
            FamilySounds.CallRinger.start(c, tag)
        } else {
            FamilySounds.CallRinger.stop(c); "ok"
        }
    }

    /** The phone's notification sound, once, at its notification volume. */
    @JavascriptInterface
    fun note(): String = FamilySounds.note(a.applicationContext)

    /** The phone's notification settings for this app. */
    @JavascriptInterface
    fun openSoundSettings() { a.runOnUiThread { FamilySounds.openSettings(a) } }
}

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
            "silence" -> m.str("tag")?.let { Store.silence(c, it); Notes.stopRing(c, it) }
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

    @JavascriptInterface
    fun saveFile(name: String, mime: String, b64: String): String = a.saveFile(name, mime, b64)
}

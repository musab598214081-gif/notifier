package app.family.notifyer

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The few things the app says to the family server by itself. */
object Net {
    fun post(url: String, body: String) {
        if (!url.startsWith("https://")) return
        runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
            c.disconnect()
        }
    }

    fun background(block: () -> Unit) = Thread { runCatching(block) }.start()

    private fun asMe(c: Context): JSONObject? {
        val cfg = Store.cfg(c) ?: return null
        val me = cfg.str("me") ?: return null
        val b = JSONObject().put("me", me)
        cfg.str("sec")?.let { b.put("sec", it) }
        return b
    }

    /** Picked up here: the server stops the repeat rings, and this person's other phones go quiet. */
    fun ringStop(c: Context, tag: String) {
        val send = Store.cfg(c)?.str("send") ?: return
        val b = asMe(c)?.put("k", "ringstop")?.put("tag", tag) ?: return
        background { post(send, b.toString()) }
    }

    /** A new push address while the app is closed: registered from here, as sw.js does. */
    fun sendSub(c: Context, token: String) {
        val send = Store.cfg(c)?.str("send") ?: return
        val b = asMe(c)?.put("k", "sub")?.put("sub", JSONObject().put("fcm", token)) ?: return
        background { post(send, b.toString()) }
    }
}

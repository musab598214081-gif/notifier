package app.family.notifyer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The few things the app says to the family server by itself. */
object Net {
    private const val ABC = "abcdefghjkmnpqrstuvwxyz23456789"

    /** One request to the family server, and its answer when there is one. */
    fun call(url: String, body: String): JSONObject? {
        if (!url.startsWith("https://")) return null
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            val txt = runCatching {
                (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
            }.getOrNull() ?: ""
            c.disconnect()
            runCatching { JSONObject(txt) }.getOrNull() ?: JSONObject().put("status", code)
        }.getOrNull()
    }

    fun post(url: String, body: String) { call(url, body) }

    fun background(block: () -> Unit) = Thread { runCatching(block) }.start()

    private fun asMe(c: Context): JSONObject? {
        val cfg = Store.cfg(c) ?: return null
        val me = cfg.str("me") ?: return null
        val b = JSONObject().put("me", me)
        cfg.str("sec")?.let { b.put("sec", it) }
        cfg.str("dk")?.let { b.put("dk", it) }
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

    /** A reply typed in a notification, sent as a chat message. Call off the main thread. */
    fun chat(c: Context, to: String, text: String): Boolean {
        val cfg = Store.cfg(c) ?: return false
        val send = cfg.str("send") ?: return false
        val b = asMe(c) ?: return false
        val cid = "c" + (1..14).map { ABC.random() }.joinToString("")
        b.put("k", "chat").put("to", to).put("cid", cid).put("text", text.take(4000))
            .put("title", cfg.str("name") ?: "New message")
        cfg.str("url")?.let { b.put("url", "$it#n=1") }
        return call(send, b.toString())?.optBoolean("ok") == true
    }

    /** Those messages, read. Call off the main thread. */
    fun read(c: Context, to: String, ids: List<String>) {
        val send = Store.cfg(c)?.str("send") ?: return
        val b = asMe(c)?.put("k", "rcpt")?.put("to", to)?.put("s", "r")
            ?.put("ids", JSONArray(ids.take(200))) ?: return
        call(send, b.toString())
    }
}

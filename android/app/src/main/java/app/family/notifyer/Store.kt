package app.family.notifyer

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** What the page has told the app (the same things it tells sw.js), kept between launches. */
object Store {
    /** The app is on screen right now. */
    @Volatile var foreground = false

    /** Calls the open page is ringing out loud for: no notification ring on top of it. */
    val audible: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun sp(c: Context) = c.getSharedPreferences("family", Context.MODE_PRIVATE)

    /** { send, me, sec, skew, url } - where the family server is and who this phone is. */
    fun cfg(c: Context): JSONObject? =
        sp(c).getString("cfg", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun setCfg(c: Context, j: JSONObject) = sp(c).edit().putString("cfg", j.toString()).apply()
    fun forget(c: Context) = sp(c).edit().remove("cfg").apply()

    fun lang(c: Context) = sp(c).getString("lang", "en") ?: "en"
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", if (v == "ar") "ar" else "en").apply()

    fun token(c: Context) = sp(c).getString("token", "") ?: ""
    fun setToken(c: Context, t: String) = sp(c).edit().putString("token", t).apply()

    fun flag(c: Context, name: String) = sp(c).getBoolean(name, false)
    fun setFlag(c: Context, name: String) = sp(c).edit().putBoolean(name, true).apply()

    /** Calls answered, declined or ended here: their later rings stay quiet (as sw.js does). */
    @Synchronized fun silence(c: Context, tag: String) {
        val o = silenced(c)
        val now = System.currentTimeMillis()
        o.put(tag, now)
        o.keys().asSequence().toList().filter { now - o.optLong(it) > 15 * 60_000 }.forEach { o.remove(it) }
        sp(c).edit().putString("silenced", o.toString()).apply()
    }
    @Synchronized fun isSilenced(c: Context, tag: String): Boolean {
        val t = silenced(c).optLong(tag, 0)
        return t > 0 && System.currentTimeMillis() - t < 15 * 60_000
    }
    private fun silenced(c: Context): JSONObject =
        runCatching { JSONObject(sp(c).getString("silenced", "{}") ?: "{}") }.getOrDefault(JSONObject())
}

/** A string field, or null when it is missing, empty or JSON null. */
fun JSONObject.str(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }

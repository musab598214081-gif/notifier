package app.family.notifyer

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
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

    /** People's photos, as the page keeps them (small square JPEGs). */
    fun setPhotos(c: Context, j: JSONObject) {
        val e = sp(c).edit()
        val keys = j.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            if (!Regex("^[A-Za-z0-9]{3,16}$").matches(id)) continue
            val d = j.optString(id)
            if (d.startsWith("data:image/jpeg;base64,") && d.length < 40000) e.putString("ph:$id", d)
            else if (d.isEmpty()) e.remove("ph:$id")
        }
        e.apply()
    }
    fun photo(c: Context, id: String?): String? =
        if (id.isNullOrEmpty()) null else sp(c).getString("ph:$id", null)

    /** Quiet hours: { on, from: "22:00", to: "07:00" }. */
    fun setQuiet(c: Context, j: JSONObject) = sp(c).edit().putString("quiet", j.toString()).apply()
    fun quietNow(c: Context): Boolean {
        val q = runCatching { JSONObject(sp(c).getString("quiet", "{}") ?: "{}") }.getOrNull() ?: return false
        if (!q.optBoolean("on")) return false
        val f = mins(q.optString("from"))
        val t = mins(q.optString("to"))
        if (f < 0 || t < 0 || f == t) return false
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (f < t) now in f until t else now >= f || now < t
    }
    private fun mins(s: String): Int {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(s) ?: return -1
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

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

package app.family.notifyer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.media.AudioAttributes
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONObject
import kotlin.math.abs

/**
 * The app's notifications - the same decisions sw.js makes for the browser,
 * made here for pushes that arrive through Firebase:
 *
 *  - a call rings like a phone call: full screen over the lock screen, the
 *    phone's ringtone looping until it is answered, declined or cancelled;
 *  - a missed call replaces it, with Call back;
 *  - a message groups per person; anything else is one short alert;
 *  - with the app on screen the page shows things itself, so nothing is shown.
 */
object Notes {
    /* calls_v2: calls_v1 carried its own vibration pattern. A channel's
       sound and vibration are fixed when it is first created, so the only
       way to hand them back to the phone on phones that already have the
       app is a new channel; the old one is removed below. */
    const val CH_CALL = "calls_v2"
    private const val CH_CALL_OLD = "calls_v1"
    const val CH_MSG = "messages_v1"
    const val CH_MISSED = "missed_v1"
    const val CH_ONGOING = "ongoing_v1"

    private const val ID_CALL = 1
    private const val ID_MISSED = 2
    private const val ID_MSG = 3
    private const val ID_OTHER = 4
    const val ID_ONGOING = 5

    private val CALLING = Regex("\\s+is calling you.*$", RegexOption.IGNORE_CASE)
    private val msgLines = HashMap<String, MutableList<String>>()
    private val msgIds = HashMap<String, MutableList<String>>()

    /* Every sound and vibration is the phone's own: the ringtone chosen in
       Sound & vibration for a call (on the ring stream, so the ring volume
       applies) and the phone's notification sound for the rest, with the
       phone's default vibration for each. The URIs are the symbolic
       "current default" ones, so a ringtone changed in the phone's settings
       is used from the next call. Do Not Disturb is never bypassed. */
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_CALL_OLD) != null) nm.deleteNotificationChannel(CH_CALL_OLD)
        if (nm.getNotificationChannel(CH_CALL) != null) return
        val calls = NotificationChannel(CH_CALL, c.getString(R.string.ch_calls), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)              /* no pattern of our own: the phone's */
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val noteSound = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val msgs = NotificationChannel(CH_MSG, c.getString(R.string.ch_messages), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, noteSound)
            enableVibration(true)
        }
        val missed = NotificationChannel(CH_MISSED, c.getString(R.string.ch_missed), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(Settings.System.DEFAULT_NOTIFICATION_URI, noteSound)
        }
        val ongoing = NotificationChannel(CH_ONGOING, c.getString(R.string.ch_ongoing), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannels(listOf(calls, msgs, missed, ongoing))
    }

    /** A push from the family server (the same payload sw.js receives). */
    fun handle(c: Context, d0: JSONObject) {
        channels(c)
        var d = d0
        var kind = d.str("kind") ?: "buzz"
        val tag = d.str("tag")
        var ringing = kind == "call" || kind == "group"
        val front = Store.foreground

        /* An open page reconnects at once, so the call behind the push arrives now. */
        MainActivity.js("window.familyPush && familyPush()")

        if (kind == "cancel") {
            if (tag != null) { stopRing(c, tag); Store.silence(c, tag) }
            return
        }
        if (kind == "msg") { if (!front) message(c, d); return }

        /* A call that arrives long after it was sent is not ringing any more -
           measured by the server's clock (skew: how far this phone's is off). */
        if (ringing) {
            val skew = Store.cfg(c)?.optLong("skew", 0L) ?: 0L
            val ts = d.optLong("ts", 0)
            if (ts > 0 && abs(skew) < 86_400_000 && System.currentTimeMillis() + skew - ts > 70_000) {
                d = JSONObject(d.toString())
                    .put("title", "Missed call")
                    .put("body", (d0.str("title") ?: "").replace(CALLING, "") + " tried to call you")
                    .put("cb", d0.str("from") ?: JSONObject.NULL)
                kind = "missed"
                ringing = false
            }
        }
        if (ringing && tag != null) {
            if (Store.isSilenced(c, tag)) return
            if (front && Store.audible.contains(tag)) return     /* the page rings by itself */
            ring(c, d, kind, tag)
            return
        }
        if (kind == "missed") {
            if (tag != null) { stopRing(c, tag); Store.silence(c, tag) }
            if (!front) missed(c, d)
            return
        }
        if (front && kind != "test") return
        other(c, d, kind)
    }

    private fun ring(c: Context, d: JSONObject, kind: String, tag: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        /* Still ringing from the last push: the ring goes on, nothing to add. */
        if (nm.activeNotifications.any { it.tag == "c:$tag" }) return
        val url = d.str("url") ?: base(c)
        val answer = d.str("answer") ?: d.str("ans")?.let { url + it } ?: url
        val title = tr(c, d.str("title") ?: "Incoming call")
        val who = (d.str("title") ?: "").replace(CALLING, "").ifBlank { c.getString(R.string.app_name) }

        val open = MainActivity.intent(c, url, tag, kind, MainActivity.HOW_OPEN)
        val full = MainActivity.intent(c, url, tag, kind, MainActivity.HOW_FULL)
        val ans = MainActivity.intent(c, answer, tag, kind, MainActivity.HOW_ANSWER)
        val dec = NoteActionReceiver.pending(c, NoteActionReceiver.DECLINE, tag, d.optJSONObject("dec")?.toString())

        val pic = photo(c, d.str("from"))
        val pb = Person.Builder().setName(who).setImportant(true)
        if (pic != null) pb.setIcon(IconCompat.createWithBitmap(pic))
        val person = pb.build()
        val nb = NotificationCompat.Builder(c, CH_CALL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(tr(c, d.str("body") ?: ""))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(activity(c, open, "o$tag"))
            .setFullScreenIntent(activity(c, full, "f$tag"), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, dec, activity(c, ans, "a$tag")))
            .setTimeoutAfter(65_000)
        if (pic != null) nb.setLargeIcon(pic)
        val n = nb.build()
        /* The ringtone loops, like a phone call, until the notification goes. */
        n.flags = n.flags or Notification.FLAG_INSISTENT
        notify(c, "c:$tag", ID_CALL, n)
    }

    fun stopRing(c: Context, tag: String) {
        NotificationManagerCompat.from(c).cancel("c:$tag", ID_CALL)
    }

    private fun missed(c: Context, d: JSONObject) {
        val tag = d.str("tag") ?: d.str("from") ?: "x"
        var url = d.str("url") ?: base(c)
        d.str("cb")?.let { url += (if (url.contains('#')) "&" else "#") + "cb=" + enc(it) }
        val i = MainActivity.intent(c, url, null, "missed", MainActivity.HOW_OPEN)
        val pi = activity(c, i, "x$tag")
        val b = NotificationCompat.Builder(c, CH_MISSED)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(tr(c, d.str("title") ?: "Missed call"))
            .setContentText(tr(c, d.str("body") ?: ""))
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setSilent(Store.quietNow(c))
            .setContentIntent(pi)
        photo(c, d.str("cb") ?: d.str("from"))?.let { b.setLargeIcon(it) }
        if (d.str("cb") != null) b.addAction(0, act(c, "Call back"), pi)
        notify(c, "x:$tag", ID_MISSED, b.build())
    }

    /** One notification per sender; a second message joins the first. */
    private fun message(c: Context, d: JSONObject) {
        val from = d.str("from") ?: ""
        val tag = d.str("tag") ?: "m-$from"
        var url = d.str("url") ?: base(c)
        if (from.isNotEmpty()) url += (if (url.contains('#')) "&" else "#") + "chat=" + enc(from)
        val lines = synchronized(msgLines) {
            d.str("cid")?.let { cid ->
                msgIds.getOrPut(tag) { mutableListOf() }.apply { add(cid); while (size > 50) removeAt(0) }
            }
            msgLines.getOrPut(tag) { mutableListOf() }.apply { add(d.str("body") ?: ""); while (size > 4) removeAt(0) }.toList()
        }
        val count = lines.size
        val style = NotificationCompat.InboxStyle()
        lines.forEach { style.addLine(tr(c, it)) }
        val b = NotificationCompat.Builder(c, CH_MSG)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(tr(c, d.str("title") ?: "New message"))
            .setContentText(tr(c, lines.last()))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setSilent(Store.quietNow(c))
            .setNumber(count)
            .setContentIntent(activity(c, MainActivity.intent(c, url, null, "msg", MainActivity.HOW_OPEN), "m$tag"))
        if (count > 1) b.setStyle(style)
        photo(c, from.ifEmpty { null })?.let { b.setLargeIcon(it) }
        if (from.isNotEmpty() && Store.cfg(c)?.str("send") != null) {
            val label = act(c, "Reply")
            val input = RemoteInput.Builder(NoteActionReceiver.KEY_TEXT).setLabel(label).build()
            b.addAction(NotificationCompat.Action.Builder(0, label, NoteActionReceiver.reply(c, tag, from))
                .addRemoteInput(input).setAllowGeneratedReplies(true).build())
        }
        notify(c, "n:$tag", ID_MSG, b.build())
    }

    /** The conversation was opened in the app: its notification has done its job. */
    fun clearChat(c: Context, from: String) {
        val tag = "m-$from"
        synchronized(msgLines) { msgLines.remove(tag); msgIds.remove(tag) }
        NotificationManagerCompat.from(c).cancel("n:$tag", ID_MSG)
    }

    /** The ids of the messages a notification showed (to mark them read after a reply). */
    fun takeIds(tag: String): List<String> =
        synchronized(msgLines) { msgIds.remove(tag)?.toList() ?: emptyList() }

    /** A reply that could not be sent: said, with a way into the chat. */
    fun replyFailed(c: Context, to: String, text: String) {
        val url = base(c).substringBefore('#') + "#chat=" + enc(to)
        other(c, JSONObject().put("title", "Reply not sent").put("body", text)
            .put("tag", "rf-$to").put("url", url), "test")
    }

    /** A buzz, a test, a join request - one short alert. */
    fun other(c: Context, d: JSONObject, kind: String) {
        val tag = d.str("tag") ?: kind
        val home = base(c)
        var url = d.str("url") ?: home
        val b = NotificationCompat.Builder(c, CH_MSG)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(tr(c, d.str("title") ?: c.getString(R.string.app_name)))
            .setContentText(tr(c, d.str("body") ?: ""))
            .setAutoCancel(true)
        if (kind != "test") b.setSilent(Store.quietNow(c))
        photo(c, d.str("from"))?.let { b.setLargeIcon(it) }
        val jr = d.str("jr")
        if (kind == "approve" && jr != null) {
            /* Somebody wants to join: stays until the admin decides. */
            url = home.substringBefore('#') + "#jr=" + enc(jr)
            val yes = MainActivity.intent(c, home.substringBefore('#') + "#approve=" + enc(jr), null, kind, MainActivity.HOW_OPEN)
            b.addAction(0, act(c, "Approve"), activity(c, yes, "y$tag"))
            d.optJSONObject("rej")?.let {
                b.addAction(0, act(c, "Reject"), NoteActionReceiver.pending(c, NoteActionReceiver.REJECT, "o:$tag", it.toString()))
            }
            b.setOngoing(true)
        }
        /* Somebody's invitation expired and they asked for a new link: a tap
           opens the request; Send link goes straight to sharing it. */
        val rn = d.str("rn")
        if (kind == "renew" && rn != null) {
            val page = home.substringBefore('#')
            url = page + "#renew=" + enc(rn)
            val send = MainActivity.intent(c, page + "#renewsend=" + enc(rn), null, kind, MainActivity.HOW_OPEN)
            b.addAction(0, act(c, "Send link"), activity(c, send, "s$tag"))
            b.setOngoing(true)
        }
        b.setContentIntent(activity(c, MainActivity.intent(c, url, null, kind, MainActivity.HOW_OPEN), "t$tag"))
        notify(c, "o:$tag", ID_OTHER, b.build())
    }
    fun cancelOther(c: Context, tag: String) = NotificationManagerCompat.from(c).cancel(tag, ID_OTHER)

    fun ongoing(c: Context): Notification {
        channels(c)
        val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return NotificationCompat.Builder(c, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(c.getString(R.string.call_ongoing))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    /** A person's photo, as a round bitmap - or null when they have none. */
    private fun photo(c: Context, id: String?): Bitmap? {
        val d = Store.photo(c, id) ?: return null
        return try {
            val bytes = Base64.decode(d.substringAfter(","), Base64.DEFAULT)
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val size = minOf(src.width, src.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
            out
        } catch (_: Exception) { null }
    }

    @SuppressLint("MissingPermission")
    private fun notify(c: Context, tag: String, id: Int, n: Notification) {
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        try { nm.notify(tag, id, n) } catch (_: SecurityException) {}
    }

    private fun activity(c: Context, i: Intent, key: String): PendingIntent =
        PendingIntent.getActivity(c, key.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun base(c: Context) = Store.cfg(c)?.str("url") ?: BuildConfig.APP_URL

    private fun enc(s: String): String = android.net.Uri.encode(s)

    /* ------------------------------------------------ Arabic, as in sw.js */

    private val AR_NOTE = listOf(
        Regex("^(.+?) is calling you.*$") to "$1 يتصل بك",
        Regex("^(.+?) is buzzing you$") to "$1 ينبّهك",
        Regex("^(.+?) tried to call you$") to "$1 حاول الاتصال بك",
        Regex("^(.+?) is asking you into a group call$") to "$1 يدعوك إلى مكالمة جماعية",
        Regex("^(.+?) wants to join$") to "$1 يريد الانضمام",
        Regex("^Missed call$") to "مكالمة فائتة",
        Regex("^Missed group call$") to "مكالمة جماعية فائتة",
        Regex("^Group call$") to "مكالمة جماعية",
        Regex("^New message$") to "رسالة جديدة",
        Regex("^Incoming call$") to "مكالمة واردة",
        Regex("^Call ended$") to "انتهت المكالمة",
        Regex("^Tap to answer$") to "اضغط للرد",
        Regex("^wants your attention$") to "يريد انتباهك",
        Regex("^Reply not sent$") to "لم يُرسل الرد",
        Regex("^\uD83D\uDCF7 Photo") to "\uD83D\uDCF7 صورة",
        Regex("^\uD83D\uDCCD Location") to "\uD83D\uDCCD الموقع",
        Regex("^\uD83C\uDFA4 Voice message") to "\uD83C\uDFA4 رسالة صوتية",
        Regex("^Call me$") to "اتصل بي",
        Regex("^Come here please$") to "تعال هنا من فضلك",
        Regex("^Dinner is ready$") to "العشاء جاهز",
        Regex("^Are you OK\\?$") to "هل أنت بخير؟",
        Regex("^I'm on my way$") to "أنا في الطريق",
        Regex("^Ring me when free$") to "اتصل بي عندما تتفرغ",
        Regex("^This is how messages and other notifications look and sound\\.$") to "هكذا تبدو الرسائل والإشعارات الأخرى وتُسمع."
    )
    private val AR_ACT = mapOf("Call back" to "معاودة الاتصال", "Approve" to "موافقة", "Reject" to "رفض", "Send link" to "إرسال الرابط", "Reply" to "رد")

    fun tr(c: Context, text: String): String {
        if (Store.lang(c) != "ar") return text
        return text.split("\n").joinToString("\n") { line ->
            AR_NOTE.firstOrNull { it.first.containsMatchIn(line) }?.let { line.replace(it.first, it.second) } ?: line
        }
    }
    private fun act(c: Context, label: String) = if (Store.lang(c) == "ar") AR_ACT[label] ?: label else label
}

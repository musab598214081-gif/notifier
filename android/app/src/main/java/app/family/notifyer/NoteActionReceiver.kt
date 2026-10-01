package app.family.notifyer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** Decline (a call) and Reject (a join request), straight from the notification -
    no need to open the app to say no. Each carries the request the page prepared. */
class NoteActionReceiver : BroadcastReceiver() {
    companion object {
        const val DECLINE = "app.family.notifyer.DECLINE"
        const val REJECT = "app.family.notifyer.REJECT"

        fun pending(c: Context, action: String, tag: String, body: String?): PendingIntent {
            val i = Intent(c, NoteActionReceiver::class.java)
                .setAction(action).putExtra("tag", tag).putExtra("body", body)
            return PendingIntent.getBroadcast(c, (action + tag).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onReceive(c: Context, i: Intent) {
        val tag = i.getStringExtra("tag") ?: ""
        when (i.action) {
            DECLINE -> { Store.silence(c, tag); Notes.stopRing(c, tag) }
            REJECT -> Notes.cancelOther(c, tag)          /* tag is the notification's own, "o:…" */
        }
        val body = i.getStringExtra("body") ?: return
        val done = goAsync()
        Thread {
            try {
                val j = JSONObject(body)
                Net.post(j.optString("u"), j.optJSONObject("b")?.toString() ?: "{}")
            } catch (_: Exception) {
            } finally { done.finish() }
        }.start()
    }
}

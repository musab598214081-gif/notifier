package app.family.notifyer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import org.json.JSONObject

/** Decline (a call), Reject (a join request) and Reply (a message), straight from
    the notification - no need to open the app. */
class NoteActionReceiver : BroadcastReceiver() {
    companion object {
        const val DECLINE = "app.family.notifyer.DECLINE"
        const val REJECT = "app.family.notifyer.REJECT"
        const val REPLY = "app.family.notifyer.REPLY"
        const val KEY_TEXT = "reply_text"

        fun pending(c: Context, action: String, tag: String, body: String?): PendingIntent {
            val i = Intent(c, NoteActionReceiver::class.java)
                .setAction(action).putExtra("tag", tag).putExtra("body", body)
            return PendingIntent.getBroadcast(c, (action + tag).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Decline from the ringing screen: the same as the notification's Decline. */
        fun decline(c: Context, tag: String) {
            try { pending(c, DECLINE, tag, Notes.declineBody(tag)).send() }
            catch (_: PendingIntent.CanceledException) { Store.silence(c, tag); Notes.stopRing(c, tag) }
        }

        /** Mutable on purpose: Android puts the typed reply into it. */
        fun reply(c: Context, tag: String, to: String): PendingIntent {
            val i = Intent(c, NoteActionReceiver::class.java)
                .setAction(REPLY).putExtra("tag", tag).putExtra("to", to)
            return PendingIntent.getBroadcast(c, (REPLY + tag).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }

    override fun onReceive(c: Context, i: Intent) {
        val tag = i.getStringExtra("tag") ?: ""
        if (i.action == REPLY) {
            val to = i.getStringExtra("to") ?: return
            val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
            if (text.isEmpty()) { Notes.clearChat(c, to); return }
            val done = goAsync()
            Thread {
                try {
                    if (Net.chat(c, to, text)) {
                        val ids = Notes.takeIds(tag)
                        if (ids.isNotEmpty()) Net.read(c, to, ids)
                        Notes.clearChat(c, to)
                    } else {
                        Notes.clearChat(c, to)
                        Notes.replyFailed(c, to, text)
                    }
                } catch (_: Exception) {
                } finally { done.finish() }
            }.start()
            return
        }
        when (i.action) {
            DECLINE -> { Store.silence(c, tag); Notes.stopRing(c, tag) }
            REJECT -> Notes.cancelOther(c, tag)
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

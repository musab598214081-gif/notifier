package app.family.notifyer

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

/** Pushes from the family server, through Firebase. The payload is the same
    JSON a browser's sw.js receives, carried in the data field "p". */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Store.setToken(this, token)
        Net.sendSub(this, token)
        MainActivity.js("window.familyPushToken && familyPushToken()")
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val p = m.data["p"] ?: return
        val d = try { JSONObject(p) } catch (_: Exception) { return }
        Notes.handle(this, d)
    }
}

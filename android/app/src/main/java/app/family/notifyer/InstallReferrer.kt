package app.family.notifyer

import android.content.Context
import android.net.Uri
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * An invitation that survived the Play Store. The web page sends a phone that
 * has no app to Google Play with the invitation in the `referrer` parameter
 * (index.html, storeUrl). After the install, Play hands that string back
 * here, once, and the app opens the invitation as if its link had been
 * tapped. Phones that got the app some other way (the direct APK) have no
 * referrer and nothing happens.
 */
object InstallReferrer {
    private const val DONE = "referrer_done"
    private val CODE = Regex("^[2-9A-HJ-NP-Z]{10}$")
    private val SERVER = Regex("^[A-Za-z0-9_-]{1,512}$")

    /** Asks Play once; calls [open] with the page address to open, if there is one. */
    fun check(c: Context, open: (String) -> Unit) {
        if (Store.flag(c, DONE)) return
        val app = c.applicationContext
        val client = try { InstallReferrerClient.newBuilder(app).build() } catch (_: Exception) { return }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(code: Int) {
                    try {
                        when (code) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                Store.setFlag(app, DONE)
                                link(client.installReferrer.installReferrer)?.let(open)
                            }
                            /* No Play on this phone, or not supported: never going to work. */
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                                Store.setFlag(app, DONE)
                            /* Service unavailable or a developer error: try again next start. */
                            else -> {}
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { client.endConnection() } catch (_: Exception) {}
                    }
                }
                override fun onInstallReferrerServiceDisconnected() {}
            })
        } catch (_: Exception) {}
    }

    /**
     * The referrer is the invitation's own fragment, e.g. "i=K7P2QX9M4T" or
     * "i=K7P2QX9M4T&f=<server>". Only those two fields are read, and only
     * when they look right: the referrer is text anyone's link could carry.
     */
    internal fun link(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null
        val q = Uri.parse("https://x/?" + referrer)
        val code = q.getQueryParameter("i")?.uppercase()?.takeIf { CODE.matches(it) } ?: return null
        val server = q.getQueryParameter("f")?.takeIf { SERVER.matches(it) }
        return BuildConfig.APP_URL + "#i=" + code + (if (server != null) "&f=$server" else "")
    }
}

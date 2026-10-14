package app.family.notifyer

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.json.JSONObject

/**
 * window.FamilyUpdate in the Play build: a new version of the app, from
 * Google Play but without going to the Play Store. The app asks Play now
 * and then (on coming back to the screen, and whenever the page asks); a
 * newer version is offered on the page's update bar ("App version 1.0.N
 * is ready - Update"), one tap shows Play's own small sheet inside the
 * app, the download runs in the background while the app is used, and
 * the app restarts into the new version - never in the middle of a call.
 *
 * What the page is told, through window.familyUpdate (as the direct
 * build's updater did): available {code, name}, downloading {pct},
 * installing, none, failed {why}.
 */
class Updater(private val a: MainActivity) {
    private val mgr = AppUpdateManagerFactory.create(a)
    private val main = Handler(Looper.getMainLooper())
    private var info: AppUpdateInfo? = null
    private var lastLook = 0L
    /* Downloaded during a call: put in once the call is over. */
    private var waiting = false

    /* Play's sheet: Update tapped, or closed without it. */
    private val flow = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        when (r.resultCode) {
            Activity.RESULT_OK -> {}                        /* downloading - the listener reports it */
            ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> tell(JSONObject().put("state", "failed").put("why", "download"))
            else -> look()                                  /* not now: offered again */
        }
    }

    private val progress = InstallStateUpdatedListener { st ->
        when (st.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val total = st.totalBytesToDownload()
                val pct = if (total > 0) (st.bytesDownloaded() * 100 / total).toInt() else -1
                tell(JSONObject().put("state", "downloading").put("pct", pct))
            }
            InstallStatus.DOWNLOADED -> finish()
            InstallStatus.INSTALLING -> tell(JSONObject().put("state", "installing"))
            InstallStatus.FAILED -> tell(JSONObject().put("state", "failed").put("why", "download"))
            InstallStatus.CANCELED -> look()
            else -> {}
        }
    }

    /** Once, when the app starts. */
    fun cleanUp() {
        mgr.registerListener(progress)
    }

    /** Back on the screen: a finished download goes in; otherwise a look, every half hour at most. */
    fun onResume() {
        if (System.currentTimeMillis() - lastLook > 30 * 60 * 1000L) look()
    }

    /** A call has just ended: an update that waited for it goes in now. */
    fun callEnded() {
        if (waiting) main.postDelayed({ finish() }, 1500)
    }

    fun release() {
        try { mgr.unregisterListener(progress) } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun check() { main.post { look() } }

    @JavascriptInterface
    fun install() { main.post { start() } }

    private fun look() {
        lastLook = System.currentTimeMillis()
        mgr.appUpdateInfo.addOnSuccessListener { i ->
            info = i
            when {
                i.installStatus() == InstallStatus.DOWNLOADED -> finish()
                i.installStatus() == InstallStatus.DOWNLOADING ->
                    tell(JSONObject().put("state", "downloading").put("pct", -1))
                i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> {
                    val code = i.availableVersionCode()
                    tell(JSONObject().put("state", "available").put("code", code).put("name", "1.0.$code"))
                }
                else -> tell(JSONObject().put("state", "none"))
            }
        }.addOnFailureListener {
            /* Not installed from Google Play (a test build): nothing to offer. */
            tell(JSONObject().put("state", "none"))
        }
    }

    private fun start() {
        val i = info
        if (i == null || i.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) {
            if (i?.installStatus() == InstallStatus.DOWNLOADED) finish() else look()
            return
        }
        /* One AppUpdateInfo starts one flow: a fresh one is fetched for the next. */
        info = null
        val ok = try {
            mgr.startUpdateFlowForResult(i, flow, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
        } catch (_: Exception) { false }
        if (!ok) tell(JSONObject().put("state", "failed").put("why", "installer"))
    }

    /** Downloaded: restart into the new version - after the call, if there is one. */
    private fun finish() {
        if (a.inCall) {
            waiting = true
            tell(JSONObject().put("state", "downloading").put("pct", 100))
            return
        }
        waiting = false
        tell(JSONObject().put("state", "installing"))
        /* What the page holds is written down first. */
        MainActivity.js("try { saveFlush(); chatFlush(); } catch (e) {}")
        main.postDelayed({ mgr.completeUpdate() }, 600)
    }

    private fun tell(j: JSONObject) {
        MainActivity.js("window.familyUpdate && familyUpdate(${JSONObject.quote(j.toString())})")
    }
}

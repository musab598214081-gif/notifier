package app.family.notifyer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * The family's web app (APP_URL), in a WebView, with what a browser can't do:
 * the earpiece (FamilyAudioBridge), calls that ring like a phone call even
 * when the app is closed (PushService + Notes), and a call that keeps going
 * in the background (CallService).
 */
class MainActivity : AppCompatActivity() {
    lateinit var updater: Updater

    companion object {
        const val HOW_OPEN = "open"       /* the notification was tapped */
        const val HOW_FULL = "full"       /* a ringing call took over the screen */
        const val HOW_ANSWER = "answer"   /* Answer was pressed */

        private var current: WeakReference<MainActivity>? = null

        fun intent(c: Context, url: String, tag: String?, kind: String?, how: String): Intent =
            Intent(c, MainActivity::class.java)
                .setAction("app.family.notifyer.$how.${tag ?: kind ?: ""}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("url", url).putExtra("tag", tag).putExtra("kind", kind).putExtra("how", how)

        /** Runs a line of JavaScript in the page, if the app is open. */
        fun js(code: String) {
            val a = current?.get() ?: return
            a.runOnUiThread { if (!a.isDestroyed) a.web.evaluateJavascript(code, null) }
        }
    }

    lateinit var web: WebView
        private set
    private lateinit var audio: FamilyAudioBridge
    private var host = ""
    private var pageReady = false
    @Volatile private var inCall = false
    private var recheckPush = false

    private var pendingPerm: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    /* ---------------------------------------------------------------- permissions */

    private val forPage = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val req = pendingPerm ?: return@registerForActivityResult
        pendingPerm = null
        val ok = req.resources.filter { r -> androidPerm(r)?.let { granted(it) } ?: false }
        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
    }
    private val forGeo = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val g = pendingGeo ?: return@registerForActivityResult
        pendingGeo = null
        g.second.invoke(g.first, res.values.any { it }, false)
    }
    private val forNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        js("window.familyNotifyResult && familyNotifyResult($ok)")
    }
    private val atStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askFullScreen()
        js("window.familyPushToken && familyPushToken()")
    }
    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun androidPerm(resource: String) = when (resource) {
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        else -> null
    }

    /* ---------------------------------------------------------------- start */

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        Notes.channels(this)
        host = Uri.parse(BuildConfig.APP_URL).host ?: ""

        web = WebView(this)
        val root = FrameLayout(this)
        root.setBackgroundColor(getColor(R.color.night))
        root.addView(web, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        /* Clear of the status bar, the navigation bar and the keyboard. */
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        setUpWeb()
        audio = FamilyAudioBridge(this)
        web.addJavascriptInterface(audio, "FamilyAudio")
        web.addJavascriptInterface(FamilyAppBridge(this), "FamilyApp")
        updater = Updater(this)
        web.addJavascriptInterface(updater, "FamilyUpdate")
        updater.cleanUp()                    /* a finished (or abandoned) update's file */

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                /* The page first: it closes what is open on top (a menu, a
                   sheet, a settings page, a conversation), like any app. */
                web.evaluateJavascript("window.familyBack ? familyBack() : null") { r ->
                    if (r == "true" || isDestroyed) return@evaluateJavascript
                    /* Nothing open: back to the home screen, not closed - the
                       family connection stays up. (A page too old to answer
                       still gets its own history.) */
                    if (r != "false" && web.canGoBack()) web.goBack() else moveTaskToBack(true)
                }
            }
        })

        val opened = handle(intent, cold = true)
        if (!opened) web.loadUrl(BuildConfig.APP_URL)
        /* A fresh install from Google Play that began with an invitation:
           open it. Not when this phone is already signed in to a family. */
        if (!opened && Store.cfg(this) == null) {
            InstallReferrer.check(this) { url ->
                runOnUiThread {
                    if (isDestroyed || Store.cfg(this) != null) return@runOnUiThread
                    if (pageReady) web.evaluateJavascript("window.familyOpen && familyOpen(${JSONObject.quote(url)})", null)
                    else web.loadUrl(url)
                }
            }
        }
        fetchToken()
        firstRun()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i, cold = false)
    }

    override fun onResume() {
        super.onResume()
        Store.foreground = true
        web.onResume()
        if (recheckPush) { recheckPush = false; js("window.familyPushToken && familyPushToken()") }
    }

    override fun onPause() {
        super.onPause()
        Store.foreground = false
        /* Paused (so the page knows it is hidden) - except during a call,
           which must keep going while you use another app. */
        if (!inCall) web.onPause()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        try { audio.release() } catch (_: Exception) {}
        CallService.stop(this)
        web.destroy()
        super.onDestroy()
    }

    /* ---------------------------------------------------------------- the page */

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWeb() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false     /* a call's sound starts by itself */
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(true)
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString FamilyNotifierApp/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.isForMainFrame && allowed(r.url)) return false
                if (r.isForMainFrame) outside(r.url)
                return r.isForMainFrame
            }
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { pageReady = false }
            override fun onPageFinished(v: WebView, url: String?) { pageReady = true }
            override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(req: PermissionRequest) {
                runOnUiThread {
                    if (req.origin.host != host) { req.deny(); return@runOnUiThread }
                    val need = req.resources.mapNotNull { androidPerm(it) }.filter { !granted(it) }
                    if (need.isEmpty()) {
                        val ok = req.resources.filter { androidPerm(it) != null }
                        if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
                    } else {
                        pendingPerm?.deny()
                        pendingPerm = req
                        forPage.launch(need.toTypedArray())
                    }
                }
            }
            override fun onPermissionRequestCanceled(req: PermissionRequest) {
                if (pendingPerm === req) pendingPerm = null
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, cb: GeolocationPermissions.Callback) {
                if (Uri.parse(origin).host != host) { cb.invoke(origin, false, false); return }
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origin, true, false); return
                }
                pendingGeo = origin to cb
                forGeo.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            override fun onShowFileChooser(v: WebView, cb: ValueCallback<Array<Uri>>, p: WebChromeClient.FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { pickFiles.launch(p.createIntent()); true } catch (_: Exception) { fileCb = null; false }
            }
        }
    }

    /** Only the family app's own address opens inside the app (it can reach the bridges). */
    private fun allowed(u: Uri): Boolean =
        u.scheme == "https" && u.host == host
    private fun allowed(s: String) = allowed(Uri.parse(s))

    /** Maps, web links and the like: in the phone's own apps. */
    private fun outside(u: Uri) {
        try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    /** A notification tapped, a call taking over the screen, or a link opened or shared into the app. */
    private fun handle(i: Intent?, cold: Boolean): Boolean {
        i ?: return false
        var url = i.getStringExtra("url")
        val tag = i.getStringExtra("tag")
        val how = i.getStringExtra("how")
        val kind = i.getStringExtra("kind")
        if (url == null && i.action == Intent.ACTION_VIEW) url = i.dataString
        if (url == null && i.action == Intent.ACTION_SEND) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            url = Regex("https://\\S+").find(text)?.value
            /* No link in the shared text, but the invitation's own 10-letter
               code (read out over the phone, say): the page opens it the same. */
            if (url == null) {
                val code = Regex("\\b[2-9A-HJ-NP-Z]{10}\\b").find(
                    text.uppercase().replace(Regex("(?<=[2-9A-Z])[\\s\\-](?=[2-9A-Z])"), ""))?.value
                if (code != null) url = BuildConfig.APP_URL + "#i=" + code
            }
        }

        if (tag != null && (kind == "call" || kind == "group")) {
            /* Over the lock screen, like the phone's own incoming call. */
            showOverLock(true)
            if (how != HOW_FULL) {
                /* Picked up (or opened): the ringing stops here, on the server,
                   and on this person's other phones. */
                Notes.stopRing(this, tag)
                Store.silence(this, tag)
                Net.ringStop(this, tag)
            }
        }
        if (url.isNullOrBlank() || !allowed(url)) return false
        if (cold || !pageReady) web.loadUrl(url)
        else web.evaluateJavascript("window.familyOpen && familyOpen(${JSONObject.quote(url)})", null)
        return true
    }

    @Suppress("DEPRECATION")
    private fun showOverLock(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(f) else window.clearFlags(f)
        }
    }

    /** From FamilyAudioBridge: a call has started or ended. */
    fun callActive(on: Boolean) = runOnUiThread {
        inCall = on
        if (on) {
            if (granted(Manifest.permission.RECORD_AUDIO)) CallService.start(this)
        } else {
            CallService.stop(this)
            showOverLock(false)
            if (!Store.foreground) web.onPause()
        }
    }

    /* ---------------------------------------------------------------- notifications */

    private fun fetchToken() {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (!t.isSuccessful) return@addOnCompleteListener
                val tok = t.result ?: return@addOnCompleteListener
                Store.setToken(this, tok)
                js("window.familyPushToken && familyPushToken()")
            }
        } catch (_: Exception) {}
    }

    /** The first time: the microphone and notifications, then full-screen calls. */
    private fun firstRun() {
        if (Store.flag(this, "askedStart")) return
        Store.setFlag(this, "askedStart")
        val want = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) { want += Manifest.permission.POST_NOTIFICATIONS; Store.setFlag(this, "askedNotify") }
        atStart.launch(want.toTypedArray())
    }

    /** "ok" when a ringing call can fill a locked phone's screen; "off" when
        Android 14+ is waiting for the person to allow it. */
    fun fullScreenState(): String =
        if (Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) "ok" else "off"

    /** The page's "Allow full-screen calls" button: straight to the phone's setting. */
    fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < 34) return
        recheckPush = true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } catch (_: Exception) {}
    }

    /** Android 14+ lets calling apps fill the screen only once the person allows it. */
    private fun askFullScreen() {
        if (Build.VERSION.SDK_INT < 34 || Store.flag(this, "askedFsi")) return
        if (fullScreenState() == "ok") return
        Store.setFlag(this, "askedFsi")
        AlertDialog.Builder(this)
            .setTitle(R.string.fsi_title)
            .setMessage(R.string.fsi_body)
            .setPositiveButton(R.string.fsi_go) { _, _ -> openFullScreenSettings() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    fun notifState(): String = when {
        NotificationManagerCompat.from(this).areNotificationsEnabled() -> "granted"
        Store.flag(this, "askedNotify") -> "denied"
        else -> "default"
    }

    /** The page's "turn on notifications" button. */
    fun askNotify() {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            js("window.familyNotifyResult && familyNotifyResult(true)"); return
        }
        val canAsk = Build.VERSION.SDK_INT >= 33 &&
            (!Store.flag(this, "askedNotify") || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        if (canAsk) {
            Store.setFlag(this, "askedNotify")
            forNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            /* Refused before: only the phone's settings can turn them on now. */
            recheckPush = true
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) {}
        }
    }

    /* ---------------------------------------------------------------- files */

    /** A picture or file from a chat, into Downloads (the WebView can't save blob: links). */
    fun saveFile(name: String, mime: String, b64: String): String = try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("no uri")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            File(dir, safe).writeBytes(bytes)
        }
        "ok"
    } catch (_: Exception) { "" }
}

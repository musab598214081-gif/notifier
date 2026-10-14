package app.family.notifyer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.JavascriptInterface
import java.util.concurrent.Executor

/**
 * window.FamilyAudio: real earpiece / loudspeaker routing for calls, which
 * Chrome gives web pages no way to do. The page calls setRoute (see
 * nativeAudio() in index.html):
 *
 *   "earpiece"  every call starts here - private, like the phone's dialler
 *   "speaker"   only when the person taps Speaker
 *   "off"       the call is over: the phone goes back to normal
 *
 * It answers with where the sound now goes ("earpiece", "speaker",
 * "headset", "bluetooth"), and tells the page by itself when that changes
 * (headphones plugged in or out) through window.familyAudioRoute.
 *
 * The WebView's own call audio (Chrome's WebRTC) picks the loudspeaker by
 * itself whenever no headset is in, and can do it after the page chose
 * the earpiece - a "Phone" call heard by the whole room. So for as long
 * as a call lasts the chosen mode is held: any change of output made by
 * somebody else is undone at once (Android 12+), or within a second
 * (older phones), and the volume keys turn the call itself up and down.
 */
class FamilyAudioBridge(private val host: MainActivity) {

    private val am = host.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val pm = host.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val main = Handler(Looper.getMainLooper())

    /* The screen goes dark while the phone is held to the ear, as in the dialler. */
    private val proximity: PowerManager.WakeLock? =
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
            pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "family:call-ear")
        else null

    @Volatile private var wanted = "off"

    /* The call has the phone's sound to itself, as a phone call does:
       music or a video pauses, other apps' sounds duck, and they come
       back when the call ends. */
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ }, main)
        .build()
    private var focused = false
    /* Where this bridge last put the sound, to tell its own changes from others'. */
    @Volatile private var placed = ""
    private var guardSince = 0L
    private var guardCount = 0

    /* Android 12+: told whenever the call's output device changes. */
    private val commListener: Any? = if (Build.VERSION.SDK_INT >= 31)
        AudioManager.OnCommunicationDeviceChangedListener { d -> guard(d) } else null

    /* Older phones have no such news: checked every second during a call. */
    private val poll = object : Runnable {
        override fun run() {
            if (wanted == "off") return
            @Suppress("DEPRECATION")
            val loudNow = am.isSpeakerphoneOn
            if ((placed == "speaker") != loudNow || am.mode != AudioManager.MODE_IN_COMMUNICATION) reapply()
            main.postDelayed(this, 1000)
        }
    }

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reapply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reapply()
    }

    init {
        am.registerAudioDeviceCallback(devices, main)
        if (Build.VERSION.SDK_INT >= 31)
            am.addOnCommunicationDeviceChangedListener(Executor { main.post(it) },
                commListener as AudioManager.OnCommunicationDeviceChangedListener)
    }

    @JavascriptInterface
    fun setRoute(route: String): String {
        val was = wanted
        wanted = if (route == "speaker" || route == "earpiece") route else "off"
        val where = synchronized(this) { applyRoute() }
        if ((was == "off") != (wanted == "off")) host.callActive(wanted != "off")
        main.removeCallbacks(poll)
        if (wanted != "off") {
            if (Build.VERSION.SDK_INT < 31) main.postDelayed(poll, 1000)
            /* The WebView sets up its call audio a moment after the page asks:
               the mode is put back once that has happened, whatever it chose. */
            main.postDelayed({ reapply() }, 400)
            main.postDelayed({ reapply() }, 1500)
        }
        host.runOnUiThread {
            host.volumeControlStream = if (wanted == "off") AudioManager.USE_DEFAULT_STREAM_TYPE
                                       else AudioManager.STREAM_VOICE_CALL
        }
        return where
    }

    fun release() {
        wanted = "off"
        main.removeCallbacks(poll)
        synchronized(this) { applyRoute() }
        am.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= 31)
            am.removeOnCommunicationDeviceChangedListener(commListener as AudioManager.OnCommunicationDeviceChangedListener)
    }

    /** Android 12+: the call's output moved. Somebody else's doing (the
        WebView, usually) is undone; a headset coming or going is reapply's. */
    private fun guard(d: AudioDeviceInfo?) {
        if (wanted == "off") return
        val now = when (d?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            null -> ""
            else -> "headset"
        }
        if (now == placed) return
        /* Never a tug of war: at most a few corrections in a short while. */
        val t = System.currentTimeMillis()
        if (t - guardSince > 3000) { guardSince = t; guardCount = 0 }
        if (++guardCount > 6) return
        reapply()
    }

    private fun applyRoute(): String {
        if (wanted == "off") {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else legacy(false)
            am.mode = AudioManager.MODE_NORMAL
            if (focused) { try { am.abandonAudioFocusRequest(focus) } catch (_: Exception) {}; focused = false }
            proximityOn(false)
            placed = ""
            return ""
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (!focused) focused = try { am.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED } catch (_: Exception) { false }

        /* Headphones always win, in either mode - as in a phone call. */
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val wired = outs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val loud = wanted == "speaker"
        val where = when {
            !loud && wired != null -> { route(wired, false); "headset" }
            !loud && bt != null -> { route(bt, false); "bluetooth" }
            loud -> { route(outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }, true); "speaker" }
            else -> { route(outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }, false); "earpiece" }
        }
        proximityOn(where == "earpiece")
        placed = if (where == "bluetooth") "headset" else where
        return where
    }

    private fun route(device: AudioDeviceInfo?, speaker: Boolean) {
        if (Build.VERSION.SDK_INT >= 31 && device != null) {
            if (!am.setCommunicationDevice(device)) legacy(speaker)
        } else legacy(speaker)
    }

    @Suppress("DEPRECATION")
    private fun legacy(speaker: Boolean) { am.isSpeakerphoneOn = speaker }

    /* Something changed underneath - headphones in or out, or the WebView
       re-routing on its own: the chosen mode is put back, and the page told. */
    private fun reapply() {
        if (wanted == "off") return
        val before = placed
        val where = synchronized(this) { applyRoute() }
        if (placed != before) MainActivity.js("window.familyAudioRoute && window.familyAudioRoute('$where')")
    }

    private fun proximityOn(on: Boolean) {
        val lock = proximity ?: return
        try {
            if (on && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
            else if (!on && lock.isHeld) lock.release()
        } catch (_: Exception) {}
    }
}

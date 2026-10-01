package app.family.notifyer

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.webkit.JavascriptInterface

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

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reapply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reapply()
    }

    init { am.registerAudioDeviceCallback(devices, main) }

    @JavascriptInterface
    fun setRoute(route: String): String {
        val was = wanted
        wanted = if (route == "speaker" || route == "earpiece") route else "off"
        val where = synchronized(this) { applyRoute() }
        if ((was == "off") != (wanted == "off")) host.callActive(wanted != "off")
        return where
    }

    fun release() {
        wanted = "off"
        synchronized(this) { applyRoute() }
        am.unregisterAudioDeviceCallback(devices)
    }

    private fun applyRoute(): String {
        if (wanted == "off") {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else legacy(false)
            am.mode = AudioManager.MODE_NORMAL
            proximityOn(false)
            return ""
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION

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
        val where = synchronized(this) { applyRoute() }
        MainActivity.js("window.familyAudioRoute && window.familyAudioRoute('$where')")
    }

    private fun proximityOn(on: Boolean) {
        val lock = proximity ?: return
        try {
            if (on && !lock.isHeld) lock.acquire(4 * 60 * 60 * 1000L)
            else if (!on && lock.isHeld) lock.release()
        } catch (_: Exception) {}
    }
}

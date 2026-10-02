package app.family.notifyer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * The phone's own sounds, for the page while the app is on screen (see
 * FamilyAppBridge.ring / note). With the app closed, Notes.kt does the same
 * through the notification channels.
 *
 *  - an incoming call: the ringtone chosen in Sound & vibration, on the
 *    ring stream (ring volume), looping; vibration handed to the phone as
 *    USAGE_RINGTONE so its "vibrate for calls" setting applies; nothing on
 *    silent, and nothing under Do Not Disturb;
 *  - everything else: the phone's notification sound, once, at the
 *    notification volume.
 *
 * Nothing here sets a volume or chooses a tone. The URIs are the symbolic
 * "current default" ones, so a ringtone changed in the phone's settings is
 * used from the next call on.
 */
object FamilySounds {

    enum class Mode { LOUD, VIBRATE, SILENT }

    fun mode(c: Context): Mode {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = c.getSystemService(NotificationManager::class.java)
        when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> return Mode.SILENT
        }
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Mode.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> Mode.VIBRATE
            else -> if (am.getStreamVolume(AudioManager.STREAM_RING) == 0) Mode.VIBRATE else Mode.LOUD
        }
    }

    /* ---- an incoming call ringing on screen, until told to stop ---- */

    object CallRinger {
        private var tone: Ringtone? = null
        private var tag = ""
        private var vibrating = false

        /** "ok" when ringing out loud; "silent" when the phone's own setting
            keeps it quiet (it may still vibrate). The same tag again keeps
            the one ring going. */
        @Synchronized
        fun start(c: Context, forTag: String): String {
            val m = mode(c)
            if (tone != null && tag == forTag) return if (m == Mode.LOUD) "ok" else "silent"
            stop(c)
            tag = forTag
            if (m == Mode.LOUD) {
                RingtoneManager.getRingtone(c.applicationContext, Settings.System.DEFAULT_RINGTONE_URI)?.let { r ->
                    r.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = true
                    try { r.play(); tone = r } catch (_: Exception) {}
                }
            }
            if (m != Mode.SILENT) vibrate(c)
            return if (tone != null) "ok" else "silent"
        }

        @Synchronized
        fun stop(c: Context) {
            try { tone?.stop() } catch (_: Exception) {}
            tone = null
            tag = ""
            if (vibrating) { try { vibrator(c)?.cancel() } catch (_: Exception) {}; vibrating = false }
        }

        private fun vibrate(c: Context) {
            val v = vibrator(c) ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && mode(c) == Mode.LOUD) {
                /* Older phones: "Vibrate when ringing" is a system setting. */
                val want = try { Settings.System.getInt(c.contentResolver, "vibrate_when_ringing", 1) != 0 }
                           catch (_: Exception) { true }
                if (!want) return
            }
            val pattern = longArrayOf(0, 1000, 1000)
            vibrating = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                          VibrationAttributes.createForUsage(VibrationAttributes.USAGE_RINGTONE))
            else
                @Suppress("DEPRECATION")
                v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                          AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
        }
    }

    /* ---- the phone's notification sound, once ---- */

    fun note(c: Context): String {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            am.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0) return "silent"
        val r = RingtoneManager.getRingtone(c.applicationContext, Settings.System.DEFAULT_NOTIFICATION_URI)
            ?: return "silent"
        r.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.isLooping = false
        try { r.play() } catch (_: Exception) { return "silent" }
        return "ok"
    }

    /* ---- the phone's notification settings for this app ---- */

    fun openSettings(c: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { c.startActivity(i) } catch (_: Exception) {}
    }

    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else
            @Suppress("DEPRECATION") c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

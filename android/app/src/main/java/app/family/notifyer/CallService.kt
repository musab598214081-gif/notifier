package app.family.notifyer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Runs for the length of a call, so Android keeps the microphone and the
    sound going when you switch to another app or the screen locks. */
class CallService : Service() {
    companion object {
        @Volatile var running = false
            private set

        fun start(c: Context) {
            if (running) return
            try { ContextCompat.startForegroundService(c, Intent(c, CallService::class.java)) } catch (_: Exception) {}
        }
        fun stop(c: Context) {
            if (!running) return
            c.stopService(Intent(c, CallService::class.java))
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    /* With the screen dark at your ear, Wi-Fi drops into power saving and
       the phone's processor naps - the commonest cause of a voice that
       stutters, lags or drops out. For the length of the call: Wi-Fi at
       full speed and low latency, and the processor awake. */
    private var wifi: WifiManager.WifiLock? = null
    private var cpu: PowerManager.WakeLock? = null

    private fun holdOn() {
        try {
            if (wifi == null) {
                val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                           else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "family:call").apply { setReferenceCounted(false); acquire() }
            }
        } catch (_: Exception) {}
        try {
            if (cpu == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                cpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "family:call-cpu").apply {
                    setReferenceCounted(false); acquire(4 * 60 * 60 * 1000L)
                }
            }
        } catch (_: Exception) {}
    }

    private fun letGo() {
        try { wifi?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { cpu?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifi = null; cpu = null
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, Notes.ID_ONGOING, Notes.ongoing(this), type)
            running = true
            holdOn()
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        letGo()
        running = false
        super.onDestroy()
    }
}

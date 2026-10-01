package app.family.notifyer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, Notes.ID_ONGOING, Notes.ongoing(this), type)
            running = true
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}

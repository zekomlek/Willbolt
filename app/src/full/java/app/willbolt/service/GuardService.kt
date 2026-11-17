package app.willbolt.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.willbolt.core.Policy
import app.willbolt.core.SelfRepair
import app.willbolt.core.TimeGuard
import app.willbolt.core.Watchdog
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.data.UsageStore
import app.willbolt.util.Notifications

/**
 * Foreground service that hosts the watchdog: observers on system settings plus a 30-second tick. It only runs while
 * something is locked (the uninstall protection is held by a lock, so that includes it) - that is when it puts back what
 * gets switched off - so with nothing locked Willbolt has no background service and no notification at all.
 *
 * Full edition only. The Play edition has an empty [GuardService.start]; there the accessibility service's own heartbeat
 * and the 15-minute WatchdogWorker do the housekeeping.
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "GuardService"
        private const val NOTIF_ID = 1001

        /** Starts the guard if something is locked; otherwise there is nothing for it to do. */
        fun start(ctx: Context) {
            try {
                ConfigStore.init(ctx)
                if (!Policy.anyLocked(ConfigStore.value)) return
                ContextCompat.startForegroundService(ctx, Intent(ctx, GuardService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "could not start right now: $t")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        EventLog.init(this)
        UsageStore.init(this)
        Notifications.ensureChannels(this)
        if (!goForeground()) {
            stopSelf()
            return
        }
        SelfRepair.watch(this)
        handler.post(ticker)
    }

    /**
     * Android wants every startForegroundService() answered with startForeground() within about ten seconds, and ends the
     * whole app if it is not (ForegroundServiceDidNotStartInTimeException). That happened twice on 2 November - once when
     * Willbolt was opened, once when the accessibility service connected - and with the process gone nothing was blocked
     * for two hours. So it is answered on every start, also when the service was already running, and when Android will
     * not allow it the service ends itself instead of leaving Android waiting.
     */
    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIF_ID, Notifications.foreground(this, Notifications.statusLine()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST,
        )
        true
    } catch (t: Throwable) {
        Log.w(TAG, "startForeground failed", t)
        false
    }

    private fun tick() {
        TimeGuard.tick()
        Policy.housekeeping()
        UsageStore.prune(ConfigStore.value.rules.map { it.id }.toSet())
        Watchdog.checkAll(this)
        if (!Policy.anyLocked(ConfigStore.value)) {
            // The last lock has opened.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID, Notifications.foreground(this, Notifications.statusLine()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        SelfRepair.unwatch(this)
        super.onDestroy()
    }
}

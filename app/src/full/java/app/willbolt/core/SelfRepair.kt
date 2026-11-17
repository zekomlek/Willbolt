package app.willbolt.core

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.service.GuardAccessibilityService

/**
 * The full edition's self-repair. With WRITE_SECURE_SETTINGS (granted once over USB) it turns Willbolt's own accessibility
 * service back on the moment the phone, a battery manager or a person switches it off, keeps automatic time on, and undoes
 * Private-DNS changes that would bypass the website filter. Without that permission Home can only show what is wrong.
 * The Play edition does none of this.
 */
object SelfRepair {
    private const val TAG = "SelfRepair"

    /** How long [settle] gives the system to connect the accessibility service before it is switched off and on. */
    private const val SETTLE_MS = 12_000L

    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var appCtx: Context? = null
    private var lastKick = 0L
    private val delayedCheck = Runnable { appCtx?.let { Watchdog.checkAll(it) } }

    fun available(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Returns true if the system setting now lists the service. */
    fun enableAccessibility(ctx: Context): Boolean {
        if (!available(ctx)) return false
        return try {
            val comp = Watchdog.a11yComponent(ctx)
            val cur = Watchdog.enabledList(ctx)
            if (cur.none { ComponentName.unflattenFromString(it) == comp }) {
                Settings.Secure.putString(
                    ctx.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (cur + comp.flattenToString()).joinToString(":"),
                )
            }
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "enableAccessibility failed", t)
            false
        }
    }

    /** Off-and-on again, for a service that is "enabled" but whose process never connected. */
    private fun kickAccessibility(ctx: Context) {
        try {
            val comp = Watchdog.a11yComponent(ctx)
            val without = Watchdog.enabledList(ctx).filter { ComponentName.unflattenFromString(it) != comp }
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without.joinToString(":"))
            main.postDelayed({ enableAccessibility(ctx) }, 700)
            EventLog.add("HEAL", "Accessibility service was enabled but not running - restarted it", dedupeMs = 60_000)
        } catch (t: Throwable) {
            Log.w(TAG, "kick failed", t)
        }
    }

    /**
     * For a process that was started only to take a look (the 15-minute worker): if the accessibility service is switched on
     * in Android's settings but never connects - what a crash leaves behind, the system does not bind it again by itself -
     * give it a few seconds, then switch it off and on, which does. [heal] cannot: it waits for a process that is old
     * enough, and a worker's never is. Blocks for up to [SETTLE_MS] (only ever called on a background thread).
     */
    fun settle(ctx: Context) {
        if (!available(ctx) || !Policy.anyLocked(ConfigStore.value) || !Watchdog.isAccessibilityEnabledInSettings(ctx)) return
        val deadline = SystemClock.elapsedRealtime() + SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (GuardAccessibilityService.instance != null) return
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return
            }
        }
        if (GuardAccessibilityService.instance == null) {
            lastKick = SystemClock.elapsedRealtime()
            kickAccessibility(ctx)
            try {
                Thread.sleep(1_500) // the service is switched back on 700 ms later; let that happen before the process may end
            } catch (_: InterruptedException) {
            }
        }
    }

    /** Puts back what was switched off. Called by [Watchdog.checkAll] while something is locked. */
    fun heal(ctx: Context, cfg: Config, now: Long) {
        if (!available(ctx)) return
        if (!Watchdog.isAccessibilityEnabledInSettings(ctx)) {
            if (enableAccessibility(ctx)) {
                EventLog.add("HEAL", "Accessibility service was switched off - switched it back on", dedupeMs = 10_000)
            }
        } else if (GuardAccessibilityService.instance == null) {
            val t = SystemClock.elapsedRealtime()
            val processAge = t - Process.getStartElapsedRealtime()
            if (t - lastKick > 120_000 && processAge > 20_000) {
                lastKick = t
                kickAccessibility(ctx)
            }
        }
        if (cfg.closeDnsLoopholes && Policy.dnsKeptOn(cfg, now)) healPrivateDns(ctx)
        healAutoTime(ctx)
    }

    private fun healPrivateDns(ctx: Context) {
        val cr = ctx.contentResolver
        val mode = Settings.Global.getString(cr, "private_dns_mode")
        if (mode == "hostname") {
            // A fixed Private-DNS server is resolved outside the VPN and would skip the filter.
            Settings.Global.putString(cr, "private_dns_mode", "opportunistic")
            EventLog.add("HEAL", "Private DNS was pointed at a custom server - set back to Automatic", dedupeMs = 10_000)
        }
    }

    private fun healAutoTime(ctx: Context) {
        val cr = ctx.contentResolver
        if (Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 1) != 1) {
            Settings.Global.putInt(cr, Settings.Global.AUTO_TIME, 1)
            EventLog.add("HEAL", "Automatic date & time was off - switched it back on", dedupeMs = 10_000)
        }
    }

    // ---- observers ---------------------------------------------------------------------------------------

    fun watch(ctx: Context) {
        if (observer != null) return
        val app = ctx.applicationContext
        appCtx = app
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(delayedCheck)
                main.postDelayed(delayedCheck, 250)
            }
        }
        try {
            val cr = app.contentResolver
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, obs)
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor("private_dns_mode"), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor("private_dns_specifier"), false, obs)
            cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.AUTO_TIME), false, obs)
            observer = obs
        } catch (t: Throwable) {
            Log.w(TAG, "could not register observers", t)
        }
    }

    fun unwatch(ctx: Context) {
        observer?.let {
            try {
                ctx.applicationContext.contentResolver.unregisterContentObserver(it)
            } catch (_: Throwable) {
            }
        }
        observer = null
    }
}

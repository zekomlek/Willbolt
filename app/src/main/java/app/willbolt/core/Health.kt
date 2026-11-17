package app.willbolt.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import app.willbolt.Edition
import app.willbolt.admin.GuardAdminReceiver
import app.willbolt.data.ConfigStore
import app.willbolt.ml.NsfwClassifier
import app.willbolt.service.GuardAccessibilityService
import app.willbolt.vpn.DnsVpnService

data class Check(
    val id: String,
    val title: String,
    val detail: String,
    val ok: Boolean,
    /** Critical problems turn the status red, and trigger an alert while something is locked. */
    val critical: Boolean,
    val actionLabel: String? = null,
)

object Health {
    @Volatile
    var unusedRestrictionsStatus: Int = -1
        private set

    /** Asks Android whether "pause app activity if unused" applies to us. Result lands in [unusedRestrictionsStatus]. */
    fun refreshAsync(ctx: Context) {
        try {
            val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx)
            future.addListener({
                try {
                    unusedRestrictionsStatus = future.get()
                } catch (_: Throwable) {
                }
            }, ContextCompat.getMainExecutor(ctx))
        } catch (_: Throwable) {
        }
    }

    fun adminComponent(ctx: Context) = ComponentName(ctx, GuardAdminReceiver::class.java)

    fun isAdminActive(ctx: Context): Boolean =
        ctx.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(adminComponent(ctx)) == true

    fun run(ctx: Context): List<Check> {
        val cfg = ConfigStore.value
        val out = ArrayList<Check>()

        val a11yRunning = GuardAccessibilityService.instance != null
        val a11yEnabled = Watchdog.isAccessibilityEnabledInSettings(ctx)
        out += Check(
            "accessibility", "Accessibility service",
            when {
                a11yRunning -> "Running"
                a11yEnabled -> "Enabled, but not running yet"
                else -> "Off - nothing is blocked or checked until this is on"
            },
            ok = a11yRunning, critical = true, actionLabel = if (a11yRunning) null else "Turn on",
        )

        // Optional, and never a "needs attention": the uninstall-protection card on Home (and Settings) carries it, with
        // its own Activate. In the full edition Willbolt closes its own Settings screens when you set that up, but only
        // this makes Android itself refuse to force-stop, clear or uninstall it.
        val admin = isAdminActive(ctx)
        out += Check(
            "admin", if (Edition.FULL) "Uninstall protection" else "Device administrator",
            when {
                !Edition.FULL && admin -> "On - Android asks you to turn this off before Willbolt can be uninstalled"
                !Edition.FULL -> "Optional - Android asks you to turn it off first, a small speed bump"
                admin -> "Device administrator on - Android's Settings will not force-stop, clear or uninstall Willbolt"
                else -> "Device administrator off - Android's Settings can force-stop, clear or uninstall Willbolt"
            },
            ok = admin, critical = false, actionLabel = if (admin) null else "Activate",
        )

        if (Watchdog.wantsDnsFilter(cfg)) {
            val running = DnsVpnService.running
            val bypassed = running && DnsVpnService.bypassedByPrivateDns
            val detail = when {
                bypassed -> "Running, but Private DNS is set to a fixed server so apps skip the filter. Set Private DNS to Automatic (Settings > Network)."
                running && !DnsVpnService.verified -> "Starting - checking that lookups flow through it..."
                running -> "Running - blocked sites do not load in any app"
                DnsVpnService.pausedAfterFailure() ->
                    "Paused: it failed its self-test and switched itself off to keep your internet working. It retries every 10 minutes."
                else -> "Off - only the browser's address bar is checked, and safe search is not applied"
            }
            val ok = running && !bypassed
            out += Check(
                "vpn", "Website filter (DNS)", detail, ok = ok, critical = Policy.dnsKeptOn(cfg),
                actionLabel = when {
                    ok -> null
                    bypassed -> "Open settings"
                    DnsVpnService.pausedAfterFailure() -> "Retry now"
                    else -> "Start"
                },
            )
        }

        val pm = ctx.getSystemService(PowerManager::class.java)
        val battery = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        out += Check(
            "battery", "Battery: no restrictions",
            if (battery) "No restrictions" else "The phone may stop Willbolt in the background",
            ok = battery, critical = false, actionLabel = if (battery) null else "Allow",
        )

        val st = unusedRestrictionsStatus
        val hibernationOk = st == -1 || st == UnusedAppRestrictionsConstants.ERROR ||
            st == UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE ||
            st == UnusedAppRestrictionsConstants.DISABLED
        out += Check(
            "hibernation", "Keep permissions when unused",
            if (hibernationOk) "Android will not remove Willbolt's permissions" else
                "Android may reset Willbolt's permissions if you don't open it for a while - this is the usual cause of 'it turned itself off'",
            ok = hibernationOk, critical = false, actionLabel = if (hibernationOk) null else "Fix",
        )

        if (Oem.needsManualStep) {
            // What the phone really did counts for more than a tick: the maker's manager stops an app that is not set to keep
            // running, and Android keeps a record of it. After a stop, blocking is off until Willbolt is opened again.
            val stops = Oem.stops(ctx, cfg)
            val last = stops.lastOrNull()
            out += Check(
                "oem", "${Oem.makerName} battery manager",
                when {
                    last != null ->
                        "It stopped Willbolt ${if (stops.size == 1) "once" else "${stops.size} times"}, last at ${Gaps.at(last.at)}, " +
                            "and nothing was blocked until Willbolt was opened again. " +
                            if (Oem.isHonorOrHuawei) "Keep all three App launch switches on for Willbolt."
                            else "Let Willbolt run in the background there."
                    cfg.oemStepsDone -> "You marked this as done"
                    else -> "Your phone's own battery manager needs one manual change"
                },
                ok = last == null && cfg.oemStepsDone,
                // Red only when it is costing protection that matters: something is locked and the phone is switching Willbolt off.
                critical = last != null && Policy.anyLocked(cfg),
                actionLabel = if (last == null && cfg.oemStepsDone) null else "Show steps",
            )
        }

        out += Edition.extraChecks(ctx)

        val autoTime = try {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AUTO_TIME, 1) == 1
        } catch (_: Throwable) {
            true
        }
        out += Check(
            "autotime", "Automatic date & time",
            if (autoTime) "On" else "Off - changing the clock could dodge schedules",
            ok = autoTime, critical = false, actionLabel = if (autoTime) null else "Open settings",
        )

        val scanOk = Build.VERSION.SDK_INT >= 30 && NsfwClassifier.isAvailable(ctx)
        out += Check(
            "scanner", "Live screen check",
            when {
                Build.VERSION.SDK_INT < 30 -> "Needs Android 11 or newer"
                !NsfwClassifier.isAvailable(ctx) -> "Detection model is missing from this build"
                else -> "Ready (on-device model)"
            },
            ok = scanOk, critical = false,
        )
        return out
    }
}

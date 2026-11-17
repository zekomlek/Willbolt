package app.willbolt.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.willbolt.data.Config

/**
 * Phone makers add their own "battery managers" on top of Android that quietly stop background apps or
 * take permissions away. Each one hides the switch in a different place, so this collects the usual
 * screens plus plain-language steps.
 */
object Oem {
    private val maker = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()

    val isHonorOrHuawei = "honor" in maker || "huawei" in maker
    val isXiaomi = "xiaomi" in maker || "redmi" in maker || "poco" in maker
    val isOppoFamily = "oppo" in maker || "realme" in maker || "oneplus" in maker
    val isVivo = "vivo" in maker || "iqoo" in maker
    val isSamsung = "samsung" in maker

    /** Makers whose battery manager is known to need a manual step. */
    val needsManualStep get() = isHonorOrHuawei || isXiaomi || isOppoFamily || isVivo || isSamsung

    val displayName: String
        get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL

    /** "Honor", "Samsung". */
    val makerName: String
        get() = Build.MANUFACTURER.lowercase().replaceFirstChar { it.uppercase() }

    /** How long a stop by the maker's manager keeps its battery screens reachable (see [stoppingUs]). */
    const val FIX_WINDOW_MS = 2 * 60 * 60 * 1000L

    /** How far back Home looks for stops by the maker's manager. */
    private const val HISTORY_MS = 3 * 24 * 60 * 60 * 1000L

    /**
     * The times the phone maker's battery manager stopped Willbolt in the last three days, since the setup was last ticked as
     * done. Honor's PowerGenie does it to any app that is not set to keep running, as soon as the screen has been off a
     * while: it is the proof that "Run in background" is not on, whatever was ticked.
     */
    fun stops(ctx: Context, cfg: Config, now: Long = System.currentTimeMillis()): List<Gaps.Stop> =
        Gaps.stoppedLately(Gaps.makerStops(ctx), now, cfg.oemDoneMs, HISTORY_MS)

    /**
     * The maker's manager is stopping Willbolt right now (it did within the last two hours, since you last ticked the setup as
     * done). Then the screens where that is fixed must not be closed by the uninstall guard: blocking them would only leave
     * Willbolt switched off.
     */
    fun stoppingUs(ctx: Context, cfg: Config, now: Long = System.currentTimeMillis()): Boolean =
        Gaps.stoppedLately(Gaps.makerStops(ctx), now, cfg.oemDoneMs, FIX_WINDOW_MS).isNotEmpty()

    fun steps(): List<String> = when {
        isHonorOrHuawei -> listOf(
            "Open Settings > Battery > App launch (on some versions: Phone Manager > App launch).",
            "Find Willbolt and switch OFF \"Manage automatically\".",
            "In the pop-up switch ON all three: Auto-launch, Secondary launch and Run in background. Tap OK.",
            "Open the recent-apps screen and lock Willbolt's card (swipe the card down, or tap its lock icon) so \"clear all\" cannot close it.",
            "Settings > Apps > Apps > Willbolt > Battery: choose Unrestricted / allow background activity.",
            "Settings > Battery: make sure Power saving and Ultra power saving are off.",
        )
        isXiaomi -> listOf(
            "Settings > Apps > Manage apps > Willbolt > Autostart: ON.",
            "Same page > Battery saver: choose No restrictions.",
            "Open recent apps, long-press Willbolt's card and tap the lock icon.",
        )
        isOppoFamily -> listOf(
            "Settings > Apps > App management > Willbolt > Battery usage: allow background activity and auto-launch.",
            "Open recent apps and lock Willbolt's card.",
        )
        isVivo -> listOf(
            "Settings > Battery > Background power consumption > Willbolt: Allow high background power use.",
            "Settings > Apps > Autostart: allow Willbolt.",
        )
        isSamsung -> listOf(
            "Settings > Battery > Background usage limits: remove Willbolt from Sleeping / Deep sleeping apps and turn off \"Put unused apps to sleep\".",
            "Settings > Apps > Willbolt > Battery: choose Unrestricted.",
        )
        else -> listOf(
            "Settings > Apps > Willbolt > Battery: choose Unrestricted.",
            "If your phone has its own \"auto-start\" or \"app launch\" manager, allow Willbolt there.",
        )
    }

    private fun comp(pkg: String, cls: String) =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun candidates(ctx: Context): List<Intent> {
        val list = ArrayList<Intent>()
        if (isHonorOrHuawei) {
            list += comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            list += comp("com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity")
            list += comp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            list += comp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            list += comp("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        }
        if (isXiaomi) list += comp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        if (isOppoFamily) {
            list += comp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            list += comp("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            list += comp("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        }
        if (isVivo) list += comp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        if (isSamsung) list += comp("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
        list += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        list += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        return list
    }

    /** Opens the most specific battery / auto-start screen it can. Returns false if none opened. */
    fun openBackgroundSettings(ctx: Context): Boolean {
        for (intent in candidates(ctx)) {
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Throwable) {
                // not exported / not present on this phone - try the next one
            }
        }
        return false
    }
}

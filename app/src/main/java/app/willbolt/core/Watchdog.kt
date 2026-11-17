package app.willbolt.core

import android.content.ComponentName
import android.content.Context
import android.net.VpnService
import android.provider.Settings
import android.util.Log
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.RuleMode
import app.willbolt.service.GuardAccessibilityService
import app.willbolt.vpn.DnsVpnService

/**
 * Keeps protection switched on: brings back the website filter you started after a reboot or a crash, and reads whether
 * the accessibility service is on.
 *
 * What goes further - turning the accessibility service back on, undoing Private-DNS and automatic-time changes - needs
 * a permission only the full edition has, and lives in [SelfRepair]. In the Play edition Home can only show what is wrong.
 */
object Watchdog {
    private const val TAG = "Watchdog"

    fun a11yComponent(ctx: Context) = ComponentName(ctx, GuardAccessibilityService::class.java)

    /**
     * Something needs the website filter (DNS) to be running. Safe search only counts once you have started the filter -
     * it is an option of the filter, not a reason to start one, so a new install with everything off asks for nothing.
     */
    fun wantsDnsFilter(cfg: Config) =
        cfg.adultDomainList || (cfg.safeSearch && cfg.webFilterOn) ||
            cfg.rules.any { it.mode == RuleMode.BLOCK && it.domains.isNotEmpty() }

    // ---- accessibility ----------------------------------------------------------------------------

    internal fun enabledList(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }

    fun isAccessibilityEnabledInSettings(ctx: Context): Boolean {
        val on = Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!on) return false
        val comp = a11yComponent(ctx)
        return enabledList(ctx).any { ComponentName.unflattenFromString(it) == comp }
    }

    // ---- main check ----------------------------------------------------------------------------------

    fun checkAll(ctx: Context) {
        try {
            ConfigStore.init(ctx)
            val cfg = ConfigStore.value
            val now = TimeGuard.now()
            // The DNS filter you started comes back after a reboot or a crash, locked or not.
            ensureVpn(ctx, cfg)
            // With nothing locked, switching things off is allowed.
            if (!Policy.anyLocked(cfg, now)) return
            SelfRepair.heal(ctx, cfg, now)
        } catch (t: Throwable) {
            Log.w(TAG, "checkAll failed", t)
        }
    }

    private fun ensureVpn(ctx: Context, cfg: Config) {
        if (!cfg.webFilterOn || !wantsDnsFilter(cfg) || DnsVpnService.running || DnsVpnService.pausedAfterFailure()) return
        if (VpnService.prepare(ctx) != null) return // the user has not approved the VPN yet (or another VPN took over)
        DnsVpnService.start(ctx)
    }
}

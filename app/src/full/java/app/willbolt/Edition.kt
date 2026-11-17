package app.willbolt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.willbolt.core.Check
import app.willbolt.core.SelfRepair

/**
 * The full edition: everything, for installs from GitHub. The Play edition (`src/play`) has the same names with less
 * behind them, because Play does not allow an app to guard its own uninstall or write system settings.
 */
object Edition {
    const val NAME = "Full edition"
    const val FULL = true

    /** The system dialog that exempts Willbolt from battery optimisation in one tap. */
    fun batteryIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    /** Shown after tapping the battery fix, when the edition can only open a list. */
    val batteryHint: String? = null

    /** Said under the lock form: what a lock covers beyond Willbolt's own settings, and what still gets you out. */
    const val LOCK_NOTE =
        "A lock guards Willbolt's own settings. To keep Android's Settings from stopping or removing Willbolt too, " +
            "switch on Uninstall protection above. Safe Mode or a USB cable still get you out."

    /** Setup rows that only this edition has. */
    fun extraChecks(ctx: Context): List<Check> {
        val secure = SelfRepair.available(ctx)
        return listOf(
            Check(
                "selfheal", "Self-repair",
                if (secure) "On: Willbolt switches itself back on if anything turns it off"
                else "Needs one command from a computer. Without it Willbolt can only warn you when it is switched off.",
                ok = secure, critical = false, actionLabel = if (secure) null else "How",
            ),
        )
    }
}

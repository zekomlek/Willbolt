package app.willbolt

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.willbolt.core.Check

/**
 * The Play edition. Google Play does not allow an app to stop people from disabling or uninstalling it through the
 * Accessibility API, to change system settings behind their back, or to dodge Android's privacy controls, so this
 * edition has none of that; the full edition (`src/full`, for installs from GitHub) does.
 */
object Edition {
    const val NAME = "Play edition"
    const val FULL = false

    /** The list of apps in Android's battery settings; the one-tap dialog needs a permission Play does not hand out. */
    @Suppress("UNUSED_PARAMETER")
    fun batteryIntent(ctx: Context): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Shown after tapping the battery fix, since that opens a list. */
    val batteryHint: String? = "Open the list, pick Willbolt and choose Don't optimize."

    /** Said under the lock form: what a lock covers, and what it does not. */
    const val LOCK_NOTE =
        "A lock guards Willbolt's own settings. Android's Settings can still switch Willbolt off or stop it (Activity then " +
            "notes when), or clear its data or uninstall it."

    /** Setup rows that only the full edition has. */
    @Suppress("UNUSED_PARAMETER")
    fun extraChecks(ctx: Context): List<Check> = emptyList()
}

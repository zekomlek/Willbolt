package app.willbolt.service

import android.accessibilityservice.AccessibilityService
import app.willbolt.data.Config

/**
 * Nothing here on purpose. Google Play does not allow an app to stop people from disabling or uninstalling it through
 * the Accessibility API, so the Play edition never looks at Android's own settings screens. The full edition has a real
 * TamperGuard in `src/full`.
 */
@Suppress("UNUSED_PARAMETER")
object TamperGuard {
    fun isSensitive(pkg: String) = false

    fun applies(cfg: Config, now: Long) = false

    fun note(pkg: String, className: CharSequence?, stateChange: Boolean, now: Long) {}

    fun forget() {}

    fun check(
        service: AccessibilityService,
        overlay: BlockOverlay,
        cfg: Config,
        pkg: String,
        className: String?,
        appLabel: String,
    ) {}
}

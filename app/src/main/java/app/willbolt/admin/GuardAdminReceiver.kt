package app.willbolt.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import app.willbolt.Edition
import app.willbolt.core.Policy
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog

/**
 * Being an *active* device administrator is what makes Android ask for it to be deactivated before the app can be
 * uninstalled; Android's Settings also greys out Force stop and Clear storage for an app that has one. Willbolt asks for no
 * admin powers. Optional, and the person can deactivate it in Android's settings (in the full edition, not while the
 * uninstall protection is on: Willbolt then closes that screen).
 */
class GuardAdminReceiver : DeviceAdminReceiver() {

    private val what = if (Edition.FULL) "Uninstall protection" else "Device administrator"

    override fun onEnabled(context: Context, intent: Intent) {
        ConfigStore.init(context)
        EventLog.init(context)
        EventLog.add("ADMIN", "$what activated")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        ConfigStore.init(context)
        val cfg = ConfigStore.value
        return if (Policy.selfProtected(cfg)) {
            "Willbolt's uninstall protection is on. Switch it off in Willbolt first (if a lock holds it on, that lock has to open)."
        } else if (Edition.FULL) {
            "Willbolt can then be force-stopped, cleared or uninstalled like any other app."
        } else {
            "Willbolt can then be uninstalled like any other app."
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        ConfigStore.init(context)
        EventLog.init(context)
        EventLog.add("ADMIN", "$what deactivated")
    }
}

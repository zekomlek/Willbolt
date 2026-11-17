package app.willbolt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.willbolt.core.Watchdog
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog

/** After a reboot or an app update: restart the watchdog and put anything that got switched off back on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConfigStore.init(context)
        EventLog.init(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) EventLog.add("UPDATE", "Willbolt was updated")
        else EventLog.add("BOOT", "The phone was started")
        WatchdogWorker.schedule(context)
        GuardService.start(context)
        Watchdog.checkAll(context)
    }
}

package app.willbolt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.willbolt.core.TimeGuard
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog

/**
 * Android says the phone's clock was set: TimeGuard compares it with the stopwatch right now and books the move, so that
 * nothing - not a lock being made, not one being opened - ever sees the moved time. Registered in code by WillboltApp for as
 * long as Willbolt runs; Android does not hand this broadcast to receivers in the manifest.
 */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ConfigStore.init(context)
        EventLog.init(context)
        TimeGuard.tick()
    }
}

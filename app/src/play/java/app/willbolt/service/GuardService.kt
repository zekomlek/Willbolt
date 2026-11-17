package app.willbolt.service

import android.content.Context

/**
 * The Play edition has no foreground "guard" service (it would only keep a watchdog running for self-repair, which this
 * edition does not do). The accessibility service's own heartbeat and the 15-minute WatchdogWorker do the housekeeping.
 * The full edition's real service is in `src/full`.
 */
@Suppress("UNUSED_PARAMETER")
object GuardService {
    fun start(ctx: Context) {}
}

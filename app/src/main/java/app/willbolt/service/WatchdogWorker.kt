package app.willbolt.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import app.willbolt.core.Policy
import app.willbolt.core.SelfRepair
import app.willbolt.core.TimeGuard
import app.willbolt.core.Watchdog
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.data.UsageStore
import java.util.concurrent.TimeUnit

/** Runs every ~15 minutes even if the guard service is not running (it only runs while something is locked). */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val app = applicationContext
        ConfigStore.init(app)
        EventLog.init(app)
        UsageStore.init(app)
        TimeGuard.tick()
        Policy.housekeeping()
        UsageStore.prune(ConfigStore.value.rules.map { it.id }.toSet())
        GuardService.start(app)
        Watchdog.checkAll(app)
        // A process started just to look cannot wait for the accessibility service to come back after a crash, so it does.
        SelfRepair.settle(app)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork("willbolt-watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

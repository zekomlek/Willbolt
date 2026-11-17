package app.willbolt

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.willbolt.core.AdultList
import app.willbolt.core.Gaps
import app.willbolt.core.Health
import app.willbolt.core.RuleEngine
import app.willbolt.core.TimeGuard
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.data.UsageStore
import app.willbolt.service.ClockReceiver
import app.willbolt.service.WatchdogWorker
import app.willbolt.util.Notifications

class WillboltApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ConfigStore.init(this)
        EventLog.init(this)
        UsageStore.init(this)
        RuleEngine.init(this)
        // The clock is looked at as soon as it is set (see TimeGuard), and once now: a clock moved while Willbolt was not
        // running is caught when it starts.
        ContextCompat.registerReceiver(
            this, ClockReceiver(), IntentFilter(Intent.ACTION_TIME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        TimeGuard.tick()
        Gaps.scan(this)
        AdultList.preload(this)
        Notifications.ensureChannels(this)
        Health.refreshAsync(this)
        WatchdogWorker.schedule(this)
    }
}

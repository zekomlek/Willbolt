package app.willbolt.service

import android.app.KeyguardManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.willbolt.core.RuleEngine
import app.willbolt.core.TimeGuard
import app.willbolt.core.UsageEngine
import app.willbolt.data.ConfigStore
import app.willbolt.data.RuleMode
import app.willbolt.data.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Counts how long the apps of time-limit and break-timer rules are on screen, once a second, and tells the service when
 * something has to happen (limit used up, break starts). The arithmetic lives in [UsageEngine].
 *
 * Time only counts while the screen is on and unlocked and one of the rule's apps is visible.
 */
class UsageTracker(
    private val svc: GuardAccessibilityService,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "UsageTracker"
    }

    private var job: Job? = null
    private var lastTickAt = 0L
    private var wasInteractive = true
    private val pm = svc.getSystemService(PowerManager::class.java)
    private val km = svc.getSystemService(KeyguardManager::class.java)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val idle = safely { tick() }
                delay(if (idle) 5_000L else 1_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        UsageStore.flush()
    }

    /** Called when the apps on screen change, so the switch is noticed at once instead of up to a second later. */
    fun tickNow() {
        safely { tick() }
    }

    /** Returns true when there was nothing to count (no time-limit or break rules). */
    private fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "tick failed", t)
        false
    }

    private fun tick(): Boolean {
        val cfg = ConfigStore.value
        val rules = cfg.rules.filter { it.enabled && it.mode != RuleMode.BLOCK && it.apps.isNotEmpty() }
        val el = SystemClock.elapsedRealtime()
        val dt = if (lastTickAt == 0L) 0L else el - lastTickAt
        lastTickAt = el
        if (rules.isEmpty()) {
            svc.syncBreak(null)
            return true
        }

        val interactive = pm?.isInteractive == true && km?.isKeyguardLocked != true
        val visible = svc.visiblePackages()
        val now = TimeGuard.now()
        for (r in rules) {
            if (!RuleEngine.isActive(r, now)) continue
            val active = interactive && r.apps.any { it in visible && !RuleEngine.isProtectedPackage(it) }
            val before = UsageStore.get(r.id)
            val t = UsageEngine.tick(r, before, active, dt, now, elsewhere = interactive && !active)
            if (t.usage != before) UsageStore.put(r.id, t.usage)
            when (t.signal) {
                UsageEngine.Signal.LIMIT_REACHED -> svc.onLimitReached(r, t.usage)
                UsageEngine.Signal.ONE_MINUTE_LEFT -> svc.onOneMinuteLeft(r)
                UsageEngine.Signal.BREAK_STARTED -> svc.onBreakStarted(r, t.usage)
                null -> {}
            }
        }
        val running = if (interactive) UsageEngine.runningBreak(cfg.rules, visible, now, UsageStore::get) else null
        svc.syncBreak(running)
        // The screen just went off or locked: a good moment to make sure the count is on disk.
        if (wasInteractive && !interactive) scope.launch(Dispatchers.IO) { UsageStore.flush() }
        wasInteractive = interactive
        return false
    }
}

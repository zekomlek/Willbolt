package app.willbolt.core

import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.RuleUsage
import java.util.Calendar
import java.util.TimeZone

/**
 * The arithmetic behind time limits and break timers. Everything here is a pure function of its inputs, so it can be
 * tested without a phone; the accessibility service only feeds it one second at a time (see UsageTracker).
 *
 * All times are TimeGuard time ("trusted" epoch ms, immune to changing the phone's clock).
 */
object UsageEngine {
    /** Away from the app this long, and the next visit counts as opening it anew. */
    const val SESSION_GAP_MS = 60_000L

    /** How long before the end of the allowance the "one minute left" notice appears. */
    const val WARN_BEFORE_MS = 60_000L

    /** One tick never counts as more than this, so a frozen phone or a paused process does not eat the allowance. */
    const val MAX_TICK_MS = 3_000L

    enum class Signal { LIMIT_REACHED, ONE_MINUTE_LEFT, BREAK_STARTED }

    class Tick(val usage: RuleUsage, val signal: Signal? = null)

    // ---- daily windows -----------------------------------------------------------------------------------

    /** Latest moment at or before [now] when the clock showed [resetMin] (minutes after midnight, local time). */
    fun windowStart(now: Long, resetMin: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, resetMin / 60)
        cal.set(Calendar.MINUTE, resetMin % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis > now) cal.add(Calendar.DAY_OF_YEAR, -1)
        return cal.timeInMillis
    }

    fun nextReset(now: Long, resetMin: Int, tz: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = windowStart(now, resetMin, tz) }
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    fun limitMs(rule: Rule) = rule.limit.minutes.coerceAtLeast(1) * 60_000L

    /** When the apps open again after the allowance ran out at [now]. */
    fun closedUntil(rule: Rule, now: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val reset = nextReset(now, rule.limit.resetMin, tz)
        val cd = rule.limit.cooldownMin
        return if (cd <= 0) reset else minOf(now + cd * 60_000L, reset)
    }

    /** The usage as it stands at [now]: an old window is dropped and a finished cool-down is over. */
    fun refresh(rule: Rule, u: RuleUsage, now: Long, tz: TimeZone = TimeZone.getDefault()): RuleUsage {
        val ws = windowStart(now, rule.limit.resetMin, tz)
        return when {
            u.windowStart != ws -> u.copy(windowStart = ws, usedMs = 0L, blockedUntil = 0L, warned = false)
            u.blockedUntil != 0L && now >= u.blockedUntil -> u.copy(usedMs = 0L, blockedUntil = 0L, warned = false)
            else -> u
        }
    }

    fun remainingMs(rule: Rule, u: RuleUsage, now: Long, tz: TimeZone = TimeZone.getDefault()): Long =
        (limitMs(rule) - refresh(rule, u, now, tz).usedMs).coerceAtLeast(0L)

    // ---- one second of use -----------------------------------------------------------------------------------

    /**
     * Advances [prev] by [dtMs] of real time. [active] means one of the rule's apps is on screen right now; [elsewhere] means
     * the phone is in use (screen on and unlocked) with none of them in front. Time spent on a break or while the apps are
     * closed is not counted as use.
     *
     * A break's countdown runs in real time, also while the screen is off. With [Breaks.pauseAway][app.willbolt.data.Breaks.pauseAway]
     * it stops while another app is in use and goes on from there when that ends.
     */
    fun tick(
        rule: Rule,
        prev: RuleUsage,
        active: Boolean,
        dtMs: Long,
        now: Long,
        tz: TimeZone = TimeZone.getDefault(),
        elsewhere: Boolean = false,
    ): Tick {
        var u = refresh(rule, prev, now, tz)
        val limitOn = rule.mode == RuleMode.LIMIT
        val breaksOn = rule.mode != RuleMode.BLOCK && rule.breaks.enabled
        var signal: Signal? = null

        if (u.breakUntil != 0L && now >= u.breakUntil) u = u.copy(breakUntil = 0L, breakLenMs = 0L)
        if (u.breakLeftMs != 0L && (!elsewhere || !breaksOn)) {
            // A paused break goes on as soon as no other app is in use (back in the app, or the screen went off). If the
            // block has no break timer any more, it is dropped.
            u = if (breaksOn) u.copy(breakUntil = now + u.breakLeftMs, breakLeftMs = 0L)
            else u.copy(breakLeftMs = 0L, breakLenMs = 0L)
        }
        if (!active) {
            if (elsewhere && breaksOn && rule.breaks.pauseAway && u.breakUntil > now) {
                // Another app is in use: the countdown stops where it is.
                u = u.copy(breakLeftMs = u.breakUntil - now, breakUntil = 0L)
            }
            return Tick(u)
        }

        val closed = limitOn && u.blockedUntil > now
        val away = if (u.lastActiveAt == 0L) Long.MAX_VALUE else now - u.lastActiveAt
        if (away >= SESSION_GAP_MS) {
            // opening the app anew: the time away counts as a break
            u = u.copy(sinceBreakMs = 0L)
            if (breaksOn && rule.breaks.onOpen && !closed && u.breakUntil <= now) {
                val len = rule.breaks.openLength() * 1000L
                u = u.copy(breakUntil = now + len, breakLenMs = len)
                signal = Signal.BREAK_STARTED
            }
        }
        u = u.copy(lastActiveAt = now)
        if (closed || u.breakUntil > now) return Tick(u, signal)

        val dt = dtMs.coerceIn(0L, MAX_TICK_MS)
        if (limitOn) {
            val limit = limitMs(rule)
            u = u.copy(usedMs = u.usedMs + dt)
            if (u.usedMs >= limit) {
                u = u.copy(usedMs = limit, blockedUntil = closedUntil(rule, now, tz), breakUntil = 0L, breakLenMs = 0L, breakLeftMs = 0L)
                return Tick(u, Signal.LIMIT_REACHED)
            }
            if (!u.warned && limit > 2 * WARN_BEFORE_MS && limit - u.usedMs <= WARN_BEFORE_MS) {
                u = u.copy(warned = true)
                signal = signal ?: Signal.ONE_MINUTE_LEFT
            }
        }
        if (breaksOn) {
            u = u.copy(sinceBreakMs = u.sinceBreakMs + dt)
            if (u.sinceBreakMs >= rule.breaks.everySec * 1000L) {
                val len = rule.breaks.lengthSec * 1000L
                u = u.copy(sinceBreakMs = 0L, breakUntil = now + len, breakLenMs = len)
                signal = Signal.BREAK_STARTED
            }
        }
        return Tick(u, signal)
    }

    // ---- lookups used by the service ---------------------------------------------------------------------------

    /** The limit rule (in effect) that has used up its allowance for [pkg], or null. */
    fun closedRule(rules: List<Rule>, pkg: String, now: Long, usage: (String) -> RuleUsage): Rule? {
        if (RuleEngine.isProtectedPackage(pkg)) return null
        return rules.firstOrNull {
            it.mode == RuleMode.LIMIT && pkg in it.apps && RuleEngine.isActive(it, now) && usage(it.id).blockedUntil > now
        }
    }

    /** The rule whose break timer is running for one of the [visible] apps, or null. */
    fun runningBreak(rules: List<Rule>, visible: Set<String>, now: Long, usage: (String) -> RuleUsage): Rule? =
        rules.firstOrNull { r ->
            r.mode != RuleMode.BLOCK && r.breaks.enabled && RuleEngine.isActive(r, now) &&
                r.apps.any { it in visible && !RuleEngine.isProtectedPackage(it) } && usage(r.id).breakUntil > now
        }
}

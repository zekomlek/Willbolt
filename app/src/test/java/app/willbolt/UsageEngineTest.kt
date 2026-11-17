package app.willbolt

import app.willbolt.core.UsageEngine
import app.willbolt.core.UsageEngine.Signal
import app.willbolt.data.Breaks
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.RuleUsage
import app.willbolt.data.UsageLimit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class UsageEngineTest {
    private lateinit var oldTz: TimeZone

    @Before
    fun setUp() {
        oldTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(oldTz)
    }

    private val sec = 1_000L
    private val min = 60_000L

    private fun at(day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute, second) }.timeInMillis

    private fun limitRule(minutes: Int, resetMin: Int = 4 * 60, cooldown: Int = 0, breaks: Breaks = Breaks()) =
        Rule("r", "Social", apps = setOf("a"), mode = RuleMode.LIMIT, limit = UsageLimit(minutes, resetMin, cooldown), breaks = breaks)

    private fun remindRule(breaks: Breaks) = Rule("b", "Breaks", apps = setOf("a"), mode = RuleMode.REMIND, breaks = breaks)

    private class Run(val usage: RuleUsage, val signals: List<Signal>, val end: Long)

    /** Feeds [seconds] one-second ticks starting at [from] and collects what the engine asked for. */
    private fun run(rule: Rule, start: RuleUsage, from: Long, seconds: Int, active: Boolean = true): Run {
        var u = start
        var now = from
        val signals = ArrayList<Signal>()
        repeat(seconds) {
            now += sec
            val t = UsageEngine.tick(rule, u, active, sec, now)
            u = t.usage
            t.signal?.let { signals += it }
        }
        return Run(u, signals, now)
    }

    // ---- daily windows ------------------------------------------------------------------------------------

    @Test
    fun windowStartIsTheLatestResetAtOrBeforeNow() {
        val reset = 4 * 60
        assertEquals(at(21, 4, 0), UsageEngine.windowStart(at(21, 10, 0), reset))
        assertEquals(at(20, 4, 0), UsageEngine.windowStart(at(21, 3, 59), reset))
        assertEquals(at(21, 4, 0), UsageEngine.windowStart(at(21, 4, 0), reset))
        assertEquals(at(22, 4, 0), UsageEngine.nextReset(at(21, 10, 0), reset))
        assertEquals(at(21, 0, 0), UsageEngine.windowStart(at(21, 23, 59), 0))
    }

    // ---- time limit -------------------------------------------------------------------------------------------

    @Test
    fun limitRunsOutAndClosesTheAppsUntilTheNextReset() {
        val rule = limitRule(minutes = 2)
        val start = at(21, 10, 0)
        val r = run(rule, RuleUsage(), start, 119)
        assertTrue(r.signals.isEmpty())
        assertEquals(0L, r.usage.blockedUntil)

        val last = UsageEngine.tick(rule, r.usage, true, sec, r.end + sec)
        assertEquals(Signal.LIMIT_REACHED, last.signal)
        assertEquals(at(22, 4, 0), last.usage.blockedUntil)
        assertEquals(2 * min, last.usage.usedMs)
    }

    @Test
    fun nothingCountsWhileClosedAndANewWindowStartsFresh() {
        val rule = limitRule(minutes = 2)
        val closed = run(rule, RuleUsage(), at(21, 10, 0), 130)
        assertEquals(1, closed.signals.count { it == Signal.LIMIT_REACHED })
        assertEquals(2 * min, closed.usage.usedMs)

        // still closed the next morning at 03:59, open again at 04:00
        val before = UsageEngine.tick(rule, closed.usage, true, sec, at(22, 3, 59))
        assertEquals(at(22, 4, 0), before.usage.blockedUntil)
        val after = UsageEngine.tick(rule, closed.usage, true, sec, at(22, 4, 0, 1))
        assertEquals(0L, after.usage.blockedUntil)
        assertEquals(sec, after.usage.usedMs)
        assertEquals(min * 2, UsageEngine.limitMs(rule))
    }

    @Test
    fun cooldownReopensBeforeTheResetButNeverPastIt() {
        val rule = limitRule(minutes = 2, cooldown = 120)
        val start = at(21, 10, 0)
        val hit = run(rule, RuleUsage(), start, 121)
        assertTrue(Signal.LIMIT_REACHED in hit.signals)
        // the allowance ran out after 120 s of use; the apps stay closed for 120 minutes from that moment
        assertEquals(start + 120 * sec + 120 * min, hit.usage.blockedUntil)

        // just before the cool-down ends: still closed; at the end: a fresh allowance inside the same window
        val still = UsageEngine.refresh(rule, hit.usage, hit.usage.blockedUntil - 1)
        assertTrue(still.blockedUntil > 0)
        val reopened = UsageEngine.refresh(rule, hit.usage, hit.usage.blockedUntil)
        assertEquals(0L, reopened.blockedUntil)
        assertEquals(0L, reopened.usedMs)

        // a cool-down that would run past the next daily reset is cut at the reset
        val long = rule.copy(limit = rule.limit.copy(cooldownMin = 600))
        assertEquals(at(22, 4, 0), UsageEngine.closedUntil(long, at(21, 23, 0)))
        assertEquals(at(21, 12, 0), UsageEngine.closedUntil(rule, at(21, 10, 0)))
    }

    @Test
    fun oneMinuteLeftIsAnnouncedOnceAndOnlyForLongerLimits() {
        val rule = limitRule(minutes = 5)
        val r = run(rule, RuleUsage(), at(21, 10, 0), 4 * 60 + 30)
        assertEquals(listOf(Signal.ONE_MINUTE_LEFT), r.signals)

        val tiny = run(limitRule(minutes = 2), RuleUsage(), at(21, 10, 0), 100)
        assertFalse(Signal.ONE_MINUTE_LEFT in tiny.signals)
    }

    @Test
    fun aStalledTickIsCappedAndInactiveTimeIsNotCounted() {
        val rule = limitRule(minutes = 30)
        val t = UsageEngine.tick(rule, RuleUsage(), true, 60 * sec, at(21, 10, 0))
        assertEquals(UsageEngine.MAX_TICK_MS, t.usage.usedMs)
        val idle = run(rule, RuleUsage(), at(21, 10, 0), 50, active = false)
        assertEquals(0L, idle.usage.usedMs)
    }

    // ---- break timers ------------------------------------------------------------------------------------------

    @Test
    fun breakStartsAfterTheConfiguredUseAndUseDoesNotCountWhileItRuns() {
        val rule = remindRule(Breaks(enabled = true, everySec = 10, lengthSec = 5))
        val start = at(21, 10, 0)
        val first = run(rule, RuleUsage(), start, 10)
        assertEquals(listOf(Signal.BREAK_STARTED), first.signals)
        assertEquals(first.end + 5 * sec, first.usage.breakUntil)

        // four more seconds are still inside the break: nothing is counted, nothing new starts
        val during = run(rule, first.usage, first.end, 4)
        assertTrue(during.signals.isEmpty())
        assertEquals(0L, during.usage.sinceBreakMs)

        // after the break the count starts again and a second break comes after another 10 s of use
        val after = run(rule, during.usage, during.end, 1 + 10)
        assertEquals(listOf(Signal.BREAK_STARTED), after.signals)
    }

    @Test
    fun onOpenStartsTheTimerFirstThingButNotWhenYouJustSwitchBack() {
        val rule = remindRule(Breaks(enabled = true, everySec = 600, lengthSec = 30, onOpen = true))
        val t0 = at(21, 10, 0)
        val opened = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        assertEquals(Signal.BREAK_STARTED, opened.signal)
        assertEquals(t0 + 30 * sec, opened.usage.breakUntil)

        // sit through the break: no second timer when it ends
        val sat = run(rule, opened.usage, t0, 45)
        assertTrue(sat.signals.isEmpty())
        assertEquals(0L, sat.usage.breakUntil)

        // away for 30 s then back: not a new open. Away for 61 s then back: a new open.
        val away30 = run(rule, sat.usage, sat.end, 30, active = false)
        val back = UsageEngine.tick(rule, away30.usage, true, sec, away30.end + sec)
        assertNull(back.signal)
        val away61 = run(rule, back.usage, away30.end + sec, 61, active = false)
        val reopened = UsageEngine.tick(rule, away61.usage, true, sec, away61.end + sec)
        assertEquals(Signal.BREAK_STARTED, reopened.signal)
    }

    @Test
    fun aRunningBreakIsNotRestartedByLeavingAndComingBack() {
        val rule = remindRule(Breaks(enabled = true, everySec = 600, lengthSec = 120, onOpen = true))
        val t0 = at(21, 10, 0)
        val opened = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        val until = opened.usage.breakUntil
        val gone = run(rule, opened.usage, t0, 70, active = false)
        val back = UsageEngine.tick(rule, gone.usage, true, sec, gone.end + sec)
        assertNull(back.signal)
        assertEquals(until, back.usage.breakUntil)
    }

    @Test
    fun theTimerOnOpeningHasItsOwnLength() {
        val rule = remindRule(Breaks(enabled = true, everySec = 20, lengthSec = 60, onOpen = true, openSec = 5))
        val t0 = at(21, 10, 0)
        val opened = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        assertEquals(Signal.BREAK_STARTED, opened.signal)
        assertEquals(t0 + 5 * sec, opened.usage.breakUntil)
        assertEquals(5 * sec, opened.usage.breakLenMs)

        // after it (4 more seconds), 20 s of use bring the regular break, which is still a minute long
        val used = run(rule, opened.usage, t0, 4 + 20)
        assertEquals(listOf(Signal.BREAK_STARTED), used.signals)
        assertEquals(used.end + 60 * sec, used.usage.breakUntil)
        assertEquals(60 * sec, used.usage.breakLenMs)

        // a block saved before 0.6.8 has no length of its own for it: the break length is used
        assertEquals(60, Breaks(lengthSec = 60, onOpen = true).openLength())
    }

    /** Feeds ticks in which another app is in use (screen on, none of the rule's apps in front). */
    private fun elsewhere(rule: Rule, start: RuleUsage, from: Long, seconds: Int): Run {
        var u = start
        var now = from
        repeat(seconds) {
            now += sec
            u = UsageEngine.tick(rule, u, false, sec, now, elsewhere = true).usage
        }
        return Run(u, emptyList(), now)
    }

    @Test
    fun withPauseTheCountdownStopsInAnotherAppAndGoesOnWhenYouComeBack() {
        val rule = remindRule(Breaks(enabled = true, everySec = 600, lengthSec = 30, onOpen = true, pauseAway = true))
        val t0 = at(21, 10, 0)
        val opened = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        val watched = run(rule, opened.usage, t0, 10)

        // five minutes in another app (the first tick there comes a second later, with 19 s left): the countdown stands
        // still, and there is none on screen
        val gone = elsewhere(rule, watched.usage, watched.end, 300)
        assertEquals(19 * sec, gone.usage.breakLeftMs)
        assertEquals(0L, gone.usage.breakUntil)
        assertNull(UsageEngine.runningBreak(listOf(rule), setOf("a"), gone.end) { gone.usage })

        // back in the app: the same 19 s go on - not a new timer, although you were away for more than a minute
        val back = UsageEngine.tick(rule, gone.usage, true, sec, gone.end + sec)
        assertNull(back.signal)
        assertEquals(gone.end + sec + 19 * sec, back.usage.breakUntil)
        assertEquals(0L, back.usage.breakLeftMs)
        assertEquals(30 * sec, back.usage.breakLenMs)
        val done = run(rule, back.usage, gone.end + sec, 21)
        assertEquals(0L, done.usage.breakUntil)
    }

    @Test
    fun withoutPauseTheCountdownRunsOnInAnotherApp() {
        val rule = remindRule(Breaks(enabled = true, everySec = 600, lengthSec = 30, onOpen = true))
        val t0 = at(21, 10, 0)
        val opened = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        val gone = elsewhere(rule, opened.usage, t0, 40)
        assertEquals(0L, gone.usage.breakLeftMs)
        assertEquals(0L, gone.usage.breakUntil)
        // back within the minute: no timer
        val back = UsageEngine.tick(rule, gone.usage, true, sec, gone.end + sec)
        assertNull(back.signal)
        assertEquals(0L, back.usage.breakUntil)
    }

    @Test
    fun withPauseTheCountdownStillRunsWhileTheScreenIsOff() {
        val rule = remindRule(Breaks(enabled = true, everySec = 600, lengthSec = 30, onOpen = true, pauseAway = true))
        val t0 = at(21, 10, 0)
        val opened = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        // screen off for 40 s (not in use, so not "another app"): the break is over
        val off = run(rule, opened.usage, t0, 40, active = false)
        assertEquals(0L, off.usage.breakUntil)
        assertEquals(0L, off.usage.breakLeftMs)

        // paused in another app, then the screen goes off: from then on it counts down again
        val again = UsageEngine.tick(rule, RuleUsage(), true, sec, t0)
        val paused = elsewhere(rule, again.usage, t0, 10)
        assertEquals(29 * sec, paused.usage.breakLeftMs)
        val dark = UsageEngine.tick(rule, paused.usage, false, sec, paused.end + sec)
        assertEquals(paused.end + sec + 29 * sec, dark.usage.breakUntil)
        assertEquals(0L, dark.usage.breakLeftMs)
    }

    @Test
    fun aPausedBreakIsDroppedWhenTheBreakTimerIsSwitchedOff() {
        val rule = remindRule(Breaks(enabled = true, everySec = 600, lengthSec = 30, onOpen = true, pauseAway = true))
        val t0 = at(21, 10, 0)
        val paused = elsewhere(rule, UsageEngine.tick(rule, RuleUsage(), true, sec, t0).usage, t0, 5)
        assertTrue(paused.usage.breakLeftMs > 0L)
        val off = rule.copy(breaks = rule.breaks.copy(enabled = false))
        val back = UsageEngine.tick(off, paused.usage, true, sec, paused.end + sec)
        assertEquals(0L, back.usage.breakLeftMs)
        assertEquals(0L, back.usage.breakUntil)
    }

    @Test
    fun breaksWorkTogetherWithALimit() {
        val rule = limitRule(minutes = 10, breaks = Breaks(enabled = true, everySec = 60, lengthSec = 10))
        val r = run(rule, RuleUsage(), at(21, 10, 0), 61)
        assertEquals(listOf(Signal.BREAK_STARTED), r.signals)
        // the 60 s of use before the break count against the allowance, the break itself does not
        assertEquals(60 * sec, r.usage.usedMs)
    }

    // ---- lookups ---------------------------------------------------------------------------------------------------

    @Test
    fun lookupsFindTheClosedRuleAndTheRunningBreak() {
        val rule = limitRule(minutes = 2)
        val wall = at(21, 10, 0)
        val usage = mapOf("r" to RuleUsage(blockedUntil = wall + min), "b" to RuleUsage(breakUntil = wall + min))
        val get = { id: String -> usage[id] ?: RuleUsage() }

        assertNotNull(UsageEngine.closedRule(listOf(rule), "a", wall, get))
        assertNull(UsageEngine.closedRule(listOf(rule), "other", wall, get))
        assertNull(UsageEngine.closedRule(listOf(rule), "a", wall + 2 * min, get)) // closure is over

        val breaker = remindRule(Breaks(enabled = true))
        assertNotNull(UsageEngine.runningBreak(listOf(breaker), setOf("a"), wall, get))
        assertNull(UsageEngine.runningBreak(listOf(breaker), setOf("z"), wall, get))
        assertNull(UsageEngine.runningBreak(listOf(breaker.copy(enabled = false)), setOf("a"), wall, get))
    }
}

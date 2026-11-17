package app.willbolt

import app.willbolt.core.DomainPolicy
import app.willbolt.core.RuleEngine
import app.willbolt.data.Breaks
import app.willbolt.data.Config
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.Schedule
import app.willbolt.data.ScheduleKind
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

class RuleEngineTest {
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

    // 2026-10-21 is a Wednesday.
    private fun at(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    private val weekdays = 0b0011111 // Mon..Fri

    private fun weekly(days: Int, start: Int, end: Int) =
        Rule("r", "r", schedule = Schedule(ScheduleKind.WEEKLY, days, start, end))

    @Test
    fun daytimeWindowOnWeekdays() {
        val r = weekly(weekdays, 9 * 60, 17 * 60)
        assertTrue(RuleEngine.isActive(r, at(21, 9, 0)))      // Wed 09:00 - inclusive start
        assertTrue(RuleEngine.isActive(r, at(21, 16, 59)))
        assertFalse(RuleEngine.isActive(r, at(21, 17, 0)))    // exclusive end
        assertFalse(RuleEngine.isActive(r, at(21, 8, 59)))
        assertFalse(RuleEngine.isActive(r, at(24, 12, 0)))    // Saturday
        assertFalse(RuleEngine.isActive(r, at(25, 12, 0)))    // Sunday
    }

    @Test
    fun overnightWindowSpillsIntoTheNextMorning() {
        val r = weekly(weekdays, 22 * 60, 6 * 60)
        assertTrue(RuleEngine.isActive(r, at(21, 23, 30)))    // Wed night
        assertTrue(RuleEngine.isActive(r, at(22, 2, 0)))      // Thu 02:00 belongs to Wed night
        assertFalse(RuleEngine.isActive(r, at(21, 12, 0)))
        assertTrue(RuleEngine.isActive(r, at(24, 2, 0)))      // Sat 02:00 belongs to Fri night
        assertFalse(RuleEngine.isActive(r, at(25, 2, 0)))     // Sun 02:00 belongs to Sat night (off)
        assertFalse(RuleEngine.isActive(r, at(25, 23, 0)))    // Sun 23:00 (Sunday not selected)
    }

    @Test
    fun sameStartAndEndMeansAllDay() {
        val r = weekly(0b0000001, 0, 0) // Mondays only
        assertTrue(RuleEngine.isActive(r, at(19, 3, 0)))      // Mon 19 Oct
        assertFalse(RuleEngine.isActive(r, at(21, 3, 0)))
    }

    @Test
    fun untilAndDisabledRules() {
        val now = at(21, 12, 0)
        val until = Rule("u", "u", schedule = Schedule(ScheduleKind.UNTIL, until = now + 1000))
        assertTrue(RuleEngine.isActive(until, now))
        assertFalse(RuleEngine.isActive(until, now + 1000))
        assertFalse(RuleEngine.isActive(until.copy(enabled = false), now))
        assertTrue(RuleEngine.isActive(Rule("a", "a"), now))
    }

    @Test
    fun appBlockingIgnoresProtectedPackages() {
        val rule = Rule("r", "Distractions", apps = setOf("com.example.game", "com.android.settings"))
        val cfg = Config(rules = listOf(rule))
        assertNotNull(RuleEngine.blockedApp(cfg, "com.example.game", 0))
        assertNull(RuleEngine.blockedApp(cfg, "com.android.settings", 0)) // can never be blocked
        assertNull(RuleEngine.blockedApp(cfg, "com.other", 0))
    }

    @Test
    fun onlyBlockRulesCloseAppsOrSitesOutright() {
        val limit = Rule("l", "Limit", apps = setOf("com.example.game"), domains = setOf("game.com"), mode = RuleMode.LIMIT)
        val remind = Rule("m", "Remind", apps = setOf("com.example.chat"), mode = RuleMode.REMIND, breaks = Breaks(enabled = true))
        val cfg = Config(rules = listOf(limit, remind), adultDomainList = false)
        // limits and reminders are decided by the usage counter, not by a plain schedule check
        assertNull(RuleEngine.blockedApp(cfg, "com.example.game", 0))
        assertNull(RuleEngine.blockedApp(cfg, "com.example.chat", 0))
        assertNull(RuleEngine.blockedDomainRule(cfg, "game.com", 0))
    }

    @Test
    fun rulesDescribeThemselvesInPlainWords() {
        val block = Rule("b", "b")
        assertEquals("Always on", RuleEngine.describeRule(block))
        val limit = Rule("l", "l", mode = RuleMode.LIMIT, limit = UsageLimit(90, 4 * 60, 0))
        assertEquals("1 h 30 min a day, resets at 04:00", RuleEngine.describeRule(limit))
        val withBreaks = limit.copy(limit = UsageLimit(30, 0, 120), breaks = Breaks(true, 600, 30, true))
        assertEquals(
            "30 min a day, resets at 00:00, closed for 2 h after\nBreak every 10 min for 30 s, also when opened",
            RuleEngine.describeRule(withBreaks),
        )
        assertEquals("Break every 45 s for 1 min 30 s", RuleEngine.describeRule(Rule("m", "m", mode = RuleMode.REMIND, breaks = Breaks(true, 45, 90))))
        assertEquals(
            "Break every 10 min for 30 s, 5 s when opened, pauses in other apps",
            RuleEngine.describeBreaks(Breaks(true, 600, 30, onOpen = true, openSec = 5, pauseAway = true)),
        )
        assertEquals("Break reminders (off)", RuleEngine.describeRule(Rule("m", "m", mode = RuleMode.REMIND)))
    }

    @Test
    fun suffixesAndNormalisation() {
        assertEquals(listOf("a.b.example.com", "b.example.com", "example.com"), RuleEngine.suffixes("a.b.example.com"))
        assertEquals("example.com", RuleEngine.normalizeDomain("https://www.Example.com/path?x=1"))
        assertNull(RuleEngine.normalizeDomain("not a domain"))
        assertNull(RuleEngine.normalizeDomain("localhost"))
    }

    @Test
    fun domainRulesMatchSubdomainsAndAllowListWins() {
        val rule = Rule("r", "Social", domains = setOf("reddit.com"))
        val cfg = Config(rules = listOf(rule), adultDomainList = false)
        assertNotNull(DomainPolicy.verdict(cfg, "old.reddit.com", 0))
        assertNull(DomainPolicy.verdict(cfg, "notreddit.com", 0))
        val allowed = cfg.copy(allowDomains = setOf("old.reddit.com"))
        assertNull(DomainPolicy.verdict(allowed, "old.reddit.com", 0))
        assertNotNull(DomainPolicy.verdict(allowed, "www.reddit.com", 0))
    }
}

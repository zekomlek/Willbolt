package app.willbolt

import app.willbolt.core.Policy
import app.willbolt.core.RuleEngine
import app.willbolt.core.TimeGuard
import app.willbolt.core.TimeGuard.Mark
import app.willbolt.data.Config
import app.willbolt.data.Lock
import app.willbolt.data.LockKind
import app.willbolt.data.Rule
import app.willbolt.data.Schedule
import app.willbolt.data.ScheduleKind
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
import kotlin.math.abs

class TimeGuardTest {
    private val sec = 1_000L
    private val min = 60_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private lateinit var oldTz: TimeZone

    // 2026-10-21 (a Wednesday) 23:00 UTC
    private var night = 0L

    @Before
    fun setUp() {
        oldTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        night = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 21, 23, 0, 0) }.timeInMillis
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(oldTz)
    }

    /** A phone: the wall clock and the stopwatch run together, [setClock] moves only the wall clock. [ask] is TimeGuard.now(). */
    private class Phone(var wall: Long, var elapsed: Long = 600_000L, var boot: Int = 7, var mark: Mark = Mark()) {
        var moved = 0L

        fun pass(ms: Long) {
            wall += ms
            elapsed += ms
        }

        fun setClock(by: Long) {
            wall += by
        }

        fun restart(afterMs: Long) {
            wall += afterMs
            elapsed = 20_000L
            boot++
        }

        fun ask(): Long {
            val r = TimeGuard.read(mark, wall, elapsed, boot)
            mark = r.mark
            moved = r.moved
            return r.trusted
        }
    }

    private fun phone() = Phone(night).also { it.ask() } // asked once, as Willbolt does the moment it starts

    // ---- the clock -----------------------------------------------------------------------------------------------

    @Test
    fun `time passes as it does when nobody touches the clock`() {
        val p = phone()
        val t0 = p.ask()
        p.pass(90 * sec)
        assertEquals(t0 + 90 * sec, p.ask())
        assertEquals(0L, p.mark.penalty)
        assertEquals(0L, p.moved)
    }

    @Test
    fun `a clock moved forward changes nothing, at the very next question`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(+day) // no time passes between the move and the question: this is the old five-second hole
        assertEquals(t0, p.ask())
        assertEquals(day, p.moved)
        p.pass(min)
        assertEquals(t0 + min, p.ask())
    }

    @Test
    fun `a clock moved back changes nothing either`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(-3 * hour)
        assertEquals(t0, p.ask())
        assertEquals(-3 * hour, p.moved)
        p.pass(10 * min)
        assertEquals(t0 + 10 * min, p.ask())
    }

    @Test
    fun `a move and the move back cancel out`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(+hour)
        p.ask()
        p.pass(30 * sec)
        p.setClock(-hour) // the phone's own time sync puts it right again
        assertEquals(t0 + 30 * sec, p.ask())
        assertEquals(0L, p.mark.penalty)
    }

    @Test
    fun `a move and its undo before anybody asks leave nothing behind`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(+day)
        p.setClock(-day)
        assertEquals(t0, p.ask())
        assertEquals(0L, p.mark.penalty)
    }

    @Test
    fun `a few seconds off is let through - the two clocks never tick quite alike`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(+2 * sec)
        assertEquals(t0 + 2 * sec, p.ask())
        assertEquals(0L, p.mark.penalty)
        assertEquals(0L, p.moved)
    }

    @Test
    fun `small moves cannot be added up into a big one`() {
        // Every five seconds the clock is moved 2.9 s forward - each too small to count on its own - 200 times: 580 s.
        val p = phone()
        val t0 = p.ask()
        repeat(200) {
            p.pass(5 * sec)
            p.setClock(2_900L)
            p.ask()
        }
        val gained = p.ask() - t0 - 200 * 5 * sec
        assertTrue("gained $gained ms", abs(gained) <= TimeGuard.SLACK_MS)
    }

    @Test
    fun `small moves between two questions that are far apart are caught as one`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(2_900L)
        p.pass(hour / 60)
        p.setClock(2_900L)
        p.pass(hour / 60)
        p.setClock(2_900L) // 8.7 s in all, nobody asked in between
        val t = p.ask()
        assertTrue(abs(t - (t0 + 2 * min)) <= 1_000L) // booked as one move, so what is left over is the rate allowance
    }

    @Test
    fun `the starting point is renewed after a while`() {
        val p = phone()
        val before = p.mark
        p.pass(TimeGuard.REMARK_MS - sec)
        p.ask()
        assertEquals(before, p.mark)
        p.pass(2 * sec)
        p.ask()
        assertEquals(p.wall, p.mark.wall)
        assertEquals(p.elapsed, p.mark.elapsed)
    }

    @Test
    fun `a first start takes the clock as it is, with the penalty it brings`() {
        val r = TimeGuard.read(Mark(penalty = 5 * min), night, 1_000L, 3)
        assertEquals(night + 5 * min, r.trusted)
        assertEquals(Mark(night, 1_000L, 3, 5 * min), r.mark)
        assertEquals(0L, r.moved)
    }

    // ---- the phone started again ---------------------------------------------------------------------------------

    @Test
    fun `after a restart the time the phone was off is just time that passed`() {
        val p = phone()
        val t0 = p.ask()
        p.restart(8 * hour)
        assertEquals(t0 + 8 * hour, p.ask())
        assertEquals(0L, p.moved)
        assertEquals(8, p.mark.boot)
    }

    @Test
    fun `after a restart a clock that went back is held where it was`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(-day) // moved back with Willbolt not looking, then the phone restarted
        p.restart(2 * min)
        val t = p.ask()
        assertEquals(t0, t) // time does not run backwards: it stays at the last known moment
        assertTrue(p.moved < 0)
        p.pass(min)
        assertEquals(t0 + min, p.ask())
    }

    @Test
    fun `without a start count only a clock that went back can be told`() {
        val p = phone()
        p.boot = -1
        val t0 = p.ask()
        p.pass(min)
        p.setClock(+hour) // not told apart from a long wait
        assertEquals(t0 + min + hour, p.ask())
        p.setClock(-2 * hour)
        assertEquals(t0 + min + hour, p.ask())
    }

    @Test
    fun `a stopwatch that ran backwards is a restart`() {
        val p = phone()
        val t0 = p.ask()
        p.elapsed -= 5 * min // cannot happen within one start; treated as a new one
        p.pass(min)
        assertEquals(t0 + min, p.ask())
    }

    // ---- what that means for the locks ---------------------------------------------------------------------------

    @Test
    fun `an unlock that is waiting cannot be replaced by a weaker lock in the moment after the clock was moved`() {
        val p = phone()
        val t0 = p.ask()
        // A lock that unlocks after a day, and the unlock is under way (it opens in a day).
        val old = Lock(on = true, kind = LockKind.WAIT, delayMin = 1_440, unlockAt = t0 + day)
        assertTrue(Policy.isLocked(old, t0))

        p.setClock(+day) // "speed it up" - and be quick: no time passes before the next question
        // What the old code saw for a few seconds: the phone's clock. The lock looked open...
        assertFalse(Policy.isLocked(old, p.wall))
        // ...and could be locked again as a 5-minute lock. With the time asked for, it is still locked, and that is refused.
        val now = p.ask()
        assertTrue(Policy.isLocked(old, now))
        val weaker = Lock(on = true, kind = LockKind.WAIT, delayMin = 5)
        assertNull(Policy.locked(old, weaker, now))
        // Locking it for as long or longer is still fine.
        assertNotNull(Policy.locked(old, old.copy(unlockAt = 0L), now))
    }

    @Test
    fun `a lock that ends by itself is not ended by moving the clock`() {
        val p = phone()
        val t0 = p.ask()
        val lock = Lock(on = true, kind = LockKind.TIME, ends = t0 + 6 * hour)
        p.setClock(+day)
        assertTrue(Policy.isLocked(lock, p.ask()))
        p.pass(6 * hour)
        assertFalse(Policy.isLocked(lock, p.ask())) // it ends when the time really has passed
    }

    // ---- lock-outs, "block until" and schedules go by the same time ----------------------------------------------------

    @Test
    fun `an app that is locked out stays locked out when the clock is moved`() {
        val p = phone()
        val t0 = p.ask()
        val cfg = Config(lockouts = mapOf("app.a" to t0 + 10 * min))
        p.setClock(+hour)
        val now = p.ask()
        assertEquals(t0 + 10 * min, Policy.lockoutEnd(cfg, "app.a", now))
        assertNull(Policy.lockoutEnd(cfg, "app.b", now))
        p.pass(10 * min)
        assertNull(Policy.lockoutEnd(cfg, "app.a", p.ask())) // over when the ten minutes have really passed
    }

    @Test
    fun `a lock-out written in another frame of time is not believed`() {
        val now = night
        val cfg = Config(lockouts = mapOf("far" to now + 3 * day, "past" to now - min, "ok" to now + hour))
        assertNull(Policy.lockoutEnd(cfg, "far", now))
        assertNull(Policy.lockoutEnd(cfg, "past", now))
        assertEquals(now + hour, Policy.lockoutEnd(cfg, "ok", now))
    }

    @Test
    fun `a block for a while is not ended by moving the clock`() {
        val p = phone()
        val t0 = p.ask()
        val rule = Rule("r", "r", apps = setOf("a"), schedule = Schedule(ScheduleKind.UNTIL, until = t0 + 2 * hour))
        p.setClock(+day)
        assertTrue(RuleEngine.isActive(rule, p.ask()))
        p.pass(2 * hour)
        assertFalse(RuleEngine.isActive(rule, p.ask()))
    }

    @Test
    fun `a night-time block is not switched off by setting the clock to midday`() {
        val p = phone() // Wednesday 23:00
        val rule = Rule(
            "r", "r", apps = setOf("a"),
            schedule = Schedule(ScheduleKind.WEEKLY, days = 0b1111111, startMin = 22 * 60, endMin = 6 * 60),
        )
        assertTrue(RuleEngine.isActive(rule, p.ask()))
        p.setClock(+13 * hour) // the phone now says Thursday 12:00
        assertFalse(RuleEngine.isActive(rule, p.wall)) // the clock alone would say: not blocked
        assertTrue(RuleEngine.isActive(rule, p.ask())) // Willbolt's time still says 23:00
    }

    @Test
    fun `block times shown on the phone's clock are the stored time minus the penalty`() {
        val p = phone()
        val t0 = p.ask()
        p.setClock(+hour)
        p.ask()
        val until = t0 + 2 * hour // TimeGuard time: it ends 2 hours from now
        val shownOnPhone = until - p.mark.penalty
        assertEquals(p.wall + 2 * hour, shownOnPhone) // on the phone's clock (which reads an hour ahead) too: 2 hours from its "now"
    }

    @Test
    fun `span reads like the rest of the app`() {
        assertEquals("40 s", TimeGuard.span(40 * sec))
        assertEquals("5 min", TimeGuard.span(5 * min))
        assertEquals("3 h 5 min", TimeGuard.span(3 * hour + 5 * min))
        assertEquals("2 h", TimeGuard.span(2 * hour))
        assertEquals("1 day", TimeGuard.span(day))
        assertEquals("2 days 3 h", TimeGuard.span(2 * day + 3 * hour))
    }
}

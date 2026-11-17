package app.willbolt

import app.willbolt.core.Gaps
import app.willbolt.core.Gaps.End
import app.willbolt.core.Gaps.Exit
import app.willbolt.core.Gaps.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which process ends count as "somebody force-stopped Willbolt", and which of those were the phone maker's battery manager. */
class GapsTest {
    private val t = 1_000_000_000_000L
    private val min = 60_000L

    // What Android wrote on the Honor phone, 2 November: the same description for every one of the seven stops that day.
    private val honor = "stop app.willbolt due to from pid 1593 iAwareR[PowerGenie inactive clean] 18621865ms"
    private val update = "stop app.willbolt due to installPackageLI"

    @Test
    fun onlyEndsByTheUserAfterTheLastLookCount() {
        val exits = listOf(
            Exit(t - 30 * min, byUser = true), // already seen
            Exit(t + 5 * min, byUser = false), // low memory...
            Exit(t + 20 * min, byUser = true),
            Exit(t + 10 * min, byUser = true),
        )
        assertEquals(listOf(t + 10 * min, t + 20 * min), Gaps.forceStops(exits, since = t, updatedAt = 0L))
        assertEquals(emptyList<Long>(), Gaps.forceStops(emptyList(), since = t, updatedAt = 0L))
    }

    @Test
    fun anUpdateEndsTheOldProcessLikeAForceStopButIsNotOne() {
        val updatedAt = t + 100 * min
        val exits = listOf(
            Exit(updatedAt - 800, byUser = true, note = update), // the installer stopping the old version, a moment before
            Exit(t + 50 * min, byUser = true), // a real one, earlier the same day
            Exit(updatedAt + min, byUser = true), // a real one, after the update
        )
        assertEquals(listOf(t + 50 * min, updatedAt + min), Gaps.forceStops(exits, since = t, updatedAt = updatedAt))
    }

    @Test
    fun aCrashIsAGapToo() {
        // 2 November, 18:51: Willbolt crashed and the accessibility service was not bound again for two hours, silently -
        // only force-stops were ever looked for.
        val exits = listOf(
            Exit(t + 10 * min, byUser = false, crash = true),
            Exit(t + 20 * min, byUser = true, note = honor),
            Exit(t + 30 * min, byUser = false), // low memory, a normal end of a cached process
        )
        val s = Gaps.stops(exits, since = t, updatedAt = 0L)
        assertEquals(listOf(End.CRASH, End.MAKER), s.map { it.end })
        // a crash is not a force-stop
        assertEquals(listOf(t + 20 * min), Gaps.forceStops(exits, since = t, updatedAt = 0L))
    }

    @Test
    fun theBatteryManagerIsToldFromAPersonInSettings() {
        assertTrue(Gaps.isMakerStop(honor))
        assertTrue(Gaps.isMakerStop("stop app.willbolt due to from pid 4242 MIUI Millet frozen"))
        assertFalse("an update is not the battery manager", Gaps.isMakerStop(update))
        assertFalse("a stop from Settings names no manager", Gaps.isMakerStop("stop app.willbolt due to from pid 23412"))
        assertFalse(Gaps.isMakerStop(""))
        val s = Gaps.stops(listOf(Exit(t + min, byUser = true, note = honor), Exit(t + 2 * min, byUser = true)), since = t, updatedAt = 0L)
        assertEquals(listOf(End.MAKER, End.FORCE_STOP), s.map { it.end })
        assertEquals("PowerGenie inactive clean", s.first().by)
    }

    @Test
    fun theManagersOwnNameIsTakenFromTheBrackets() {
        assertEquals("PowerGenie inactive clean", Gaps.nameOf(honor))
        assertEquals("Foo bar", Gaps.nameOf("stop app.x due to from pid 12 Foo bar"))
        assertEquals("", Gaps.nameOf(""))
    }

    @Test
    fun aStopByTheManagerKeepsItsScreensReachableForTwoHoursAndOnlyIfNothingWasTickedAfterIt() {
        val hour = 60 * min
        val stops = listOf(Stop(t - 5 * hour, End.MAKER), Stop(t - 30 * min, End.MAKER), Stop(t - 10 * min, End.CRASH))
        // within two hours: only the half-hour-old stop by the manager (the crash is not its doing)
        assertEquals(listOf(t - 30 * min), Gaps.stoppedLately(stops, now = t, doneAt = 0L, withinMs = 2 * hour).map { it.at })
        // ticked as done after it: that stop is settled
        assertEquals(emptyList<Long>(), Gaps.stoppedLately(stops, now = t, doneAt = t - 20 * min, withinMs = 2 * hour).map { it.at })
        // and an old stop does not keep a screen open
        assertEquals(emptyList<Long>(), Gaps.stoppedLately(listOf(Stop(t - 5 * hour, End.MAKER)), now = t, doneAt = 0L, withinMs = 2 * hour).map { it.at })
    }

    @Test
    fun theLogSaysWhatHappened() {
        val now = t
        assertTrue(Gaps.describe(Stop(now - min, End.CRASH), now).contains("crashed"))
        val maker = Gaps.describe(Stop(now - min, End.MAKER, "PowerGenie inactive clean"), now)
        assertTrue(maker, maker.contains("battery manager") && maker.contains("PowerGenie inactive clean"))
        assertTrue(Gaps.describe(Stop(now - min, End.FORCE_STOP), now).startsWith("Force-stopped at"))
    }

    @Test
    fun theTimeShowsTheDateOnlyWhenItWasNotToday() {
        val day = 24 * 60 * min
        val now = t
        val today = Gaps.at(now - min, now)
        val earlier = Gaps.at(now - 3 * day, now)
        // today: just a clock time; earlier: longer, because it carries the date as well
        assertTrue("$earlier should be longer than $today", earlier.length > today.length)
    }
}

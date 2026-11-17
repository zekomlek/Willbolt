package app.willbolt

import app.willbolt.data.Config
import app.willbolt.data.Rule
import app.willbolt.data.Schedule
import app.willbolt.data.ScheduleKind
import app.willbolt.ui.activeParts
import org.junit.Assert.assertEquals
import org.junit.Test

/** The card at the top of Home says "Willbolt is on" only when something is actually on. */
class HomeStatusTest {
    private val wall = 1_000_000_000_000L
    private val off = Config(scanEnabled = false, adultDomainList = false, safeSearch = false)
    private val nothing = emptyList<String>()

    @Test
    fun withEverythingOffNothingIsOn() {
        assertEquals(nothing, activeParts(off, wall, scanReady = true, dnsRunning = true))
        // paused and finished blocks don't count
        val paused = Rule("p", "", apps = setOf("a"), enabled = false)
        val ended = Rule("e", "", apps = setOf("a"), schedule = Schedule(ScheduleKind.UNTIL, until = wall - 1))
        assertEquals(nothing, activeParts(off.copy(rules = listOf(paused, ended)), wall, scanReady = true, dnsRunning = true))
        // safe search needs the website filter running, the screen check needs its model
        val notRunning = off.copy(scanEnabled = true, safeSearch = true)
        assertEquals(nothing, activeParts(notRunning, wall, scanReady = false, dnsRunning = false))
    }

    @Test
    fun whatIsOnIsListed() {
        val always = Rule("a", "", apps = setOf("a"))
        val notNow = Rule("w", "", apps = setOf("b"), schedule = Schedule(ScheduleKind.WEEKLY, days = 0))
        assertEquals(
            listOf("1 block", "1 scheduled", "screen check", "adult sites", "safe search"),
            activeParts(
                Config(rules = listOf(always, notNow), scanEnabled = true, adultDomainList = true),
                wall, scanReady = true, dnsRunning = true,
            ),
        )
        assertEquals(listOf("adult sites"), activeParts(off.copy(adultDomainList = true), wall, scanReady = true, dnsRunning = false))
    }
}

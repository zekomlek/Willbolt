package app.willbolt

import app.willbolt.core.Policy
import app.willbolt.core.Watchdog
import app.willbolt.data.Config
import app.willbolt.data.Rule
import app.willbolt.ui.activeParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a brand new install looks like: everything off, and the values most people end up with already filled in. */
class DefaultsTest {
    @Test
    fun aNewInstallHasTheScreenCheckAndTheAdultListOffWithTheSuggestedValuesReady() {
        val c = Config()
        assertFalse(c.scanEnabled)
        assertFalse(c.adultDomainList)
        assertEquals(50, c.scanSensitivity)
        assertEquals(7, c.scanIntervalSec)
        assertEquals(5_000, c.adultListSize)
    }

    @Test
    fun aNewInstallHasNoUninstallProtectionAndNothingIsOn() {
        val c = Config()
        // (its switch starts off, no lock holds it, and new locks are offered it off)
        assertFalse(c.guardOn)
        assertFalse(c.lockGuard)
        assertFalse(Policy.selfProtected(c, 1_000_000_000_000L))
        // (safe search counts only while the website filter runs, which a new install has not started)
        assertEquals(emptyList<String>(), activeParts(c, 1_000_000_000_000L, scanReady = true, dnsRunning = false))
    }

    @Test
    fun aNewInstallDoesNotAskForTheWebsiteFilter() {
        // Safe search is on by default but only matters once the filter runs, so it is no reason to ask for it.
        assertFalse(Watchdog.wantsDnsFilter(Config()))
        assertTrue(Config().safeSearch)
        // Once you started the filter it is wanted again (it comes back after a reboot), as before.
        assertTrue(Watchdog.wantsDnsFilter(Config(webFilterOn = true)))
        // The adult list and a block with websites still ask for it.
        assertTrue(Watchdog.wantsDnsFilter(Config(adultDomainList = true)))
        assertTrue(Watchdog.wantsDnsFilter(Config(rules = listOf(Rule("s", "", domains = setOf("x.com"))))))
        assertFalse(Watchdog.wantsDnsFilter(Config(rules = listOf(Rule("a", "", apps = setOf("a"))))))
    }
}

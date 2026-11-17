package app.willbolt

import app.willbolt.core.Policy
import app.willbolt.ui.guardStatus
import app.willbolt.ui.heldText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card on Home turns the protection on or off with one tap and says, in a line, where it stands - and offers Activate
 * only when that is the next step.
 */
class GuardStatusTest {
    private val self = Policy.SELF_LOCK to "Uninstall protection"
    private val scan = Policy.Target.Part(Policy.Area.SCAN) to "Screen check"
    private val web = Policy.Target.Part(Policy.Area.WEB) to "Websites"
    private val block = Policy.Target.Block("b") to "Instagram, TikTok"

    private fun status(
        on: Boolean = false,
        holders: List<Pair<Policy.Target, String>> = emptyList(),
        admin: Boolean = false,
        locked: Boolean = false,
    ) = guardStatus(on, holders, admin, locked)

    @Test
    fun offSaysOneTapTurnsItOn() {
        for (admin in listOf(true, false)) {
            val s = status(admin = admin)
            assertEquals("Off. Tap to turn it on.", s.text)
            assertFalse(s.on)
            assertFalse(s.activate)
            assertFalse(s.warn)
        }
    }

    @Test
    fun offWithALockOnWarnsThatSettingsCanStillStopWillbolt() {
        val s = status(locked = true)
        assertFalse(s.on)
        assertTrue(s.warn)
        assertFalse(s.activate)
        assertTrue(s.text.contains("locked"))
        assertTrue(s.text.contains("Tap to turn it on"))
    }

    @Test
    fun switchedOnIsOnWithoutAnyLock() {
        val s = status(on = true, admin = true)
        assertTrue(s.on)
        assertEquals("On. Settings can't stop or remove Willbolt.", s.text)
        assertFalse(s.warn)
        // (and something else being locked doesn't change that)
        assertEquals(s.text, status(on = true, admin = true, locked = true).text)
    }

    @Test
    fun onWithoutTheDeviceAdministratorAsksToActivateIt() {
        val cases = listOf(
            status(on = true),
            status(holders = listOf(self), locked = true),
            status(holders = listOf(scan), locked = true),
            status(on = true, holders = listOf(scan, block), locked = true),
            status(holders = listOf(self, web), locked = true),
        )
        for (s in cases) {
            assertTrue(s.text, s.on)
            assertTrue(s.text, s.activate)
            assertTrue(s.text, s.warn)
            assertTrue(s.text, s.text.startsWith("On"))
            assertTrue(s.text, s.text.contains("activate device admin"))
        }
    }

    @Test
    fun onWithTheDeviceAdministratorIsDone() {
        val s = status(on = true, holders = listOf(self), admin = true, locked = true)
        assertEquals("On and locked. Settings can't stop or remove Willbolt.", s.text)
        assertTrue(s.on)
        assertFalse(s.activate)
        assertFalse(s.warn)
    }

    @Test
    fun saysWhichLockHoldsItOn() {
        // Only the lock keeps it on: it goes off when that lock opens.
        assertEquals("On while Screen check is locked. Settings can't stop or remove Willbolt.", status(holders = listOf(scan), admin = true).text)
        // (a part with a plural name is "are")
        assertTrue(status(holders = listOf(web), admin = true).text.startsWith("On while Websites are locked."))
        assertTrue(status(holders = listOf(scan, web), admin = true).text.startsWith("On while 2 locks are locked."))
        // The switch is on too: the lock only stops it from being switched off.
        assertTrue(status(on = true, holders = listOf(scan), admin = true).text.startsWith("On, locked by Screen check."))
        assertTrue(status(on = true, holders = listOf(scan, web), admin = true).text.startsWith("On, locked by 2 locks."))
        // its own padlock wins: that is what it is for
        assertTrue(status(on = true, holders = listOf(scan, self), admin = true).text.startsWith("On and locked."))
    }

    @Test
    fun theSettingsLineSaysTheSameAboutLocks() {
        assertNull(heldText(on = true, holders = emptyList()))
        assertNull(heldText(on = false, holders = emptyList()))
        assertEquals("On and locked", heldText(on = true, holders = listOf(self)))
        assertEquals("On while Screen check is locked", heldText(on = false, holders = listOf(scan)))
        assertEquals("On, locked by Screen check", heldText(on = true, holders = listOf(scan)))
    }

    @Test
    fun aLongNameIsCutShort() {
        val long = Policy.Target.Block("l") to "Instagram, TikTok, YouTube, Reddit, Twitter"
        val s = status(holders = listOf(long), admin = true).text
        assertTrue(s, s.contains("…"))
        assertFalse(s, s.contains("Twitter"))
    }

    @Test
    fun noStatusIsLongEnoughToFillTheCard() {
        // Home shows this beside the padlock, so it has to stay short: about two lines.
        val sets = listOf(emptyList(), listOf(self), listOf(scan), listOf(web), listOf(block), listOf(scan, web, block), listOf(self, scan))
        for (holders in sets) for (on in listOf(true, false)) for (admin in listOf(true, false)) for (locked in listOf(true, false)) {
            val text = status(on, holders, admin, locked).text
            assertTrue("$holders $on $admin $locked: ${text.length} characters", text.length <= 85)
        }
    }
}

package app.willbolt

import app.willbolt.data.Config
import app.willbolt.data.Lock
import app.willbolt.data.LockKind
import app.willbolt.data.LockMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The locks are copied into the system settings so clearing the app's data does not drop them - uninstall protection included. */
class LockMirrorTest {
    @Test
    fun aLockComesBackWithTheUninstallProtectionItHeld() {
        val c = Config(
            scanLock = Lock(on = true, kind = LockKind.WAIT, delayMin = 120, guard = true),
            webLock = Lock(on = true, kind = LockKind.TIME, ends = 5_000L),
            selfLock = Lock(on = true, delayMin = 30),
            dnsLock = Lock(on = true, delayMin = 5, guard = true),
            clockPenaltyMs = 42L,
        )
        val back = LockMirror.decode(LockMirror.encode(c), Config())!!
        assertEquals(c.scanLock, back.scanLock)
        assertTrue(back.scanLock.guard)
        assertFalse(back.webLock.guard)
        assertEquals(c.webLock, back.webLock)
        assertEquals(c.selfLock, back.selfLock)
        assertEquals(c.dnsLock, back.dnsLock)
        assertEquals(42L, back.clockPenaltyMs)
    }

    @Test
    fun aMirrorFromBeforeTheProtectionStillRestoresItsLocks() {
        // What 0.6.3 wrote: five fields per lock, no protection bit. (The state on the user's phone.)
        val old = "v4;1,W,120,0,0;1,W,120,0,0;0,W,5,0,0;0;0,W,30,0,0"
        val back = LockMirror.decode(old, Config())!!
        assertEquals(Lock(on = true, delayMin = 120), back.scanLock)
        assertEquals(Lock(on = true, delayMin = 120), back.webLock)
        assertFalse(back.scanLock.guard || back.webLock.guard)
        assertFalse(back.selfLock.on)
    }

    @Test
    fun aPasswordLockComesBackAsAFourDayWaitAndKeepsItsProtection() {
        val c = Config(scanLock = Lock(on = true, kind = LockKind.PASSWORD, guard = true))
        val back = LockMirror.decode(LockMirror.encode(c), Config())!!
        assertEquals(LockKind.WAIT, back.scanLock.kind)
        assertEquals(4 * 24 * 60, back.scanLock.delayMin)
        assertTrue(back.scanLock.guard)
    }

    @Test
    fun nothingLockedOrNotAMirrorRestoresNothing() {
        assertNull(LockMirror.decode(LockMirror.encode(Config()), Config()))
        assertNull(LockMirror.decode("garbage", Config()))
        assertNull(LockMirror.decode("v9;1,W,1,0,0;1,W,1,0,0;1,W,1,0,0;0", Config()))
    }

    @Test
    fun `the debug build does not share the real app's mirror`() {
        assertEquals("willbolt_state", LockMirror.key("app.willbolt"))
        assertEquals("willbolt_state_app_willbolt_debug", LockMirror.key("app.willbolt.debug"))
    }
}

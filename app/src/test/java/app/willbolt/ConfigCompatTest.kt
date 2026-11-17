package app.willbolt

import app.willbolt.data.Breaks
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.Lock
import app.willbolt.data.LockKind
import app.willbolt.data.Password
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.UsageLimit
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings must survive a write and a read, and files with fields the app no longer knows must still load. */
class ConfigCompatTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun anOldStyleConfigStillLoads() {
        // Written by the old "arm" model (Bulwark 0.2): those fields are simply ignored now.
        val old = """
            {"armed":true,"armedAt":5,"lockUntil":0,"unlockDelayMinutes":5,"scanTiles":2,"scanSensitivity":60,
             "lockScan":true,"lockWeb":true,"lockSelf":true,"guardEnabled":true,"adultDomainList":true,
             "rules":[{"id":"1","name":"Social","enabled":true,"locked":true,"apps":["com.instagram.android"],"domains":["reddit.com"],
                       "schedule":{"kind":"ALWAYS","days":127,"startMin":0,"endMin":0,"until":0}}]}
        """.trimIndent()
        val c = json.decodeFromString(Config.serializer(), old)
        assertEquals(60, c.scanSensitivity)
        // fields the old file does not have take today's defaults
        assertEquals(7, c.scanIntervalSec)
        assertEquals(5_000, c.adultListSize)
        // Nothing is locked until it is locked with the new padlocks.
        assertFalse(c.scanLock.on || c.webLock.on || c.selfLock.on)
        val r = c.rules.single()
        assertEquals(RuleMode.BLOCK, r.mode)
        assertFalse(r.lock.on)
        assertEquals(setOf("reddit.com"), r.domains)
    }

    @Test
    fun aFileFrom060HasNoNoteOrGapFieldsAndStartsWithTheNote() {
        // 0.6.0 knew nothing of the first-run privacy card or the protection-gap bookkeeping.
        val c = ConfigStore.decode("""{"scanSensitivity":50,"scanLock":{"on":true,"delayMin":120}}""")
        assertFalse(c.privacyNoteSeen)
        assertEquals(0L, c.gapsCheckedMs)
        assertEquals(0L, c.offSinceMs)
        // ...nor of when the phone-maker setup was ticked as done: no stop is older than "never".
        assertEquals(0L, c.oemDoneMs)
        assertEquals(50, c.scanSensitivity)
    }

    @Test
    fun aFileFrom062KeepsItsOwnValuesAndStartsWithTheUninstallProtectionOff() {
        // 0.6.2 wrote guardEnabled and 0.6.3 a guardMode: neither exists now (the protection is held by locks, see Lock.guard).
        // New defaults only reach new installs: what a settings file says stays, and the protection is something to set up again.
        val c = ConfigStore.decode(
            """{"guardEnabled":true,"guardMode":"ALWAYS","scanEnabled":true,"adultDomainList":true,"adultListSize":1000000,
                "scanIntervalSec":2,"scanSensitivity":70,"scanLock":{"on":true,"delayMin":30}}""",
        )
        assertFalse(c.scanLock.guard)
        assertFalse(c.lockGuard)
        assertTrue(c.scanEnabled)
        assertTrue(c.adultDomainList)
        assertEquals(Config.ALL_SITES, c.adultListSize)
        assertEquals(2, c.scanIntervalSec)
        assertEquals(70, c.scanSensitivity)
        assertTrue(c.scanLock.on)
    }

    @Test
    fun theUninstallProtectionOfALockSurvivesAWriteAndRead() {
        val c = Config(
            scanLock = Lock(on = true, delayMin = 60, guard = true), selfLock = Lock(on = true, guard = true), lockGuard = true,
            rules = listOf(Rule(id = "g", name = "", apps = setOf("a"), lock = Lock(on = true, kind = LockKind.TIME, ends = 99L, guard = true))),
        )
        val back = json.decodeFromString(Config.serializer(), json.encodeToString(Config.serializer(), c))
        assertEquals(c, back)
        assertTrue(back.scanLock.guard && back.selfLock.guard && back.rules.single().lock.guard && back.lockGuard)
    }

    @Test
    fun a03LockStillLoadsAsAWaitWithItsFixedTime() {
        val old = """{"scanLock":{"on":true,"delayMin":90,"until":123456,"unlockAt":0},"lockDelayMin":90}"""
        val c = json.decodeFromString(Config.serializer(), old)
        assertEquals(Lock(on = true, kind = LockKind.WAIT, delayMin = 90, ends = 0L, until = 123_456L), c.scanLock)
        assertEquals(LockKind.WAIT, c.lockKind)
        assertNull(c.password)
    }

    @Test
    fun dnsStartsOutLockedLikeWebsitesInOlderFiles() {
        // Before 0.4.2 the Websites padlock covered DNS as well.
        val old = """{"webLock":{"on":true,"kind":"WAIT","delayMin":60},"webFilterOn":true}"""
        assertEquals(Lock(on = true, delayMin = 60), ConfigStore.decode(old).dnsLock)
        // A file that has its own DNS lock keeps it.
        val now = """{"webLock":{"on":true,"delayMin":60},"dnsLock":{"on":false}}"""
        assertFalse(ConfigStore.decode(now).dnsLock.on)
    }

    @Test
    fun locksAndTheOtherFieldsSurviveAWriteAndRead() {
        val rule = Rule(
            id = "x", name = "", apps = setOf("a"), mode = RuleMode.LIMIT,
            lock = Lock(on = true, delayMin = 90, until = 123_456L, unlockAt = 0L),
            limit = UsageLimit(minutes = 45, resetMin = 360, cooldownMin = 120),
            breaks = Breaks(enabled = true, everySec = 90, lengthSec = 20, onOpen = true, openSec = 5, pauseAway = true),
        )
        val c = Config(
            rules = listOf(rule), scanIntervalSec = 7, adultListSize = 5_000, webFilterOn = true,
            scanLock = Lock(on = true, delayMin = 30, unlockAt = 99L), selfLock = Lock(on = true, delayMin = 1_440),
            webLock = Lock(on = true, kind = LockKind.PASSWORD, ends = 777L), dnsLock = Lock(on = true, kind = LockKind.TIME, ends = 888L),
            lockKind = LockKind.TIME, password = Password("aGFzaA==", "c2FsdA==", 20_000),
            passwordResetAt = 5L, passwordFails = 3, passwordPauseUntil = 6L,
        )
        val back = json.decodeFromString(Config.serializer(), json.encodeToString(Config.serializer(), c))
        assertEquals(c, back)
    }
}

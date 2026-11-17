package app.willbolt

import app.willbolt.Edition
import app.willbolt.core.Passwords
import app.willbolt.core.Policy
import app.willbolt.core.Policy.Looser
import app.willbolt.core.Policy.State
import app.willbolt.data.Breaks
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.Lock
import app.willbolt.data.LockKind
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.Schedule
import app.willbolt.data.ScheduleKind
import app.willbolt.data.UsageLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {
    private val min = 60_000L
    private val day = 24 * 60 * min
    private val t0 = 1_000_000_000_000L

    private fun want(kind: LockKind = LockKind.WAIT, delayMin: Int = 30, ends: Long = 0L) =
        Lock(kind = kind, delayMin = delayMin, ends = ends)

    private fun lockedNow(delayMin: Int = 30, kind: LockKind = LockKind.WAIT, ends: Long = 0L) =
        Policy.locked(Lock(), want(kind, delayMin, ends), t0)!!

    @Test
    fun anOpenLockLocksWithItsWait() {
        val l = lockedNow(45)
        assertTrue(l.on)
        assertEquals(45, l.delayMin)
        assertEquals(State.LOCKED, Policy.state(l, t0))
        assertTrue(Policy.isLocked(l, t0 + 10_000 * min))
        assertEquals(State.OPEN, Policy.state(Lock(), t0))
    }

    @Test
    fun unlockingTakesTheWaitThenOpens() {
        val l = Policy.unlocking(lockedNow(30), t0)!!
        assertEquals(State.UNLOCKING, Policy.state(l, t0 + 29 * min))
        assertTrue(Policy.isLocked(l, t0 + 29 * min))
        assertEquals(State.OPEN, Policy.state(l, t0 + 30 * min))
        assertFalse(Policy.isLocked(l, t0 + 30 * min))
        // tidied up afterwards, keeping the wait as the suggestion for next time
        assertEquals(Lock(on = false, delayMin = 30), Policy.settled(l, t0 + 31 * min))
        // a second start while it is already running does nothing
        assertNull(Policy.unlocking(l, t0 + min))
    }

    @Test
    fun anUnlockCanBeCalledOff() {
        val l = Policy.unlocking(lockedNow(30), t0)!!
        val kept = Policy.keptLocked(l, t0 + 10 * min)!!
        assertEquals(State.LOCKED, Policy.state(kept, t0 + 60 * min))
        assertNull(Policy.keptLocked(kept, t0 + 60 * min)) // nothing to call off any more
    }

    @Test
    fun aWaitCanLastUntilASetTimeInsteadOfForever() {
        val l = lockedNow(30, ends = t0 + 120 * min)
        assertEquals(State.LOCKED, Policy.state(l, t0 + 60 * min))
        assertNotNull(Policy.unlocking(l, t0 + 60 * min)) // the wait still works before the end
        // at its end it opens by itself, without anyone unlocking it
        assertEquals(State.OPEN, Policy.state(l, t0 + 120 * min))
        assertEquals(Lock(on = false, delayMin = 30), Policy.settled(l, t0 + 121 * min))
        // an unlock under way opens at the end if that comes first
        val u = Policy.unlocking(l, t0 + 100 * min)!!
        assertEquals(t0 + 120 * min, Policy.opensAt(u))
        assertEquals(t0 + 130 * min, u.unlockAt)
    }

    @Test
    fun aTimedLockCannotBeUnlockedAtAllAndOpensByItself() {
        val l = lockedNow(kind = LockKind.TIME, ends = t0 + 7 * day)
        assertEquals(State.FIXED, Policy.state(l, t0 + min))
        assertNull(Policy.unlocking(l, t0 + min))
        assertNull(Policy.openedWithPassword(l, t0 + min))
        assertEquals(State.OPEN, Policy.state(l, t0 + 7 * day))
        // a timed lock needs its time, and no lock takes an end that has passed
        assertNull(Policy.locked(Lock(), want(LockKind.TIME), t0))
        assertNull(Policy.locked(Lock(), want(ends = t0 - min), t0))
    }

    @Test
    fun aPasswordLockOpensWithThePasswordNotAWait() {
        val l = lockedNow(kind = LockKind.PASSWORD)
        assertEquals(State.LOCKED, Policy.state(l, t0 + day))
        assertNull(Policy.unlocking(l, t0))
        val opened = Policy.openedWithPassword(l, t0 + min)!!
        assertEquals(State.OPEN, Policy.state(opened, t0 + min))
        assertEquals(LockKind.PASSWORD, opened.kind) // remembered for next time
        // a wait lock is not opened by the password
        assertNull(Policy.openedWithPassword(lockedNow(), t0))
    }

    @Test
    fun aLockFrom03KeepsItsFixedTimeThenTheWait() {
        val l = Lock(on = true, delayMin = 30, until = t0 + 7 * day)
        assertEquals(State.FIXED, Policy.state(l, t0 + min))
        assertNull(Policy.unlocking(l, t0 + min))
        val after = t0 + 7 * day
        assertEquals(State.LOCKED, Policy.state(l, after))
        assertEquals(after + 30 * min, Policy.unlocking(l, after)!!.unlockAt)
        assertEquals(0L, Policy.settled(l, after).until)
        // made stricter, it keeps its fixed time
        assertEquals(t0 + 7 * day, Policy.locked(l, want(delayMin = 60), t0)!!.until)
    }

    @Test
    fun aLockCanOnlyBeMadeStricter() {
        val l = lockedNow(30)
        // the wait: longer yes, shorter no
        assertNotNull(Policy.locked(l, want(delayMin = 120), t0))
        assertNull(Policy.locked(l, want(delayMin = 10), t0))
        assertEquals(Looser.SHORTER_WAIT, Policy.looserBy(l, want(delayMin = 10), t0))
        // one that stays locked can't get an end, nor become timed
        assertEquals(Looser.ENDS_SOONER, Policy.looserBy(l, want(ends = t0 + day), t0))
        assertNull(Policy.locked(l, want(LockKind.TIME, ends = t0 + 300 * day), t0))
        // another way of opening is not stricter: you may know the password
        assertEquals(Looser.OTHER_WAY, Policy.looserBy(l, want(LockKind.PASSWORD), t0))
        assertEquals(Looser.OTHER_WAY, Policy.looserBy(lockedNow(kind = LockKind.PASSWORD), want(delayMin = 60 * 24), t0))

        // with an end: later or none is fine, sooner is not
        val ending = lockedNow(30, ends = t0 + day)
        assertNotNull(Policy.locked(ending, want(ends = t0 + 2 * day), t0))
        assertNotNull(Policy.locked(ending, want(ends = 0L), t0))
        assertEquals(Looser.ENDS_SOONER, Policy.looserBy(ending, want(ends = t0 + 60 * min), t0))
        // ... and it can become timed - nothing opens it before then - if that is no sooner
        assertNotNull(Policy.locked(ending, want(LockKind.TIME, ends = t0 + day), t0))
        assertEquals(Looser.ENDS_SOONER, Policy.looserBy(ending, want(LockKind.TIME, ends = t0 + 60 * min), t0))

        // a timed lock can only end later; it can't go back to a wait
        val timed = lockedNow(kind = LockKind.TIME, ends = t0 + day)
        assertNotNull(Policy.locked(timed, want(LockKind.TIME, ends = t0 + 2 * day), t0))
        assertEquals(Looser.UNLOCKS_SOONER, Policy.looserBy(timed, want(delayMin = 600), t0))

        // making it stricter calls off a running unlock
        val running = Policy.unlocking(lockedNow(30), t0)!!
        assertEquals(0L, Policy.locked(running, want(delayMin = 30), t0 + min)!!.unlockAt)
        // an open lock takes anything
        assertNotNull(Policy.locked(Lock(delayMin = 600), want(delayMin = 5), t0))
        assertNotNull(Policy.locked(Lock(), want(LockKind.PASSWORD, ends = t0 + day), t0))
    }

    @Test
    fun thePasswordCanBeSetFreelyOnlyWhenThereIsNoneOrTheWaitIsOver() {
        val pw = Passwords.make("1234", rounds = 1_000)
        assertTrue(Passwords.matches(pw, "1234"))
        assertFalse(Passwords.matches(pw, "1235"))
        assertFalse(Passwords.matches(pw, ""))
        // salted: the same password never looks the same twice
        assertNotEquals(pw.hash, Passwords.make("1234", rounds = 1_000).hash)

        assertTrue(Policy.passwordFree(Config(), t0))
        assertFalse(Policy.passwordFree(Config(password = pw), t0))
        val asked = Config(password = pw, passwordResetAt = t0 + Passwords.RESET_WAIT_MS)
        assertFalse(Policy.passwordFree(asked, t0 + Passwords.RESET_WAIT_MS - min))
        assertTrue(Policy.passwordFree(asked, t0 + Passwords.RESET_WAIT_MS))
        assertEquals(4 * day, Passwords.RESET_WAIT_MS)

        val c = Config(password = pw, rules = listOf(Rule("p", "", apps = setOf("a"), lock = lockedNow(kind = LockKind.PASSWORD))))
        assertEquals(1, Policy.passwordLocks(c, t0).size)
    }

    @Test
    fun ruleEditsMayOnlyTighten() {
        val old = Rule("1", "Social", apps = setOf("a"), domains = setOf("x.com"), schedule = Schedule(ScheduleKind.WEEKLY, 127, 600, 900))
        // adding things and switching to "always" is fine
        assertTrue(Policy.isTightening(old, old.copy(apps = old.apps + "b")))
        assertTrue(Policy.isTightening(old, old.copy(domains = old.domains + "y.com")))
        assertTrue(Policy.isTightening(old, old.copy(schedule = Schedule())))
        // removing things, switching off, or a shorter schedule is not
        assertFalse(Policy.isTightening(old, old.copy(apps = emptySet())))
        assertFalse(Policy.isTightening(old, old.copy(domains = emptySet())))
        assertFalse(Policy.isTightening(old, old.copy(enabled = false)))
        assertFalse(Policy.isTightening(old, old.copy(schedule = Schedule(ScheduleKind.WEEKLY, 127, 600, 700))))
        // moving an "always" rule to a schedule is loosening
        val always = old.copy(schedule = Schedule())
        assertFalse(Policy.isTightening(always, old))
        // extending a "for a while" block is tightening, shortening is not
        val u = old.copy(schedule = Schedule(ScheduleKind.UNTIL, until = 1000))
        assertTrue(Policy.isTightening(u, u.copy(schedule = Schedule(ScheduleKind.UNTIL, until = 2000))))
        assertFalse(Policy.isTightening(u, u.copy(schedule = Schedule(ScheduleKind.UNTIL, until = 500))))
    }

    @Test
    fun limitsAndBreaksMayOnlyGetStricter() {
        val limit = Rule("l", "Social", apps = setOf("a"), mode = RuleMode.LIMIT, limit = UsageLimit(30, 4 * 60, 60))
        assertTrue(Policy.isTightening(limit, limit.copy(limit = limit.limit.copy(minutes = 20))))
        assertFalse(Policy.isTightening(limit, limit.copy(limit = limit.limit.copy(minutes = 40))))
        // moving the daily reset is a weakening, a longer closure is not
        assertFalse(Policy.isTightening(limit, limit.copy(limit = limit.limit.copy(resetMin = 0))))
        assertTrue(Policy.isTightening(limit, limit.copy(limit = limit.limit.copy(cooldownMin = 120))))
        assertTrue(Policy.isTightening(limit, limit.copy(limit = limit.limit.copy(cooldownMin = 0)))) // "until the reset" is longest
        assertFalse(Policy.isTightening(limit, limit.copy(limit = limit.limit.copy(cooldownMin = 30))))

        // modes: block > limit > reminders only
        assertTrue(Policy.isTightening(limit, limit.copy(mode = RuleMode.BLOCK)))
        assertFalse(Policy.isTightening(limit, limit.copy(mode = RuleMode.REMIND)))
        assertTrue(Policy.isTightening(limit.copy(mode = RuleMode.REMIND), limit))
        assertFalse(Policy.isTightening(Rule("b", "b", apps = setOf("a")), limit))

        // breaks: switching on, more often, longer and "also on open" are stricter; the opposite is not
        val withBreaks = limit.copy(breaks = Breaks(true, 600, 30, false))
        assertTrue(Policy.isTightening(limit, withBreaks))
        assertTrue(Policy.isTightening(withBreaks, withBreaks.copy(breaks = Breaks(true, 300, 60, true))))
        assertFalse(Policy.isTightening(withBreaks, withBreaks.copy(breaks = Breaks(true, 900, 30, false))))
        assertFalse(Policy.isTightening(withBreaks, withBreaks.copy(breaks = Breaks(true, 600, 20, false))))
        assertFalse(Policy.isTightening(withBreaks, withBreaks.copy(breaks = Breaks(false, 600, 30, false))))
        val onOpen = withBreaks.copy(breaks = Breaks(true, 600, 30, true))
        assertFalse(Policy.isTightening(onOpen, withBreaks))

        // the timer on opening has its own length: longer is stricter, shorter is not (an old file's 0 means the break length)
        val open = { s: Int -> onOpen.copy(breaks = onOpen.breaks.copy(openSec = s)) }
        assertTrue(Policy.isTightening(open(10), open(20)))
        assertFalse(Policy.isTightening(open(20), open(10)))
        assertTrue(Policy.isTightening(onOpen, open(30)))
        assertFalse(Policy.isTightening(onOpen, open(10)))
        assertTrue(Policy.isTightening(withBreaks, open(5))) // switching it on at all is stricter

        // pausing in other apps: switching it on is stricter, off is not
        val pausing = withBreaks.copy(breaks = withBreaks.breaks.copy(pauseAway = true))
        assertTrue(Policy.isTightening(withBreaks, pausing))
        assertFalse(Policy.isTightening(pausing, withBreaks))
    }

    @Test
    fun eachPartHasItsOwnPadlock() {
        val free = Rule("2", "free", apps = setOf("a"))
        val tight = Rule("3", "tight", apps = setOf("b"), domains = setOf("x.com"), lock = lockedNow())
        val c = Config(rules = listOf(free, tight), webLock = lockedNow())
        assertTrue(Policy.canLoosenRule(free, t0))
        assertFalse(Policy.canLoosenRule(tight, t0))
        assertTrue(Policy.canLoosenRule(null, t0))
        assertTrue(Policy.canLoosenScan(c, t0)) // not locked
        assertFalse(Policy.canLoosenWeb(c, t0)) // locked
        assertTrue(Policy.canLoosenDns(c, t0)) // DNS has a padlock of its own
        assertFalse(Policy.canLoosenDns(Config(dnsLock = lockedNow()), t0))
        // locks alone do not close Willbolt's own screens: that is the uninstall protection, which you set up (below)
        assertFalse(Policy.selfProtected(c, t0))
        assertFalse(Policy.selfProtected(Config(rules = listOf(free)), t0))
        assertEquals(2, Policy.lockedCount(c, t0))
        assertTrue(Policy.anyLocked(c, t0))
        assertFalse(Policy.anyLocked(Config(rules = listOf(free)), t0))
        // screen check, websites and DNS, plus uninstall protection in the full edition (the Play edition has no guard
        // for Willbolt itself, so it has no padlock for it), plus one row per block
        assertEquals(if (Edition.FULL) 6 else 5, Policy.allLocks(c).size)
        assertEquals("are", Policy.isOrAre(Policy.Target.Part(Policy.Area.WEB)))
        assertEquals("is", Policy.isOrAre(Policy.Target.Part(Policy.Area.DNS)))
    }

    /** A lock made with the uninstall protection chosen; it ends by itself at [ends] (0 = it stays locked until unlocked). */
    private fun guarded(ends: Long = 0L) = Policy.locked(Lock(), want(ends = ends).copy(guard = true), t0)!!

    @Test
    fun willboltGuardsItselfOnlyWhileALockThatHoldsTheProtectionIsLocked() {
        val free = Rule("f", "", apps = setOf("a"))
        val none = Config(rules = listOf(free))
        // by default nothing is set up: not even with locks on is Willbolt's own app info closed
        assertFalse(Policy.selfProtected(none, t0))
        assertFalse(Policy.selfProtected(none.copy(scanLock = lockedNow()), t0))
        assertFalse(Policy.selfProtected(none.copy(rules = listOf(free.copy(lock = lockedNow()))), t0))
        // a lock made with the protection holds it, for as long as that lock is locked (the Play edition has no guard at all)
        assertEquals(Edition.FULL, Policy.selfProtected(none.copy(scanLock = guarded()), t0))
        assertEquals(Edition.FULL, Policy.selfProtected(none.copy(rules = listOf(free.copy(lock = guarded()))), t0))
        val endsAt = none.copy(webLock = guarded(ends = t0 + 60 * min))
        assertEquals(Edition.FULL, Policy.selfProtected(endsAt, t0 + 59 * min))
        assertFalse(Policy.selfProtected(endsAt, t0 + 60 * min))
        // the flag on a lock that is not locked holds nothing
        assertFalse(Policy.selfProtected(none.copy(scanLock = Lock(on = false, guard = true)), t0))
        // the padlock on Willbolt itself is the protection and nothing else, whatever its flag says
        val padlocked = none.copy(selfLock = Policy.locked(Lock(), want(ends = t0 + 60 * min), t0)!!)
        assertEquals(Edition.FULL, Policy.selfProtected(padlocked, t0))
        assertFalse(Policy.selfProtected(padlocked, t0 + 60 * min))
    }

    @Test
    fun theLocksThatHoldTheProtectionAreListedByName() {
        val c = Config(
            scanLock = guarded(), webLock = lockedNow(), selfLock = lockedNow(),
            rules = listOf(Rule("b", "Games", apps = setOf("g"), lock = guarded()), Rule("c", "Mail", apps = setOf("m"), lock = lockedNow())),
        )
        val names = Policy.guardLocks(c, t0).map { it.second }
        assertEquals(if (Edition.FULL) listOf("Screen check", "Uninstall protection", "Games") else emptyList<String>(), names)
        assertEquals(emptyList<String>(), Policy.guardLocks(Config(scanLock = lockedNow()), t0).map { it.second })
    }

    @Test
    fun aLockCannotLetGoOfTheProtectionWhileItIsLocked() {
        val held = guarded()
        // locking it tighter keeps the protection; leaving it out would loosen the lock
        assertEquals(true, Policy.locked(held, want(delayMin = 60).copy(guard = true), t0 + min)?.guard)
        assertNull(Policy.locked(held, want(delayMin = 60), t0 + min))
        assertEquals(Looser.GUARD_OFF, Policy.looserBy(held, held.copy(guard = false), t0))
        // adding it to a lock that is locked without it is stricter
        val plain = lockedNow()
        assertNull(Policy.looserBy(plain, plain.copy(guard = true), t0))
        assertEquals(true, Policy.locked(plain, want().copy(guard = true), t0 + min)?.guard)
        // once a lock is open, the next one may be made without it
        assertEquals(false, Policy.locked(Lock(), want(), t0)?.guard)
        // and an open lock keeps nothing of it
        assertEquals(Lock(on = false, delayMin = 30), Policy.settled(Policy.unlocking(held, t0)!!, t0 + 31 * min))
    }

    @Test
    fun theSwitchTurnsTheProtectionOnAndOffWhileNoLockHoldsIt() {
        val off = Config()
        val on = Policy.guardSwitched(off, true, t0)
        if (!Edition.FULL) {
            // the Play edition has no such guard, so there is nothing to switch
            assertNull(on)
            assertFalse(Policy.selfProtected(off.copy(guardOn = true), t0))
            return
        }
        assertNotNull(on)
        assertTrue(on!!.guardOn)
        assertTrue(Policy.selfProtected(on, t0))
        // nothing holds it, so one tap turns it off again
        val back = Policy.guardSwitched(on, false, t0)
        assertNotNull(back)
        assertFalse(Policy.selfProtected(back!!, t0))
        // other locks alone don't stop that
        val scanLocked = on.copy(scanLock = lockedNow())
        assertNotNull(Policy.guardSwitched(scanLocked, false, t0))
    }

    @Test
    fun aLockThatHoldsTheProtectionKeepsTheSwitchFromTurningItOff() {
        if (!Edition.FULL) return
        val padlocked = Config(guardOn = true, selfLock = lockedNow())
        assertNull(Policy.guardSwitched(padlocked, false, t0))
        val held = Config(guardOn = true, scanLock = guarded())
        assertNull(Policy.guardSwitched(held, false, t0))
        // turning it on is always allowed (stricter)
        assertNotNull(Policy.guardSwitched(Config(scanLock = guarded()), true, t0))
        // once the padlock has opened, the switch works again - and is still on
        val opened = Config(guardOn = true, selfLock = Policy.locked(Lock(), want(ends = t0 + 60 * min), t0)!!)
        assertTrue(Policy.selfProtected(opened, t0 + 61 * min))
        assertNotNull(Policy.guardSwitched(opened, false, t0 + 61 * min))
    }

    @Test
    fun aPadlockOnTheProtectionMeansItsSwitchIsOn() {
        // 0.6.4-0.6.6 had no switch: a phone that had the padlock locked gets the switch on with it, so the protection
        // doesn't go off when that lock opens later (and a padlock brought back by the lock mirror does the same).
        val old = Config(selfLock = lockedNow())
        assertEquals(Edition.FULL, Policy.guardSwitchOf(old))
        assertFalse(Policy.guardSwitchOf(Config()))
        assertTrue(Policy.guardSwitchOf(Config(guardOn = true)))
        // an open padlock says nothing
        assertFalse(Policy.guardSwitchOf(Config(selfLock = Lock(on = false, delayMin = 30))))
        // a lock that holds the protection while locked isn't the switch: it goes off when that lock opens
        assertFalse(Policy.guardSwitchOf(Config(scanLock = guarded())))
    }

    @Test
    fun anOldGuardSettingDoesNotTurnTheProtectionOn() {
        // 0.6.3 kept a mode in the settings file; there is no such setting now, only locks that hold the protection.
        val c = ConfigStore.decode("""{"guardMode":"ALWAYS","scanLock":{"on":true,"delayMin":30}}""")
        assertFalse(Policy.selfProtected(c, t0))
        assertTrue(c.scanLock.on)
    }

    @Test
    fun theWebsiteFilterStaysOnWhileDnsOrABlockWithSitesIsLocked() {
        val sites = Rule("s", "", domains = setOf("x.com"), lock = lockedNow())
        val apps = Rule("a", "", apps = setOf("a"), lock = lockedNow())
        assertTrue(Policy.dnsKeptOn(Config(dnsLock = lockedNow()), t0))
        assertTrue(Policy.dnsKeptOn(Config(webFilterOn = true, rules = listOf(sites)), t0))
        // never started: the browser's address bar still blocks the sites, so nothing nags to start it
        assertFalse(Policy.dnsKeptOn(Config(webFilterOn = false, rules = listOf(sites)), t0))
        assertFalse(Policy.dnsKeptOn(Config(webFilterOn = true, rules = listOf(apps)), t0))
        // the Websites padlock is the adult-site list only
        assertFalse(Policy.dnsKeptOn(Config(webFilterOn = true, webLock = lockedNow()), t0))
        assertFalse(Policy.dnsKeptOn(Config(), t0))
    }
}

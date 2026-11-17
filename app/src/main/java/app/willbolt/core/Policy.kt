package app.willbolt.core

import app.willbolt.Edition
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.data.Lock
import app.willbolt.data.LockKind
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.ScheduleKind
import app.willbolt.service.GuardService

/**
 * The rules of the locks. Every block, the screen check, the website filter and Willbolt's uninstall protection has a
 * padlock of its own:
 *  - anything that makes a locked part stricter is always allowed (add an app, lower a limit, lock for longer);
 *  - anything that makes it weaker (delete a block, raise a limit, switch a feature off) needs the part unlocked first.
 *    A lock opens its own way ([LockKind]): after a wait, with the password, or not at all before its set time - and
 *    any lock can be given a time at which it opens by itself.
 * A part that is not locked can be changed freely. Lock times are TimeGuard time, so moving the clock does not help.
 */
object Policy {
    /** The parts of the app that have a lock of their own. Block rules carry theirs in [Rule.lock]. */
    enum class Area(val label: String, val plural: Boolean = false) {
        SCAN("Screen check"),
        WEB("Websites", plural = true),
        DNS("DNS"),
        SELF("Uninstall protection"),
    }

    enum class State {
        /** Not locked: anything goes. */
        OPEN,

        /** Locked; it can be unlocked its own way (start the wait, or the password). */
        LOCKED,

        /** Locked, and nothing can unlock it yet: a [LockKind.TIME] lock, or a 0.3 lock before its [Lock.until]. */
        FIXED,

        /** Locked until [Lock.unlockAt], then it opens by itself. */
        UNLOCKING,
    }

    /** How a change to a locked lock would make it looser, from some moment on. */
    enum class Looser {
        /** It would open by itself sooner (or at all, where it now stays locked). */
        ENDS_SOONER,

        /** It could be unlocked before the time it now can't be. */
        UNLOCKS_SOONER,

        /** It would open another way (a password you may know is not stricter than a wait, nor the other way round). */
        OTHER_WAY,

        SHORTER_WAIT,

        /** It would let go of the uninstall protection it holds ([Lock.guard]). */
        GUARD_OFF,
    }

    /** Something with a padlock. */
    sealed interface Target {
        data class Part(val area: Area) : Target
        data class Block(val ruleId: String) : Target
    }

    const val MAX_DELAY_MIN = 30 * 24 * 60
    private const val MIN_MS = 60_000L

    /** The longest an app can be locked out after an adult-content hit: a day, the top of the Filter screen's stepper (plus a minute). */
    private const val MAX_LOCKOUT_MS = (24 * 60 + 1) * MIN_MS

    // ---- one lock, on its own (pure, unit-tested) -------------------------------------------------------------

    fun isLocked(l: Lock, now: Long): Boolean =
        l.on && (l.ends == 0L || now < l.ends) && (l.unlockAt == 0L || now < l.unlockAt)

    fun state(l: Lock, now: Long): State = when {
        !isLocked(l, now) -> State.OPEN
        l.unlockAt != 0L -> State.UNLOCKING
        l.kind == LockKind.TIME || l.until > now -> State.FIXED
        else -> State.LOCKED
    }

    private fun endOf(l: Lock) = if (l.ends == 0L) Long.MAX_VALUE else l.ends

    /** When an unlocking lock opens: its wait is over, or its end comes first. */
    fun opensAt(l: Lock): Long = minOf(if (l.unlockAt == 0L) Long.MAX_VALUE else l.unlockAt, endOf(l))

    /** Until when nothing at all can unlock [l] ([now] if it can be unlocked its own way right away). */
    private fun noUnlockUntil(l: Lock, now: Long) = maxOf(now, l.until, if (l.kind == LockKind.TIME) l.ends else 0L)

    /**
     * Null if [new] is at least as strict as the locked [old] at every moment from [now] on, otherwise the way it is
     * not: it must end no sooner, be impossible to unlock for no less time, and - while both could be unlocked - open
     * the same way, with a wait that is no shorter - and it must keep the uninstall protection the lock holds.
     */
    fun looserBy(old: Lock, new: Lock, now: Long): Looser? {
        if (endOf(new) < endOf(old)) return Looser.ENDS_SOONER
        val free = noUnlockUntil(new, now)
        if (free < noUnlockUntil(old, now)) return Looser.UNLOCKS_SOONER
        if (free < endOf(old)) {
            if (new.kind != old.kind) return Looser.OTHER_WAY
            if (new.kind == LockKind.WAIT && new.delayMin < old.delayMin) return Looser.SHORTER_WAIT
        }
        if (old.guard && !new.guard) return Looser.GUARD_OFF
        return null
    }

    /**
     * [old] locked the way [want] says: its kind, wait, end and uninstall protection (the other fields are ignored). If it
     * is locked already this can only make it stricter, and it calls off an unlock that was under way. Null when [want] is
     * no lock (an end that has passed, a timed lock without an end) or would be looser.
     */
    fun locked(old: Lock, want: Lock, now: Long): Lock? {
        if (want.ends != 0L && want.ends <= now) return null
        if (want.kind == LockKind.TIME && want.ends == 0L) return null
        val wasLocked = isLocked(old, now)
        val next = Lock(
            on = true,
            kind = want.kind,
            delayMin = want.delayMin.coerceIn(1, MAX_DELAY_MIN),
            ends = want.ends,
            // A 0.3 lock keeps its "no unlocking before" until that is over.
            until = if (wasLocked && old.until > now) old.until else 0L,
            guard = want.guard,
        )
        if (!wasLocked) return next
        return if (looserBy(old, next, now) == null) next else null
    }

    /** Starts the wait. Null when unlocking cannot start now (not a wait lock, nothing may unlock it yet, or running). */
    fun unlocking(l: Lock, now: Long): Lock? =
        if (state(l, now) == State.LOCKED && l.kind == LockKind.WAIT) l.copy(unlockAt = now + l.delayMin * MIN_MS) else null

    /** Calls off a running unlock. Null when there is none. */
    fun keptLocked(l: Lock, now: Long): Lock? = if (state(l, now) == State.UNLOCKING) l.copy(unlockAt = 0L) else null

    /** Opened with the (already checked) password. Null unless it is a password lock that may be unlocked now. */
    fun openedWithPassword(l: Lock, now: Long): Lock? =
        if (state(l, now) == State.LOCKED && l.kind == LockKind.PASSWORD) open(l) else null

    /** An open lock that remembers how it was locked, as the suggestion for next time. */
    private fun open(l: Lock) = Lock(on = false, kind = l.kind, delayMin = l.delayMin)

    /** Tidies a lock whose wait or end is over, or whose 0.3 fixed time has passed. */
    fun settled(l: Lock, now: Long): Lock = when {
        l.on && !isLocked(l, now) -> open(l)
        l.on && l.until != 0L && l.until <= now -> l.copy(until = 0L)
        !l.on && l != open(l) -> open(l)
        else -> l
    }

    // ---- what is locked ------------------------------------------------------------------------------------------

    fun lockOf(c: Config, area: Area): Lock = when (area) {
        Area.SCAN -> c.scanLock
        Area.WEB -> c.webLock
        Area.DNS -> c.dnsLock
        Area.SELF -> c.selfLock
    }

    fun lockOf(c: Config, t: Target): Lock? = when (t) {
        is Target.Part -> lockOf(c, t.area)
        is Target.Block -> c.rules.firstOrNull { it.id == t.ruleId }?.lock
    }

    fun nameOf(c: Config, t: Target): String = when (t) {
        is Target.Part -> t.area.label
        is Target.Block -> c.rules.firstOrNull { it.id == t.ruleId }?.let { RuleEngine.displayName(it) } ?: "Block"
    }

    private fun Config.withLock(t: Target, l: Lock): Config = when (t) {
        is Target.Part -> when (t.area) {
            Area.SCAN -> copy(scanLock = l)
            Area.WEB -> copy(webLock = l)
            Area.DNS -> copy(dnsLock = l)
            Area.SELF -> copy(selfLock = l)
        }
        is Target.Block -> copy(rules = rules.map { if (it.id == t.ruleId) it.copy(lock = l) else it })
    }

    fun canLoosenScan(c: Config, now: Long = TimeGuard.now()) = !isLocked(c.scanLock, now)
    fun canLoosenWeb(c: Config, now: Long = TimeGuard.now()) = !isLocked(c.webLock, now)
    fun canLoosenDns(c: Config, now: Long = TimeGuard.now()) = !isLocked(c.dnsLock, now)

    /** Weakening or deleting [rule]. A rule that is not locked can always be changed. */
    fun canLoosenRule(rule: Rule?, now: Long = TimeGuard.now()) = rule == null || !isLocked(rule.lock, now)

    /** The padlock on the uninstall protection's switch. */
    val SELF_LOCK: Target = Target.Part(Area.SELF)

    /**
     * The locks that hold the uninstall protection on right now (full edition; the Play edition has none): its own padlock
     * while it is locked, and every other lock that is locked with the protection chosen ([Lock.guard]). While there is one,
     * the switch can't turn the protection off.
     */
    fun guardLocks(c: Config, now: Long = TimeGuard.now()): List<Triple<Target, String, Lock>> =
        if (!Edition.FULL) emptyList()
        else allLocks(c).filter { (t, _, l) -> isLocked(l, now) && (l.guard || t == SELF_LOCK) }

    /**
     * Willbolt's own app-info, uninstall and device-admin screens are closed (full edition) - while its switch is on
     * ([Config.guardOn]) or a lock holds it ([guardLocks]), and never otherwise: not by default, and not just because
     * something else is locked.
     */
    fun selfProtected(c: Config, now: Long = TimeGuard.now()): Boolean =
        Edition.FULL && (c.guardOn || guardLocks(c, now).isNotEmpty())

    /**
     * [c] with the protection's switch turned [on], or null when that is not allowed: in the Play edition (it has no such
     * guard), and turning it off while a lock holds it on. Turning it on is always allowed - it only makes things stricter.
     */
    fun guardSwitched(c: Config, on: Boolean, now: Long): Config? = when {
        !Edition.FULL -> null
        !on && guardLocks(c, now).isNotEmpty() -> null
        else -> c.copy(guardOn = on)
    }

    /**
     * Where the switch belongs: a locked (or not yet tidied) padlock on it means on. Settings from 0.6.4-0.6.6, where the
     * padlock was the only way to turn the protection on, and padlocks brought back by the lock mirror, get the switch on
     * with it - so when that lock opens the protection stays on until it is switched off.
     */
    fun guardSwitchOf(c: Config): Boolean = c.guardOn || (Edition.FULL && c.selfLock.on)

    /** Turns the protection's switch on or off (see [guardSwitched]). False when it was not allowed. */
    fun setGuard(on: Boolean): Boolean {
        val now = TimeGuard.now()
        var ok = true
        var changed = false
        ConfigStore.update { c ->
            if (c.guardOn == on) return@update c
            val next = guardSwitched(c, on, now)
            if (next == null) {
                ok = false
                return@update c
            }
            changed = true
            next
        }
        if (changed) {
            EventLog.add("ADMIN", if (on) "Uninstall protection switched on" else "Uninstall protection switched off")
        }
        return ok
    }

    /** "Websites are locked", "DNS is locked". */
    fun isOrAre(t: Target) = if (t is Target.Part && t.area.plural) "are" else "is"

    /** Every padlock with its name: the parts of the app first, then the blocks. */
    fun allLocks(c: Config): List<Triple<Target, String, Lock>> =
        // Locking Willbolt itself needs the uninstall guard, which only the full edition has.
        Area.entries.filter { Edition.FULL || it != Area.SELF }.map { Triple(Target.Part(it), it.label, lockOf(c, it)) } +
            c.rules.map { Triple(Target.Block(it.id), RuleEngine.displayName(it), it.lock) }

    fun lockedCount(c: Config, now: Long = TimeGuard.now()) = allLocks(c).count { isLocked(it.third, now) }
    fun anyLocked(c: Config, now: Long = TimeGuard.now()) = allLocks(c).any { isLocked(it.third, now) }

    /**
     * The website filter (DNS) has to stay on: DNS is locked, or the filter is on and a locked block has sites -
     * stopping it would let those sites load in apps again. (Never started, the browser's address bar still blocks them.)
     */
    fun dnsKeptOn(c: Config, now: Long = TimeGuard.now()) =
        isLocked(c.dnsLock, now) || (c.webFilterOn && c.rules.any { it.domains.isNotEmpty() && isLocked(it.lock, now) })

    // ---- changing locks --------------------------------------------------------------------------------------------

    private fun change(t: Target, f: (Lock, Long) -> Lock?): Boolean {
        // Every lock, unlock and "lock it tighter" goes through here. TimeGuard.now() compares the phone's clock with its
        // stopwatch each time it is asked, so a clock moved a moment ago is already booked: a lock that is still locked
        // can't be seen open, and so can't be replaced by a weaker one.
        val now = TimeGuard.now()
        var ok = false
        ConfigStore.update { c ->
            val old = lockOf(c, t) ?: return@update c
            val next = f(old, now) ?: return@update c
            ok = true
            c.withLock(t, next)
        }
        return ok
    }

    /**
     * Locks [t] the way [want] says, or makes its lock stricter. A password lock needs the password set first. [want]'s
     * [Lock.guard] says whether the lock also holds the uninstall protection (full edition only; the lock on Willbolt itself
     * always does, it is nothing else).
     */
    fun lock(t: Target, want: Lock): Boolean {
        if (want.kind == LockKind.PASSWORD && ConfigStore.value.password == null) return false
        // Locking DNS keeps the website filter running, so it has to be on.
        if (t == Target.Part(Area.DNS) && !ConfigStore.value.webFilterOn) return false
        val guard = Edition.FULL && (want.guard || t == SELF_LOCK)
        val ok = change(t) { l, now -> locked(l, want.copy(guard = guard), now) }
        if (ok) {
            ConfigStore.update {
                it.copy(
                    lockKind = want.kind,
                    lockDelayMin = if (want.kind == LockKind.WAIT) want.delayMin else it.lockDelayMin,
                    // (The lock on Willbolt itself says nothing about whether other locks should hold the protection.)
                    lockGuard = if (t == SELF_LOCK) it.lockGuard else guard,
                    // Locking the protection's own padlock switches it on: that is what the padlock holds.
                    guardOn = it.guardOn || (t == SELF_LOCK && Edition.FULL),
                )
            }
            EventLog.add("LOCK", "${nameOf(ConfigStore.value, t)} locked" + if (guard && t != SELF_LOCK) ", uninstall protection on" else "")
            // The guard only runs while something is locked.
            GuardService.start(ConfigStore.context)
        }
        return ok
    }

    fun startUnlock(t: Target): Boolean {
        val ok = change(t) { l, now -> unlocking(l, now) }
        if (ok) {
            val l = lockOf(ConfigStore.value, t)
            EventLog.add("LOCK", "${nameOf(ConfigStore.value, t)}: unlocking started (${RuleEngine.fmtMinutes(l?.delayMin ?: 0)})")
        }
        return ok
    }

    fun keepLocked(t: Target): Boolean {
        val ok = change(t) { l, now -> keptLocked(l, now) }
        if (ok) EventLog.add("LOCK", "${nameOf(ConfigStore.value, t)}: unlocking called off")
        return ok
    }

    // ---- the password (one for every lock) ----------------------------------------------------------------------

    enum class PwResult { OK, WRONG, PAUSED, TOO_SHORT, NOT_SET, IN_USE }

    /** A password can be set without the old one: there is none yet, or the 4-day wait asked for is over. */
    fun passwordFree(c: Config, now: Long = TimeGuard.now()) =
        c.password == null || (c.passwordResetAt != 0L && now >= c.passwordResetAt)

    /** The locks the password opens. */
    fun passwordLocks(c: Config, now: Long = TimeGuard.now()) =
        allLocks(c).filter { (_, _, l) -> l.kind == LockKind.PASSWORD && isLocked(l, now) }

    /**
     * Checks [pw] against the password. Wrong tries are counted, and every [Passwords.TRIES] in a row earn a pause, so
     * a short code cannot be guessed faster than the 4-day way round. Slow on purpose: call it off the main thread.
     */
    fun checkPassword(pw: String): PwResult {
        val c = ConfigStore.value
        val p = c.password ?: return PwResult.NOT_SET
        val now = TimeGuard.now()
        if (now < c.passwordPauseUntil) return PwResult.PAUSED
        if (Passwords.matches(p, pw)) {
            if (c.passwordFails != 0) ConfigStore.update { it.copy(passwordFails = 0, passwordPauseUntil = 0L) }
            return PwResult.OK
        }
        ConfigStore.update {
            val fails = it.passwordFails + 1
            it.copy(
                passwordFails = fails,
                passwordPauseUntil = if (fails % Passwords.TRIES == 0) now + Passwords.PAUSE_MS else it.passwordPauseUntil,
            )
        }
        return PwResult.WRONG
    }

    /** Sets a new password: freely when [passwordFree], otherwise only with the current one as [old]. Slow. */
    fun setPassword(new: String, old: String?): PwResult {
        if (new.length < Passwords.MIN_LENGTH) return PwResult.TOO_SHORT
        val c = ConfigStore.value
        if (!passwordFree(c)) {
            val r = checkPassword(old.orEmpty())
            if (r != PwResult.OK) return r
        }
        val made = Passwords.make(new)
        ConfigStore.update { it.copy(password = made, passwordResetAt = 0L, passwordFails = 0, passwordPauseUntil = 0L) }
        EventLog.add("PASSWORD", if (c.password == null) "Password set" else "Password changed")
        return PwResult.OK
    }

    /** Removes the password, on the same terms as changing it - and not while a lock uses it. Slow. */
    fun removePassword(old: String?): PwResult {
        val c = ConfigStore.value
        if (c.password == null) return PwResult.NOT_SET
        if (passwordLocks(c).isNotEmpty()) return PwResult.IN_USE
        if (!passwordFree(c)) {
            val r = checkPassword(old.orEmpty())
            if (r != PwResult.OK) return r
        }
        ConfigStore.update { it.copy(password = null, passwordResetAt = 0L, passwordFails = 0, passwordPauseUntil = 0L) }
        EventLog.add("PASSWORD", "Password removed")
        return PwResult.OK
    }

    /** "Forgot it": a new password can be set without the old one once [Passwords.RESET_WAIT_MS] have passed. */
    fun askPasswordReset() {
        val now = TimeGuard.now()
        var asked = false
        ConfigStore.update {
            if (it.password == null || it.passwordResetAt != 0L) return@update it
            asked = true
            it.copy(passwordResetAt = now + Passwords.RESET_WAIT_MS)
        }
        if (asked) EventLog.add("PASSWORD", "Asked to change the password without the old one - possible in 4 days")
    }

    fun cancelPasswordReset() {
        var had = false
        ConfigStore.update {
            if (it.passwordResetAt == 0L) return@update it
            had = true
            it.copy(passwordResetAt = 0L)
        }
        if (had) EventLog.add("PASSWORD", "Changing the password without the old one was called off")
    }

    /** Opens a password lock with [pw]. Slow: call it off the main thread. */
    fun unlockWithPassword(t: Target, pw: String): PwResult {
        val r = checkPassword(pw)
        if (r == PwResult.OK && change(t) { l, now -> openedWithPassword(l, now) }) {
            EventLog.add("LOCK", "${nameOf(ConfigStore.value, t)} unlocked with the password")
        }
        return r
    }

    // ---- rule edits (always checked here, never trusted from the UI) ------------------------------------------------

    private fun strictness(m: RuleMode) = when (m) {
        RuleMode.BLOCK -> 2
        RuleMode.LIMIT -> 1
        RuleMode.REMIND -> 0
    }

    /** 0 means "closed until the daily reset", which is the longest closure there is. */
    private fun cooldownRank(min: Int) = if (min <= 0) Int.MAX_VALUE else min

    /**
     * True if [new] is at least as strict as [old] in every way: it blocks the same apps and sites or more, it is on
     * at least as often, its limit is not higher, and its breaks are not rarer or shorter.
     */
    fun isTightening(old: Rule, new: Rule): Boolean {
        val enabledOk = new.enabled || !old.enabled
        val scheduleOk = new.schedule == old.schedule ||
            new.schedule.kind == ScheduleKind.ALWAYS ||
            (new.schedule.kind == ScheduleKind.UNTIL && old.schedule.kind == ScheduleKind.UNTIL &&
                new.schedule.until >= old.schedule.until)
        val modeOk = strictness(new.mode) >= strictness(old.mode)
        val limitOk = when {
            new.mode != RuleMode.LIMIT || old.mode != RuleMode.LIMIT -> true
            else -> new.limit.minutes <= old.limit.minutes &&
                new.limit.resetMin == old.limit.resetMin &&
                cooldownRank(new.limit.cooldownMin) >= cooldownRank(old.limit.cooldownMin)
        }
        val ob = old.breaks
        val nb = new.breaks
        val breaksOk = new.mode == RuleMode.BLOCK || !ob.enabled ||
            (nb.enabled && nb.everySec <= ob.everySec && nb.lengthSec >= ob.lengthSec &&
                (!ob.onOpen || (nb.onOpen && nb.openLength() >= ob.openLength())) &&
                (nb.pauseAway || !ob.pauseAway))
        return enabledOk && scheduleOk && modeOk && limitOk && breaksOk &&
            new.apps.containsAll(old.apps) &&
            new.domains.containsAll(old.domains)
    }

    /**
     * Adds or changes a rule. The lock of an existing rule is never changed here (see [lock]); a locked rule only takes
     * changes that make it stricter.
     */
    fun saveRule(rule: Rule): Boolean {
        val now = TimeGuard.now()
        var ok = true
        ConfigStore.update { c ->
            val old = c.rules.firstOrNull { it.id == rule.id }
            if (old == null) return@update c.copy(rules = c.rules + rule)
            val next = rule.copy(lock = old.lock)
            if (canLoosenRule(old, now) || isTightening(old, next)) {
                c.copy(rules = c.rules.map { if (it.id == rule.id) next else it })
            } else {
                ok = false
                c
            }
        }
        return ok
    }

    fun deleteRule(id: String): Boolean {
        val now = TimeGuard.now()
        var ok = true
        ConfigStore.update { c ->
            if (canLoosenRule(c.rules.firstOrNull { it.id == id }, now)) c.copy(rules = c.rules.filterNot { it.id == id })
            else { ok = false; c }
        }
        return ok
    }

    // ---- lock-outs ---------------------------------------------------------------------------------------------------

    /**
     * Until when [pkg] is locked out after an adult-content hit (TimeGuard time), or null when it is not. A value further
     * ahead than a lock-out can be is not one: it was written in another frame of time and is ignored.
     */
    fun lockoutEnd(c: Config, pkg: String, now: Long): Long? =
        c.lockouts[pkg]?.takeIf { it > now && it - now <= MAX_LOCKOUT_MS }

    private fun lockoutOver(until: Long, now: Long) = until <= now || until - now > MAX_LOCKOUT_MS

    /**
     * Periodic clean-up: opens locks whose wait or end is over (noted in Activity), drops old lockouts, and puts the
     * protection's switch on where its padlock says so ([guardSwitchOf], before that padlock is tidied away).
     */
    fun housekeeping() {
        val now = TimeGuard.now()
        val c = ConfigStore.value
        val locks = allLocks(c)
        val opened = locks.filter { (_, _, l) -> l.on && !isLocked(l, now) }.map { (t, name) -> "$name ${isOrAre(t)} unlocked" }
        val untidy = locks.any { (_, _, l) -> settled(l, now) != l }
        val oldLockouts = c.lockouts.any { lockoutOver(it.value, now) }
        val switchOff = guardSwitchOf(c) != c.guardOn
        if (untidy || oldLockouts || switchOff) {
            ConfigStore.update {
                it.copy(
                    guardOn = guardSwitchOf(it),
                    scanLock = settled(it.scanLock, now),
                    webLock = settled(it.webLock, now),
                    dnsLock = settled(it.dnsLock, now),
                    selfLock = settled(it.selfLock, now),
                    rules = it.rules.map { r -> r.copy(lock = settled(r.lock, now)) },
                    lockouts = it.lockouts.filterValues { until -> !lockoutOver(until, now) },
                )
            }
        }
        for (line in opened) EventLog.add("LOCK", line)
    }
}

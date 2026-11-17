package app.willbolt.data

import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleKind { ALWAYS, WEEKLY, UNTIL }

@Serializable
data class Schedule(
    val kind: ScheduleKind = ScheduleKind.ALWAYS,
    /** Bit 0 = Monday ... bit 6 = Sunday. Only used for WEEKLY. */
    val days: Int = ALL_DAYS,
    /** Minutes after midnight. If start == end the rule covers the whole day. */
    val startMin: Int = 0,
    val endMin: Int = 0,
    /**
     * End of a block "for a while" (epoch ms, only used for UNTIL). TimeGuard time: the phone's clock plus
     * [Config.clockPenaltyMs], so moving the phone's clock does not end it early. Blocks made before 0.6.6 hold the phone's
     * clock here, which is the same thing as long as the clock was never moved.
     */
    val until: Long = 0L,
) {
    companion object {
        const val ALL_DAYS = 0b1111111
    }
}

/** What a rule does with its apps while its schedule is in effect. Declared strictest first. */
@Serializable
enum class RuleMode {
    /** The apps (and sites) cannot be opened at all. */
    BLOCK,

    /** The apps may be used for [UsageLimit.minutes] per window, then they are closed until the window resets. */
    LIMIT,

    /** Nothing is closed; only the [Breaks] apply. */
    REMIND,
}

@Serializable
data class UsageLimit(
    /** Minutes of use allowed per window. */
    val minutes: Int = 30,
    /** A new window (and a fresh allowance) starts every day at this time. Minutes after midnight, local time. */
    val resetMin: Int = 0,
    /**
     * When the allowance is used up: 0 = closed until the next reset; otherwise closed for this many minutes
     * (or until the next reset, if that comes first), after which the allowance starts over.
     */
    val cooldownMin: Int = 0,
)

/** "Take a break" timers: a full-screen countdown over the app that has to run out before the app can be used again. */
@Serializable
data class Breaks(
    val enabled: Boolean = false,
    /** A break starts after this many seconds of use. */
    val everySec: Int = 600,
    /** How long a break lasts. */
    val lengthSec: Int = 30,
    /** Also start a break when the app is opened (after you have been away for a minute). */
    val onOpen: Boolean = false,
    /** How long that break on opening lasts. 0 = as long as [lengthSec] (files from before 0.6.8). */
    val openSec: Int = 0,
    /** A running countdown stops while another app is in use, and goes on when you come back. */
    val pauseAway: Boolean = false,
) {
    /** How long the break on opening lasts. */
    fun openLength(): Int = if (openSec > 0) openSec else lengthSec
}

/** How a locked part can be unlocked before it ends by itself. */
@Serializable
enum class LockKind {
    /** You start unlocking, then wait [Lock.delayMin] minutes. */
    WAIT,

    /** Willbolt's password opens it at once (one password for every lock, see [Config.password]). */
    PASSWORD,

    /** Not at all: it opens by itself at [Lock.ends]. */
    TIME,
}

/**
 * The lock on one part of the app: a block, the screen check, the website filter or Willbolt's uninstall protection.
 * While it is locked the part can only get stricter. The rules live in `core/Policy.kt`.
 */
@Serializable
data class Lock(
    val on: Boolean = false,
    val kind: LockKind = LockKind.WAIT,
    /** How long unlocking takes ([LockKind.WAIT]). */
    val delayMin: Int = 30,
    /** It opens by itself at this time (TimeGuard time). 0 = it stays locked until it is unlocked. */
    val ends: Long = 0L,
    /** When a started unlock completes (TimeGuard time). 0 = not unlocking. */
    val unlockAt: Long = 0L,
    /** Locks from 0.3: no unlocking before this time, then the wait. 0 = none. New locks use [ends] instead. */
    val until: Long = 0L,
    /**
     * Uninstall protection (full edition): while this lock is locked Willbolt closes its own app-info, uninstall and
     * device-admin screens, so Android's Settings cannot force-stop, clear or remove it. The protection is held by the lock,
     * so it can only be switched off the way the lock opens. Only chosen when the lock is made; it can be added to a locked
     * lock (stricter) but not taken away. (The lock on Willbolt itself, [Config.selfLock], is this and nothing else.)
     */
    val guard: Boolean = false,
)

/** The one password for every lock, as a salted PBKDF2 hash (see `core/Passwords.kt`). */
@Serializable
data class Password(val hash: String, val salt: String, val rounds: Int)

@Serializable
data class Rule(
    val id: String,
    /** Empty = named after what it blocks (see RuleEngine.displayName). */
    val name: String,
    val enabled: Boolean = true,
    val apps: Set<String> = emptySet(),
    val domains: Set<String> = emptySet(),
    val schedule: Schedule = Schedule(),
    /** A locked rule can only get stricter; deleting or weakening it needs unlocking first. */
    val lock: Lock = Lock(),
    val mode: RuleMode = RuleMode.BLOCK,
    val limit: UsageLimit = UsageLimit(),
    val breaks: Breaks = Breaks(),
)

/**
 * What the screen check does when it finds something. Declared mildest first: while the screen check is locked only a later
 * one can be picked (the Filter screen relies on this order, a test keeps it).
 */
@Serializable
enum class DetectAction { OVERLAY_ONLY, HOME, HOME_AND_LOCKOUT, LOCK_SCREEN }

@Serializable
data class Config(
    // --- locks (block rules carry their own, Rule.lock) ---------------------------
    /** Live screen check settings. */
    val scanLock: Lock = Lock(),
    /** Websites: the adult-site list. */
    val webLock: Lock = Lock(),
    /** DNS: the website filter keeps running; safe search, loopholes and the DNS server only get stricter. */
    val dnsLock: Lock = Lock(),
    /**
     * The padlock on the uninstall protection's switch ([guardOn]). Locking it switches the protection on, and while it is
     * locked the switch can't be turned off; once it opens the switch stays on until it is turned off. Locking something else
     * can also hold the protection on for as long as that lock holds ([Lock.guard]).
     */
    val selfLock: Lock = Lock(),
    /** The unlock wait offered for the next lock (the last one used). */
    val lockDelayMin: Int = 30,
    /** The way of unlocking offered for the next lock (the last one used). */
    val lockKind: LockKind = LockKind.WAIT,
    /** Whether the next lock is offered with the uninstall protection on (the last choice). Off for a new install. */
    val lockGuard: Boolean = false,
    /** Opens the locks that use [LockKind.PASSWORD]. Null = no password set yet. */
    val password: Password? = null,
    /** "Forgot it": from this time (TimeGuard) a new password can be set without the old one. 0 = not asked for. */
    val passwordResetAt: Long = 0L,
    /** Wrong passwords in a row, and the pause they earned (TimeGuard time). */
    val passwordFails: Int = 0,
    val passwordPauseUntil: Long = 0L,
    val disclosureAccepted: Boolean = false,

    // --- clock-tamper bookkeeping (see TimeGuard) --------------------------------
    val clockPenaltyMs: Long = 0L,
    val lastWallMs: Long = 0L,
    val lastElapsedMs: Long = 0L,
    val lastBootCount: Int = -1,

    // --- blocking -----------------------------------------------------------------
    val rules: List<Rule> = emptyList(),
    val allowDomains: Set<String> = emptySet(),
    /** package -> TimeGuard time until which the app is locked out after an adult-content hit (see [Policy.lockoutEnd]). */
    val lockouts: Map<String, Long> = emptyMap(),

    // --- adult content --------------------------------------------------------------
    // The screen check and the adult-site list start switched off on a new install, with the values most people end up with
    // already filled in. (Settings files that exist keep what they have; these only reach a first install.)
    val adultDomainList: Boolean = false,
    /** How many of the (most common first) adult sites are blocked. [ALL_SITES] = the whole list. */
    val adultListSize: Int = 5_000,
    val safeSearch: Boolean = true,
    val closeDnsLoopholes: Boolean = true,
    /** Comma-separated IPv4 addresses. Empty = use the network's own DNS servers. */
    val upstreamDns: String = "",
    /** You started the DNS website filter (and have not stopped it), so it is brought back after a reboot or a crash. */
    val webFilterOn: Boolean = false,

    val scanEnabled: Boolean = false,
    /** 0..100, higher = stricter (more detections). */
    val scanSensitivity: Int = 50,
    /** Seconds between two checks of the screen. */
    val scanIntervalSec: Int = 7,
    val onDetect: DetectAction = DetectAction.HOME_AND_LOCKOUT,
    val lockoutMinutes: Int = 10,
    val blockBlankBrowsers: Boolean = true,

    // --- self-protection -------------------------------------------------------------
    /**
     * The uninstall protection's switch (full edition; see Policy.selfProtected): one tap on Home turns it on or off. It is
     * also on while a lock holds it ([selfLock], or a lock made with [Lock.guard]), and can't be turned off until those open.
     * Off for a new install. (0.6.3's "guardMode" was a different setting and is not read.)
     */
    val guardOn: Boolean = false,
    val oemStepsDone: Boolean = false,
    /** Wall time at which [oemStepsDone] was last ticked. Stops the phone maker caused before it do not count against the setup. */
    val oemDoneMs: Long = 0L,

    // --- first run and protection gaps (see Gaps) -----------------------------------------
    /** The "Fully private" card at the top of Home was read and put away. */
    val privacyNoteSeen: Boolean = false,
    /** Wall time up to which Android's record of how Willbolt's process ended has been read. 0 = not yet. */
    val gapsCheckedMs: Long = 0L,
    /** Wall time since which blocking was off while something was locked (switched off or force-stopped). 0 = on. */
    val offSinceMs: Long = 0L,

    // --- support (full edition; see core/Support) ----------------------------------------
    /** How many times Willbolt has blocked something you saw, counted only up to Support.MIN_BLOCKS. */
    val blocksSeen: Int = 0,
    /** Home has asked once whether you'd like to support Willbolt; it never asks again. */
    val supportAsked: Boolean = false,

    // --- debugging ----------------------------------------------------------------------
    val hudEnabled: Boolean = false,
    val diagnostics: Boolean = false,
) {
    companion object {
        const val ALL_SITES = 1_000_000
    }
}

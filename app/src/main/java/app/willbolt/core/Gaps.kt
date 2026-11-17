package app.willbolt.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import java.text.DateFormat
import java.util.Date

/**
 * Makes sure that switching Willbolt off is never silent. It prevents nothing - the Play edition cannot, and the full
 * edition has its guard and self-repair for that. While something is locked it writes a "GAP" entry in the activity log
 * when Willbolt was force-stopped (by a person, or by the phone maker's own battery manager) or crashed, and when its
 * accessibility service was off for a while. Local only.
 *
 * It also tells the battery manager's doing from the rest: Honor's PowerGenie force-stops an app it is not told to keep
 * running as soon as the screen has been off for a while, and Android then drops the app's accessibility service, so
 * nothing is blocked until the app is opened again ([makerStops]; Home shows it, the guard keeps the fix reachable).
 */
object Gaps {
    private const val TAG = "Gaps"

    /** Shorter than this is an update or a quick off-and-on, not a gap. */
    private const val MIN_GAP_MS = 60_000L

    /** An update ends the old process the same way a Force stop does, a moment before the package's update time. */
    private const val UPDATE_WINDOW_MS = 60_000L

    /** Just after the phone started, the service coming back is the phone starting, not somebody switching it on. */
    private const val BOOT_WINDOW_MS = 5 * 60_000L

    /** Android's exit record is read at most this often when something asks again and again (Home asks every second). */
    private const val CACHE_MS = 30_000L

    enum class End { FORCE_STOP, MAKER, CRASH }

    /**
     * One end of Willbolt's process, as Android's exit history reports it. [byUser]: it was force-stopped (by anybody - the
     * history cannot say who). [crash]: it crashed or stopped answering. [note]: Android's description of who did it.
     */
    data class Exit(val at: Long, val byUser: Boolean, val crash: Boolean = false, val note: String = "")

    /** An end that left Willbolt not running; [by] says how the phone maker's manager named itself, if it was that. */
    data class Stop(val at: Long, val end: End, val by: String = "")

    // What Android writes next to a force-stop: "stop app.willbolt due to from pid 1593 iAwareR[PowerGenie inactive clean]
    // 7067ms" (Honor), against "... due to installPackageLI" for an update. Words of the makers' managers, as far as known.
    private val makerWords = listOf(
        "iaware", "powergenie", "hsm", "systemmanager", "millet", "athena", "oplus", "coloros", "iqoo", "vivo", "lool",
        "samsung", "clean", "sleep", "battery", "power",
    )

    /** Was this force-stop the phone maker's battery manager rather than somebody in Settings? */
    internal fun isMakerStop(note: String): Boolean {
        val n = note.lowercase()
        return makerWords.any { it in n }
    }

    /** "PowerGenie inactive clean": the manager's own name for it, from the brackets in the note (or the note itself). */
    internal fun nameOf(note: String): String =
        Regex("""\[([^\]]+)]""").find(note)?.groupValues?.get(1)
            ?: note.substringAfter("from pid", note).trim().substringAfter(' ', "").take(40)

    /** Every end of the process after [since] that left Willbolt not running, oldest first. Ends caused by an update at [updatedAt] don't count. */
    internal fun stops(exits: List<Exit>, since: Long, updatedAt: Long): List<Stop> =
        exits
            .filter { (it.byUser || it.crash) && it.at > since && it.at !in (updatedAt - UPDATE_WINDOW_MS)..(updatedAt + 5_000L) }
            .sortedBy { it.at }
            .map {
                when {
                    it.crash -> Stop(it.at, End.CRASH)
                    isMakerStop(it.note) -> Stop(it.at, End.MAKER, nameOf(it.note))
                    else -> Stop(it.at, End.FORCE_STOP)
                }
            }

    /** When Willbolt was force-stopped after [since], oldest first. */
    internal fun forceStops(exits: List<Exit>, since: Long, updatedAt: Long): List<Long> =
        stops(exits, since, updatedAt).filter { it.end != End.CRASH }.map { it.at }

    /** "09:43", or with the date when that was not today. */
    internal fun at(ms: Long, now: Long = System.currentTimeMillis()): String {
        val day = DateFormat.getDateInstance(DateFormat.SHORT)
        val d = Date(ms)
        return if (day.format(d) == day.format(Date(now))) DateFormat.getTimeInstance(DateFormat.SHORT).format(d)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(d)
    }

    /** What the activity log says about one end. */
    internal fun describe(s: Stop, now: Long): String {
        val time = at(s.at, now)
        return when (s.end) {
            End.FORCE_STOP -> "Force-stopped at $time. Nothing was blocked or checked until Willbolt was opened again."
            End.MAKER ->
                "The phone's battery manager stopped Willbolt at $time${if (s.by.isBlank()) "" else " (${s.by})"}. " +
                    "Nothing was blocked or checked until Willbolt was opened again."
            End.CRASH -> "Willbolt crashed at $time. Nothing was blocked or checked until it started again."
        }
    }

    // ---- the phone maker's battery manager ---------------------------------------------------------------------------

    @Volatile
    private var cacheAt = Long.MIN_VALUE / 2

    @Volatile
    private var cache: List<Stop> = emptyList()

    /** Every time the phone maker's battery manager stopped Willbolt that Android still remembers, oldest first. Android 11+. */
    fun makerStops(ctx: Context): List<Stop> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val t = SystemClock.elapsedRealtime()
        if (t - cacheAt < CACHE_MS) return cache
        cache = try {
            stops(readExits(ctx), since = 0L, updatedAt = updatedAt(ctx)).filter { it.end == End.MAKER }
        } catch (e: Throwable) {
            Log.w(TAG, "could not read the exit history", e)
            emptyList()
        }
        cacheAt = t
        return cache
    }

    /** The maker's manager stopped Willbolt within [withinMs] of [now], after you last ticked the setup as done at [doneAt]. */
    internal fun stoppedLately(stops: List<Stop>, now: Long, doneAt: Long, withinMs: Long): List<Stop> =
        stops.filter { it.end == End.MAKER && it.at > doneAt && now - it.at in 0..withinMs }

    private fun updatedAt(ctx: Context): Long = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readExits(ctx: Context): List<Exit> {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(ctx.packageName, 0, 0).map {
            Exit(
                at = it.timestamp,
                byUser = it.reason == ApplicationExitInfo.REASON_USER_REQUESTED,
                crash = it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_ANR,
                note = it.description.orEmpty(),
            )
        }
    }

    // ---- reading the record --------------------------------------------------------------------------------------------

    /** Reads how Willbolt's earlier processes ended. Called once per process start; Android 11 and newer keep that record. */
    fun scan(ctx: Context) {
        try {
            ConfigStore.init(ctx)
            val checked = ConfigStore.value.gapsCheckedMs
            val now = System.currentTimeMillis()
            ConfigStore.update { it.copy(gapsCheckedMs = now) }
            // The first time only sets the starting point: older exits were not Willbolt's business then.
            if (checked == 0L || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val stops = stops(readExits(ctx), checked, updatedAt(ctx))
            if (stops.isEmpty() || !Policy.anyLocked(ConfigStore.value, TimeGuard.now())) return
            for (s in stops) EventLog.add("GAP", describe(s, now))
            ConfigStore.update { if (it.offSinceMs == 0L) it.copy(offSinceMs = stops.first().at) else it }
        } catch (t: Throwable) {
            Log.w(TAG, "scan failed", t)
        }
    }

    /** The accessibility service went away: switched off, crashed, or the phone shut down. */
    fun serviceStopped() {
        val cfg = ConfigStore.value
        if (cfg.offSinceMs == 0L && Policy.anyLocked(cfg, TimeGuard.now())) {
            ConfigStore.update { it.copy(offSinceMs = System.currentTimeMillis()) }
        }
    }

    /** It is back. If it was gone for a while with something locked, the log says for how long. */
    fun serviceStarted() {
        val since = ConfigStore.value.offSinceMs
        if (since == 0L) return
        ConfigStore.update { it.copy(offSinceMs = 0L) }
        val off = System.currentTimeMillis() - since
        if (off < MIN_GAP_MS || SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) return
        EventLog.add(
            "GAP",
            "Blocking and checks were off for ${RuleEngine.fmtMinutes((off / 60_000L).toInt())} (since ${at(since)}). They are on again.",
        )
    }
}

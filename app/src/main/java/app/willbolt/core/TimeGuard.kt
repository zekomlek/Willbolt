package app.willbolt.core

import android.os.SystemClock
import android.provider.Settings
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import kotlin.math.abs

/**
 * Willbolt's own clock: the "now" of every lock, wait, lock-out, "block until" and schedule. Moving the phone's clock does
 * not move it.
 *
 * Within one start of the phone the wall clock and SystemClock.elapsedRealtime() (the phone's stopwatch, which nobody can
 * set) must advance together. Every time anything asks for the time the two are compared - there is no pause in between and
 * no "recently checked" shortcut - so a clock that was just moved is caught by the very next question, and no lock ever
 * looks open for a moment because of it. (Before 0.6.6 the check ran at most every five seconds, and in those seconds a
 * lock could be replaced by a weaker one.) The clock's move is booked as a penalty and taken off again, so this time carries
 * on as if nothing had happened; [ClockReceiver][app.willbolt.service.ClockReceiver] asks the moment Android says the clock
 * was set.
 *
 * Small differences are ignored: Android's own time sync is a few seconds off now and then, and the two clocks never tick
 * quite alike. They are ignored against a fixed starting point that is renewed every few minutes, so many small moves cannot
 * add up to a big one.
 *
 * After the phone was restarted the stopwatch starts again, so there only a clock that went back can be told; one that went
 * forward cannot be told from a phone that was off for that long.
 */
object TimeGuard {
    /** What was last known about the clock: the wall clock and the stopwatch at one moment, and the penalty in force then. */
    internal data class Mark(val wall: Long = 0L, val elapsed: Long = 0L, val boot: Int = -1, val penalty: Long = 0L)

    /** The trusted time, the [mark] to compare with next time, and by how much the clock was moved ([moved]; 0 = not). */
    internal class Reading(val trusted: Long, val mark: Mark, val moved: Long = 0L)

    /** The two clocks may differ this much at any moment without anybody having touched them. */
    internal const val SLACK_MS = 3_000L

    /** ...and this much more for every 10,000 ms that passed (the two never tick exactly alike: 100 parts per million). */
    private const val RATE_DIVISOR = 10_000L

    /** The starting point is renewed after this long, so what was let through can't pile up. */
    internal const val REMARK_MS = 5 * 60_000L

    /** A move of the clock this big or bigger goes in the activity log. */
    private const val NOTE_MS = 30_000L

    /**
     * Compares the clock ([wall], with the stopwatch [elapsed] and the phone's start count [boot]) with what was last known
     * ([m]) and says what the time is and what to compare with next time. Pure: nothing here reads the phone.
     */
    internal fun read(m: Mark, wall: Long, elapsed: Long, boot: Int): Reading {
        // A first start: nothing to compare with yet, so the clock is taken as it is.
        if (m.wall == 0L) return Reading(wall + m.penalty, Mark(wall, elapsed, boot, m.penalty))

        val sameStart = boot != -1 && boot == m.boot && elapsed >= m.elapsed
        if (sameStart) {
            val since = elapsed - m.elapsed
            val drift = (wall - m.wall) - since
            if (abs(drift) > SLACK_MS + since / RATE_DIVISOR) {
                // The clock was moved. What it moved by is booked, so trusted time goes on from where it was.
                val penalty = m.penalty - drift
                return Reading(wall + penalty, Mark(wall, elapsed, boot, penalty), moved = drift)
            }
            val next = if (since >= REMARK_MS) Mark(wall, elapsed, boot, m.penalty) else m
            return Reading(wall + m.penalty, next)
        }

        // The phone was started again since: the stopwatch has no memory of before, so only "earlier than last time" shows.
        val back = m.wall - wall
        if (back > SLACK_MS) {
            val penalty = m.penalty + back
            return Reading(wall + penalty, Mark(wall, elapsed, boot, penalty), moved = -back)
        }
        return Reading(wall + m.penalty, Mark(wall, elapsed, boot, m.penalty))
    }

    /** "40 s", "5 min", "3 h 5 min", "2 days 3 h". */
    internal fun span(ms: Long): String {
        val s = ms / 1000
        val d = s / 86_400
        val h = s % 86_400 / 3_600
        val m = s % 3_600 / 60
        return when {
            d > 0 -> "$d day${if (d == 1L) "" else "s"}" + if (h > 0) " $h h" else ""
            h > 0 -> "$h h" + if (m > 0) " $m min" else ""
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }

    private var mark: Mark? = null
    private var boot = Int.MIN_VALUE

    /** Trusted time for locks, waits, lock-outs and schedules. Checked against the phone's stopwatch every time. */
    @Synchronized
    fun now(): Long = step()

    /** The same check, for when only the check is wanted (the 30-second heartbeat, the clock-was-set broadcast). */
    @Synchronized
    fun tick() {
        step()
    }

    /** How many times the phone has been started (Android counts them); -1 when it cannot be read. */
    private fun bootCount(): Int {
        if (boot != Int.MIN_VALUE) return boot
        val n = try {
            Settings.Global.getInt(ConfigStore.context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: Throwable) {
            -1
        }
        if (n != -1) boot = n // (a number that could not be read is asked for again next time)
        return n
    }

    private fun step(): Long {
        // Nothing is known before the settings are loaded; no caller on the phone gets here earlier than that.
        if (!ConfigStore.ready) return System.currentTimeMillis()

        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val cfg = ConfigStore.value
        val known = mark ?: Mark(cfg.lastWallMs, cfg.lastElapsedMs, cfg.lastBootCount, cfg.clockPenaltyMs)
        val r = read(known, wall, elapsed, bootCount())
        mark = r.mark
        if (r.mark != known) {
            ConfigStore.update {
                it.copy(
                    clockPenaltyMs = r.mark.penalty, lastWallMs = r.mark.wall, lastElapsedMs = r.mark.elapsed,
                    lastBootCount = r.mark.boot,
                )
            }
        }
        if (abs(r.moved) >= NOTE_MS) {
            EventLog.add(
                "CLOCK",
                "The phone's clock was moved ${if (r.moved > 0) "forward" else "back"} by ${span(abs(r.moved))}. " +
                    "Locks and timers in Willbolt ignore that.",
                dedupeMs = 5_000,
            )
        }
        return r.trusted
    }
}

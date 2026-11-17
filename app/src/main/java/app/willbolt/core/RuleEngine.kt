package app.willbolt.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import app.willbolt.data.Breaks
import app.willbolt.data.Config
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.ScheduleKind
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object RuleEngine {
    /** Can never be blocked, so a bad rule cannot lock you out of your own phone. */
    private val alwaysAllowed = setOf(
        "android", "app.willbolt",
        "com.android.settings", "com.android.systemui",
        "com.android.phone", "com.android.server.telecom", "com.android.incallui",
        "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
        "com.android.emergency",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    )

    @Volatile
    private var dynamicAllowed: Set<String> = emptySet()

    @Volatile
    private var appCtx: Context? = null
    private val labels = ConcurrentHashMap<String, String>()

    /** Adds the current home screen and the default dialer to the never-block list. */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        val s = HashSet<String>()
        try {
            val home = ctx.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            home?.activityInfo?.packageName?.let { s.add(it) }
            ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { s.add(it) }
        } catch (_: Throwable) {
        }
        dynamicAllowed = s
    }

    fun isProtectedPackage(pkg: String) = pkg in alwaysAllowed || pkg in dynamicAllowed

    /** The app's name as the launcher shows it ("Instagram"); without a context, the last part of the package. */
    fun appLabel(pkg: String): String {
        labels[pkg]?.let { return it }
        val ctx = appCtx ?: return pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        val label = try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }
        labels[pkg] = label
        return label
    }

    /** The rule's name, or - when it has none - what it covers: "Instagram", "Instagram, TikTok +2", "example.com". */
    fun displayName(r: Rule): String {
        r.name.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val things = r.apps.map { appLabel(it) }.sortedBy { it.lowercase() } + r.domains.sorted()
        return when (things.size) {
            0 -> when (r.mode) {
                RuleMode.BLOCK -> "Block"
                RuleMode.LIMIT -> "Time limit"
                RuleMode.REMIND -> "Breaks"
            }
            1, 2 -> things.joinToString(", ")
            else -> things.take(2).joinToString(", ") + " +${things.size - 2}"
        }
    }

    /**
     * Is [r] in effect at [now]? [now] is TimeGuard time, so changing the phone's clock moves neither a "block until" nor the
     * hours of a weekly schedule. (The time of day is that of the phone's time zone.)
     */
    fun isActive(r: Rule, now: Long): Boolean {
        if (!r.enabled) return false
        val s = r.schedule
        return when (s.kind) {
            ScheduleKind.ALWAYS -> true
            ScheduleKind.UNTIL -> now < s.until
            ScheduleKind.WEEKLY -> {
                val cal = Calendar.getInstance().apply { timeInMillis = now }
                // Calendar: SUNDAY=1 .. SATURDAY=7  ->  Monday=0 .. Sunday=6
                val today = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                val yesterday = (today + 6) % 7
                val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val todayOn = ((s.days shr today) and 1) == 1
                val yesterdayOn = ((s.days shr yesterday) and 1) == 1
                when {
                    s.startMin == s.endMin -> todayOn
                    s.startMin < s.endMin -> todayOn && minute >= s.startMin && minute < s.endMin
                    else -> (todayOn && minute >= s.startMin) || (yesterdayOn && minute < s.endMin)
                }
            }
        }
    }

    /** The "block completely" rule (in effect) that covers [pkg]. Time limits and break timers are handled by [UsageEngine]. */
    fun blockedApp(cfg: Config, pkg: String, now: Long = TimeGuard.now()): Rule? {
        if (isProtectedPackage(pkg)) return null
        for (r in cfg.rules) {
            if (r.mode == RuleMode.BLOCK && pkg in r.apps && isActive(r, now)) return r
        }
        return null
    }

    fun blockedDomainRule(cfg: Config, host: String, now: Long = TimeGuard.now()): Rule? {
        val candidates = suffixes(host)
        for (r in cfg.rules) {
            if (r.mode != RuleMode.BLOCK || r.domains.isEmpty() || !isActive(r, now)) continue
            if (candidates.any { it in r.domains }) return r
        }
        return null
    }

    /** "a.b.example.com" -> [a.b.example.com, b.example.com, example.com] (never a bare TLD). */
    fun suffixes(host: String): List<String> {
        val parts = host.trim('.').lowercase().split('.')
        if (parts.size < 2) return listOf(host.lowercase())
        return (0..parts.size - 2).map { i -> parts.drop(i).joinToString(".") }
    }

    fun normalizeDomain(input: String): String? {
        var t = input.trim().lowercase()
        if (t.isEmpty()) return null
        t = t.removePrefix("https://").removePrefix("http://")
        val end = t.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ':' }
        if (end >= 0) t = t.substring(0, end)
        t = t.removePrefix("www.").trim('.')
        if (!t.contains('.')) return null
        if (!t.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return t
    }

    /** "Always on", "Until 21/11/2026 18:00", "Mon Tue, 09:00-17:00". [penalty] turns a "block until" (TimeGuard time) into the phone's clock. */
    fun describeSchedule(r: Rule, penalty: Long = 0L): String {
        val s = r.schedule
        return when (s.kind) {
            ScheduleKind.ALWAYS -> "Always on"
            ScheduleKind.UNTIL -> "Until " + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
            ).format(java.util.Date(s.until - penalty))
            ScheduleKind.WEEKLY -> {
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                val days = if (s.days == 0b1111111) "Every day" else names.filterIndexed { i, _ -> (s.days shr i) and 1 == 1 }.joinToString(" ")
                val time = if (s.startMin == s.endMin) "all day" else "${fmt(s.startMin)}-${fmt(s.endMin)}"
                "$days, $time"
            }
        }
    }

    fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** "30 min", "2 h", "1 h 30 min". */
    fun fmtMinutes(m: Int): String = when {
        m < 60 -> "$m min"
        m % 60 == 0 -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }

    /** "45 s", "2 min", "1 min 30 s". */
    fun fmtSeconds(s: Int): String = when {
        s < 60 -> "$s s"
        s % 60 == 0 -> "${s / 60} min"
        else -> "${s / 60} min ${s % 60} s"
    }

    fun describeBreaks(b: Breaks): String = buildString {
        append("Break every ").append(fmtSeconds(b.everySec)).append(" for ").append(fmtSeconds(b.lengthSec))
        if (b.onOpen) {
            if (b.openLength() == b.lengthSec) append(", also when opened")
            else append(", ").append(fmtSeconds(b.openLength())).append(" when opened")
        }
        if (b.pauseAway) append(", pauses in other apps")
    }

    /** One line for the rule list: what the rule does and when. */
    fun describeRule(r: Rule, penalty: Long = 0L): String = when (r.mode) {
        RuleMode.BLOCK -> describeSchedule(r, penalty)
        RuleMode.LIMIT -> buildString {
            append(fmtMinutes(r.limit.minutes)).append(" a day, resets at ").append(fmt(r.limit.resetMin))
            if (r.limit.cooldownMin > 0) append(", closed for ").append(fmtMinutes(r.limit.cooldownMin)).append(" after")
            if (r.schedule.kind != ScheduleKind.ALWAYS) append(" (").append(describeSchedule(r, penalty)).append(")")
            if (r.breaks.enabled) append("\n").append(describeBreaks(r.breaks))
        }
        RuleMode.REMIND -> buildString {
            append(if (r.breaks.enabled) describeBreaks(r.breaks) else "Break reminders (off)")
            if (r.schedule.kind != ScheduleKind.ALWAYS) append(" (").append(describeSchedule(r, penalty)).append(")")
        }
    }
}

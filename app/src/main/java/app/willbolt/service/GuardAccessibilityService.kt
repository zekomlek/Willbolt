package app.willbolt.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import app.willbolt.R
import app.willbolt.core.AdultList
import app.willbolt.core.DomainPolicy
import app.willbolt.core.Gaps
import app.willbolt.core.Policy
import app.willbolt.core.RuleEngine
import app.willbolt.core.Support
import app.willbolt.core.TimeGuard
import app.willbolt.core.UsageEngine
import app.willbolt.core.Watchdog
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.DetectAction
import app.willbolt.data.EventLog
import app.willbolt.data.Rule
import app.willbolt.data.RuleUsage
import app.willbolt.data.UsageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.FileDescriptor
import java.io.PrintWriter
import java.text.DateFormat
import java.util.Date

/**
 * The heart of the app. It sees window changes, and:
 *  - presses Home and shows a card when a blocked app (or a locked-out app, or one whose time limit is used up) comes to the front;
 *  - lays the countdown of a "take a break" timer over an app until it has run out;
 *  - reads the browser's address bar and, for a blocked site, goes Back off the page (Home if that does not work);
 *  - in the full edition, while the uninstall protection is on, bounces out of the screens where Willbolt could be
 *    stopped or uninstalled (TamperGuard; the Play edition has an empty one);
 *  - hosts the live screen check and the usage counter.
 */
class GuardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WillboltA11y"

        // Address-bar checks: at once on the first event (then at most this often while events keep coming)...
        private const val URL_CHECK_GAP_MS = 300L
        // ...once more when the events stop, so the address the browser settles on is always looked at...
        private const val URL_SETTLE_MS = 450L
        // ...and every so often while a browser stays in front, events or not.
        private const val URL_POLL_MS = 1_500L
        // A blocked site that is still on screen this soon after Back gets Home instead.
        private const val SITE_BACK_WINDOW_MS = 4_000L

        @Volatile
        var instance: GuardAccessibilityService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val overlay by lazy { BlockOverlay(this) }
    private val breakOverlay by lazy { BreakOverlay(this) }
    val hud by lazy { ScoreHud(this) }
    private var scanner: ScreenScanner? = null
    private var usageTracker: UsageTracker? = null

    private val appLabel by lazy { getString(R.string.app_name).lowercase() }
    private val labelCache = HashMap<String, String>()

    /** Package of the app currently in front (best effort). */
    @Volatile
    var foregroundPkg: String = ""
        private set

    /** Apps with a visible window right now (best effort; empty while Willbolt's own screens are in front). */
    @Volatile
    private var visiblePkgs: Set<String> = emptySet()

    fun visiblePackages(): Set<String> = visiblePkgs

    private var lastBlockAt = 0L
    private var lastWindowCheck = 0L
    private var windowCheckPending = false
    private var lastUrlCheck = 0L
    private var lastGuardCheck = 0L
    private var guardPending = false
    private var guardPkg = ""
    private var guardClass: String? = null
    private val delayedWindowCheck = Runnable {
        windowCheckPending = false
        runWindowCheck(null)
    }
    private val delayedGuardCheck = Runnable {
        guardPending = false
        guardCheck()
    }
    private val urlCheck = Runnable { checkBrowserNow() }

    /** Every 30 seconds while the service runs: the clock check, lock housekeeping, and bringing the filter back. */
    private val heartbeat = object : Runnable {
        override fun run() {
            try {
                TimeGuard.tick()
                Policy.housekeeping()
                Watchdog.checkAll(applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "heartbeat failed", t)
            }
            handler.postDelayed(this, 30_000L)
        }
    }
    private var siteBlockHost = ""
    private var siteBlockAt = 0L

    /** Recent address-bar readings for `dumpsys` (only kept while "Guard diagnostics" is on). Main thread only. */
    private val urlNotes = ArrayDeque<String>()
    private var lastNote = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val app = applicationContext
        ConfigStore.init(app)
        EventLog.init(app)
        UsageStore.init(app)
        RuleEngine.init(app)
        BreakOverlay.restoreSound(app)
        AdultList.preload(app)
        BrowserUrl.discover(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scanner = ScreenScanner(this, scope).also { it.start() }
        }
        usageTracker = UsageTracker(this, scope).also { it.start() }
        GuardService.start(this)
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, 30_000L)
        EventLog.add("SERVICE", "Accessibility service connected")
        Gaps.serviceStarted()
    }

    // ---- events ----------------------------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            process(event)
        } catch (t: Throwable) {
            Log.e(TAG, "event handling failed", t)
        }
    }

    override fun onInterrupt() {}

    private fun process(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString()
        val type = e.eventType
        if (pkg == packageName) {
            // Our own screens: remember we are in front (so the scanner skips them), but ignore our overlay windows.
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.className?.toString()?.startsWith("app.willbolt.ui.") == true) {
                usageTracker?.tickNow() // the time up to now still belongs to the app we are leaving
                foregroundPkg = packageName
                visiblePkgs = emptySet()
                usageTracker?.tickNow()
                TamperGuard.forget() // no Settings screen is in front any more
            }
            return
        }

        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onWindowsChanged(pkg)
        }
        if (pkg == null) return

        if (TamperGuard.isSensitive(pkg)) {
            guardPkg = pkg
            guardClass = e.className?.toString()
            TamperGuard.note(pkg, e.className, type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, SystemClock.elapsedRealtime())
            guardCheck()
        }
        if (BrowserUrl.isBrowser(pkg)) scheduleBrowserCheck()
    }

    /**
     * Runs the uninstall guard on the screen in front - or, if it ran a moment ago, once more shortly after, so a dialog
     * that appears right behind another event is looked at too (a still dialog sends no further events).
     */
    private fun guardCheck() {
        val cfg = ConfigStore.value
        if (!TamperGuard.applies(cfg, TimeGuard.now())) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastGuardCheck > 250) {
            lastGuardCheck = now
            TamperGuard.check(this, overlay, cfg, guardPkg, guardClass, appLabel)
        } else if (!guardPending) {
            guardPending = true
            handler.postDelayed(delayedGuardCheck, 300)
        }
    }

    // ---- blocked apps --------------------------------------------------------------------------------------------

    private fun onWindowsChanged(eventPkg: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWindowCheck < 120) {
            if (!windowCheckPending) {
                windowCheckPending = true
                handler.postDelayed(delayedWindowCheck, 160)
            }
            return
        }
        runWindowCheck(eventPkg)
    }

    private fun runWindowCheck(eventPkg: String?) {
        lastWindowCheck = SystemClock.elapsedRealtime()
        val cfg = ConfigStore.value
        val pkgs = visibleAppPackages().ifEmpty { listOfNotNull(eventPkg) }
        if (pkgs.isEmpty()) return
        // Time up to now belongs to the apps that were visible before; then the new set starts counting.
        usageTracker?.tickNow()
        visiblePkgs = pkgs.filter { it != packageName }.toSet()
        usageTracker?.tickNow()

        // Everything below is judged on TimeGuard time, so moving the phone's clock opens nothing.
        val trusted = TimeGuard.now()
        for (p in pkgs) {
            if (p == packageName || RuleEngine.isProtectedPackage(p)) continue
            val rule = RuleEngine.blockedApp(cfg, p, trusted)
            if (rule != null) {
                val named = rule.name.trim().takeIf { it.isNotEmpty() }?.let { "\"$it\"" }
                blockApp(p, listOfNotNull(named, RuleEngine.describeSchedule(rule, cfg.clockPenaltyMs)).joinToString(" - "))
                return
            }
            val lockEnd = Policy.lockoutEnd(cfg, p, trusted)
            if (lockEnd != null) {
                blockApp(
                    p,
                    "locked out after an adult-content hit - back at " +
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lockEnd - cfg.clockPenaltyMs)),
                )
                return
            }
            val closed = UsageEngine.closedRule(cfg.rules, p, trusted, UsageStore::get)
            if (closed != null) {
                blockApp(p, limitReason(closed, UsageStore.get(closed.id)), "is closed for now")
                return
            }
        }
        pkgs.firstOrNull { it != packageName }?.let { foregroundPkg = it }
    }

    // ---- time limits and break timers ---------------------------------------------------------------------------

    /** "daily limit used up - back at 06:00" (or with the date, when that is not today). */
    private fun limitReason(rule: Rule, usage: RuleUsage): String {
        val opens = Date(usage.blockedUntil - ConfigStore.value.clockPenaltyMs)
        val sameDay = DateFormat.getDateInstance(DateFormat.SHORT).format(opens) ==
            DateFormat.getDateInstance(DateFormat.SHORT).format(Date())
        val at = if (sameDay) DateFormat.getTimeInstance(DateFormat.SHORT).format(opens)
        else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(opens)
        val named = rule.name.trim().takeIf { it.isNotEmpty() }?.let { "\"$it\": " }.orEmpty()
        return "$named${RuleEngine.fmtMinutes(rule.limit.minutes)} used up - back at $at"
    }

    /** The allowance of [rule] ran out while one of its apps was on screen. */
    fun onLimitReached(rule: Rule, usage: RuleUsage) {
        val pkg = visiblePkgs.firstOrNull { it in rule.apps } ?: return
        EventLog.add("LIMIT", "${RuleEngine.displayName(rule)}: ${RuleEngine.fmtMinutes(rule.limit.minutes)} used up (${labelOf(pkg)})", dedupeMs = 30_000)
        breakOverlay.hide()
        blockApp(pkg, limitReason(rule, usage), "is closed for now")
    }

    fun onOneMinuteLeft(rule: Rule) {
        Toast.makeText(this, "${RuleEngine.displayName(rule)}: 1 minute left", Toast.LENGTH_LONG).show()
    }

    /** A break of [rule] just started over one of its apps (a paused one going on again is not a new one). */
    fun onBreakStarted(rule: Rule, usage: RuleUsage) {
        val pkg = visiblePkgs.firstOrNull { it in rule.apps } ?: return
        val len = (if (usage.breakLenMs > 0L) usage.breakLenMs else rule.breaks.lengthSec * 1000L) / 1000L
        EventLog.add("BREAK", "${labelOf(pkg)} - ${RuleEngine.fmtSeconds(len.toInt())} timer", dedupeMs = 20_000)
    }

    /** Shows the countdown of [rule]'s running break over its app, or removes it when there is none. */
    fun syncBreak(rule: Rule?) {
        if (rule == null) {
            if (breakOverlay.isShowing) breakOverlay.hide()
            return
        }
        val usage = UsageStore.get(rule.id)
        val until = usage.breakUntil
        if (breakOverlay.isShowing && breakOverlay.deadline == until) return
        val pkg = visiblePkgs.firstOrNull { it in rule.apps } ?: return
        val total = if (usage.breakLenMs > 0L) usage.breakLenMs else rule.breaks.lengthSec * 1000L
        breakOverlay.show(labelOf(pkg), until, total, rule.breaks.pauseAway) { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    @Suppress("DEPRECATION")
    private fun visibleAppPackages(): List<String> {
        val out = LinkedHashSet<String>()
        val list = try {
            windows
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        for (w in list) {
            if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                val root = try {
                    w.root
                } catch (_: Throwable) {
                    null
                }
                if (root != null) {
                    root.packageName?.toString()?.let { out.add(it) }
                    root.recycle()
                }
            }
            w.recycle()
        }
        return out.toList()
    }

    private fun blockApp(pkg: String, reason: String, title: String = "is blocked") {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 900) return
        lastBlockAt = now
        val label = labelOf(pkg)
        overlay.show("$label $title", reason)
        performGlobalAction(GLOBAL_ACTION_HOME)
        // Once it is in the background, stop it for good (music, video or a game would otherwise keep going).
        handler.postDelayed({ closeInBackground(pkg) }, 800)
        if (EventLog.add("APP_BLOCK", "$label - $reason", dedupeMs = 5_000)) Support.noteBlock()
    }

    /** Stops the app's background processes. Does nothing to an app that is in front again by now. */
    private fun closeInBackground(pkg: String) {
        if (pkg == packageName || RuleEngine.isProtectedPackage(pkg) || pkg in visiblePkgs) return
        try {
            getSystemService(android.app.ActivityManager::class.java)?.killBackgroundProcesses(pkg)
        } catch (t: Throwable) {
            Log.w(TAG, "could not close $pkg", t)
        }
    }

    // ---- blocked sites -------------------------------------------------------------------------------------------------

    /** Looks at once (at most every [URL_CHECK_GAP_MS]) and again when the events stop, so the address the browser settles on is always checked. */
    private fun scheduleBrowserCheck() {
        handler.removeCallbacks(urlCheck)
        if (SystemClock.elapsedRealtime() - lastUrlCheck >= URL_CHECK_GAP_MS) checkBrowserNow()
        handler.postDelayed(urlCheck, URL_SETTLE_MS)
    }

    @Suppress("DEPRECATION")
    private fun checkBrowserNow() {
        handler.removeCallbacks(urlCheck)
        lastUrlCheck = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            if (!BrowserUrl.isBrowser(pkg)) return
            // Keep looking while a browser stays in front: a page can open without an event reaching us.
            if (getSystemService(PowerManager::class.java)?.isInteractive != false) handler.postDelayed(urlCheck, URL_POLL_MS)
            val cfg = ConfigStore.value
            val reading = BrowserUrl.read(root)
            if (cfg.diagnostics) note("$pkg: ${reading.seen}")
            val host = reading.host ?: return
            val verdict = DomainPolicy.verdict(cfg, host) ?: return
            blockSite(host, verdict)
        } finally {
            root.recycle()
        }
    }

    /**
     * Back first: it takes the tab off the page, so the browser does not open on it again next time. If the page is
     * still there a moment later (nothing to go back to), Home.
     */
    private fun blockSite(host: String, verdict: DomainPolicy.Verdict) {
        val now = SystemClock.elapsedRealtime()
        val backFailed = host == siteBlockHost && now - siteBlockAt < SITE_BACK_WINDOW_MS
        siteBlockHost = host
        siteBlockAt = now
        overlay.show("Site blocked", "$host - ${verdict.reason}")
        performGlobalAction(if (backFailed) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        if (EventLog.add("SITE_BLOCK", "$host - ${verdict.reason}", dedupeMs = 5_000)) Support.noteBlock()
        if (ConfigStore.value.diagnostics) note("blocked $host - ${if (backFailed) "Home" else "Back"}")
        // Look again soon: did Back leave the page?
        handler.removeCallbacks(urlCheck)
        handler.postDelayed(urlCheck, 900)
    }

    private fun note(text: String) {
        if (text == lastNote) return
        lastNote = text
        urlNotes.addLast("${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date())} $text")
        while (urlNotes.size > 60) urlNotes.removeFirst()
    }

    // ---- called by the screen scanner ---------------------------------------------------------------------------------------

    fun overlayShowing() = overlay.isShowing || breakOverlay.isShowing

    fun onNsfwDetected(score: Float, pkg: String) {
        val cfg = ConfigStore.value
        val label = labelOf(pkg)
        EventLog.add("NSFW", "$label - score ${"%.2f".format(score)}")
        Support.noteBlock()
        when (cfg.onDetect) {
            DetectAction.OVERLAY_ONLY -> overlay.show("Adult content detected", label)
            DetectAction.HOME -> {
                overlay.show("Adult content detected", "Taking you home.")
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            DetectAction.HOME_AND_LOCKOUT -> {
                overlay.show("Adult content detected", "$label is locked for ${cfg.lockoutMinutes} minutes.")
                performGlobalAction(GLOBAL_ACTION_HOME)
                if (!RuleEngine.isProtectedPackage(pkg)) {
                    // On TimeGuard time, like everything else: the phone's clock cannot be moved past it.
                    val until = TimeGuard.now() + cfg.lockoutMinutes * 60_000L
                    ConfigStore.update { it.copy(lockouts = it.lockouts + (pkg to until)) }
                }
            }
            DetectAction.LOCK_SCREEN -> {
                overlay.show("Adult content detected", "Locking the screen.")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
        }
    }

    /** A browser whose window screenshots come back black: probably a private tab Willbolt cannot see into. */
    fun onBlankBrowser(pkg: String) {
        val label = labelOf(pkg)
        overlay.show(
            "Private tab blocked",
            "$label is showing a page Willbolt cannot check (usually a private / incognito tab).",
        )
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (EventLog.add("BLANK", "$label - unscannable window, sent home", dedupeMs = 10_000)) Support.noteBlock()
    }

    // ---- misc -----------------------------------------------------------------------------------------------------------------

    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        } catch (_: Throwable) {
            pkg
        }
    }

    /**
     * `adb shell dumpsys activity service app.willbolt/.service.GuardAccessibilityService [fields|tree|tree all]`:
     * recent address-bar readings, what the browser in front shows now, and with "fields" the address fields found,
     * with "tree" the whole window (lists and web pages left out unless "all"). Only adb and the system can call this.
     */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        val w = writer ?: return
        val cfg = ConfigStore.value
        w.println("foreground: $foregroundPkg  visible: $visiblePkgs")
        w.println("address-bar readings (Guard diagnostics ${if (cfg.diagnostics) "on" else "off: switch it on to record them"}):")
        urlNotes.forEach { w.println("  $it") }
        dumpWindow(w, args?.firstOrNull(), all = args?.getOrNull(1) == "all")
    }

    @Suppress("DEPRECATION")
    private fun dumpWindow(w: PrintWriter, what: String?, all: Boolean) {
        val root = rootInActiveWindow ?: run { w.println("no active window"); return }
        try {
            val pkg = root.packageName?.toString().orEmpty()
            w.println("window in front: $pkg")
            if (what == "tree") dumpTree(w, root, all)
            if (!BrowserUrl.isBrowser(pkg)) return
            if (what == "fields") {
                BrowserUrl.inspect(root).forEach { (id, f) -> w.println("  field $id: $f") }
            }
            w.println("reading now: ${BrowserUrl.read(root).seen}")
        } finally {
            root.recycle()
        }
    }

    private fun dumpTree(w: PrintWriter, root: AccessibilityNodeInfo, all: Boolean) {
        var budget = 800
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            w.println(
                buildString {
                    append("  ".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.'))
                    n.viewIdResourceName?.let { append(" #").append(it.substringAfter(":id/")) }
                    n.text?.let { append(" \"").append(it.toString().take(80)).append('"') }
                    n.contentDescription?.let { append(" desc=\"").append(it.toString().take(50)).append('"') }
                    if (n.isFocused) append(" FOCUSED")
                    if (n.isShowingHintText) append(" HINT")
                    if (n.isEditable) append(" editable")
                    if (n.textSelectionStart >= 0) append(" sel=${n.textSelectionStart}..${n.textSelectionEnd}")
                    if (n.isScrollable) append(" scrollable")
                    if (n.collectionInfo != null) append(" list")
                    if (!n.isVisibleToUser) append(" hidden")
                    append(' ').append(r.toShortString())
                },
            )
            if (!all && BrowserUrl.isListOrPage(n)) {
                if (n.childCount > 0) w.println("  ".repeat(depth + 1) + "(${n.childCount} children left out)")
                return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 1)
    }

    private fun teardown() {
        if (instance === this) instance = null
        handler.removeCallbacks(delayedWindowCheck)
        handler.removeCallbacks(delayedGuardCheck)
        handler.removeCallbacks(urlCheck)
        handler.removeCallbacks(heartbeat)
        scanner?.stop()
        scanner = null
        usageTracker?.stop()
        usageTracker = null
        scope.cancel()
        overlay.hide()
        breakOverlay.hide()
        hud.hide()
        EventLog.add("SERVICE", "Accessibility service disconnected")
        Gaps.serviceStopped()
        // If it was switched off while something is locked, the watchdog's observer turns it back on; also nudge it now.
        Watchdog.checkAll(applicationContext)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) teardown()
        super.onDestroy()
    }
}

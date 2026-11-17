package app.willbolt.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.core.AdultList
import app.willbolt.core.Policy
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.DetectAction
import app.willbolt.ml.NsfwClassifier
import app.willbolt.service.GuardAccessibilityService
import app.willbolt.vpn.DnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val ACTION_LABELS = mapOf(
    DetectAction.OVERLAY_ONLY to "Just warn me",
    DetectAction.HOME to "Go to the home screen",
    DetectAction.HOME_AND_LOCKOUT to "Home, and pause that app",
    DetectAction.LOCK_SCREEN to "Lock the phone",
)

/** What to do when something is found, in the order [DetectAction] is declared in: strictest last, and while locked only a later one can be picked. */
private val ACTIONS = DetectAction.entries.map { it to (ACTION_LABELS[it] ?: it.name) }

private fun actionLabel(a: DetectAction) = ACTION_LABELS[a] ?: a.name

private val DNS_CHOICES = listOf(
    "" to "My network's DNS",
    "1.1.1.3, 1.0.0.3" to "Cloudflare Family",
    "185.228.168.168, 185.228.169.168" to "CleanBrowsing Family",
)

private enum class FilterInfo { SCAN, WEB, DNS }

@Composable
fun FilterScreen() {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    // Each half has its own padlock; a half that is not locked can be changed freely.
    val canScan = Policy.canLoosenScan(cfg, now)
    val canWeb = Policy.canLoosenWeb(cfg, now)
    val canDns = Policy.canLoosenDns(cfg, now)
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<FilterInfo?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            testing = true
            testResult = null
            scope.launch {
                testResult = withContext(Dispatchers.Default) { testImage(ctx, uri, cfg.scanSensitivity) }
                testing = false
            }
        }
    }

    FixHost { fix ->
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ScreenHeader("Filter", "Adult content, on screen and on the web.")

            // ---- screen check -------------------------------------------------------------------------------
            Group(
                "Screen check",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { info = FilterInfo.SCAN }) {
                            Icon(LineIcons.Info, contentDescription = "About the screen check", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PadlockButton(cfg.scanLock, now) { nav.openLock(Policy.Target.Part(Policy.Area.SCAN)) }
                    }
                },
            ) {
                if (!canScan) LockedNote("Locked - these can only get stricter.")
                val running = GuardAccessibilityService.instance != null
                SwitchItem(
                    "Check the screen",
                    if (running) "A model on this phone looks at what is on screen" else "Needs the accessibility service (Home)",
                    checked = cfg.scanEnabled,
                    enabled = canScan || !cfg.scanEnabled,
                ) { on -> ConfigStore.update { it.copy(scanEnabled = on) } }
                GroupDivider()
                // While locked, a number that can only get stricter is picked first and applied with Confirm, because a
                // stricter value can't be taken back (see rememberPending).
                val every = rememberPending(
                    cfg.scanIntervalSec, locked = !canScan,
                    // Checking more often is stricter; less often is a weakening.
                    clamp = { it.coerceAtMost(cfg.scanIntervalSec) },
                    save = { v -> ConfigStore.update { it.copy(scanIntervalSec = v) } },
                )
                ItemRow(
                    "Check every",
                    "Shorter uses more battery",
                    trailing = {
                        Stepper(every.shown, every.pick, 1..30, unit = "s", allowed = { canScan || it <= cfg.scanIntervalSec })
                    },
                )
                ConfirmRow(
                    every,
                    "From ${cfg.scanIntervalSec} s to ${every.waiting} s. While it is locked it can't be made longer again.",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                GroupDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    // While locked, the slider and the box only pick a higher value and Confirm applies it. Saving every
                    // step of a drag raised the floor under your finger, so a slider that went too far could not come back.
                    val sens = rememberPending(
                        cfg.scanSensitivity, locked = !canScan,
                        clamp = { it.coerceAtLeast(cfg.scanSensitivity) },
                        save = { v -> ConfigStore.update { it.copy(scanSensitivity = v) } },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Sensitivity", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Higher catches more, and more swimwear",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        NumberBox(
                            sens.shown,
                            sens.pick,
                            0..100,
                            allowed = { canScan || it >= cfg.scanSensitivity },
                            width = 64.dp,
                        )
                    }
                    Slider(
                        value = sens.shown.toFloat(),
                        onValueChange = { v -> sens.pick(v.roundToInt()) },
                        valueRange = 0f..100f,
                    )
                    ConfirmRow(sens, "From ${cfg.scanSensitivity} to ${sens.waiting}. While it is locked it can't be lowered again.")
                }
                GroupDivider()
                ItemRow("When something is found")
                // While locked, a stricter way is picked first and applied with Confirm, like the numbers above: it can't be
                // taken back, so a tap that was meant for another line must not be final.
                val order = ACTIONS.map { it.first }
                val savedAt = order.indexOf(cfg.onDetect)
                val action = rememberPending(
                    savedAt, locked = !canScan,
                    clamp = { it.coerceAtLeast(savedAt) },
                    save = { i -> ConfigStore.update { it.copy(onDetect = order[i.coerceIn(0, order.lastIndex)]) } },
                )
                RadioRows(
                    ACTIONS,
                    selected = order[action.shown.coerceIn(0, order.lastIndex)],
                    onPick = { a -> action.pick(order.indexOf(a)) },
                    enabled = { a -> canScan || order.indexOf(a) >= savedAt },
                    extra = { a ->
                        if (a == DetectAction.HOME_AND_LOCKOUT) {
                            val lockout = rememberPending(
                                cfg.lockoutMinutes, locked = !canScan,
                                clamp = { it.coerceAtLeast(cfg.lockoutMinutes) },
                                save = { v -> ConfigStore.update { it.copy(lockoutMinutes = v) } },
                            )
                            Column {
                                Row(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("for", style = MaterialTheme.typography.bodyLarge)
                                    Spacer(Modifier.width(12.dp))
                                    Stepper(
                                        lockout.shown, lockout.pick, 1..1_440,
                                        step = 5, unit = "min", allowed = { canScan || it >= cfg.lockoutMinutes },
                                    )
                                }
                                ConfirmRow(
                                    lockout,
                                    "From ${cfg.lockoutMinutes} to ${lockout.waiting} min. While it is locked it can't be shortened again.",
                                    Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                                )
                            }
                        }
                    },
                )
                ConfirmRow(
                    action,
                    "From \"${actionLabel(cfg.onDetect)}\" to \"${actionLabel(order[(action.waiting ?: savedAt).coerceIn(0, order.lastIndex)])}\". " +
                        "While it is locked it can't be made milder again.",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
                GroupDivider()
                SwitchItem(
                    "Block private tabs",
                    "Android hides them from screenshots, so they can't be checked",
                    checked = cfg.blockBlankBrowsers,
                    enabled = canScan || !cfg.blockBlankBrowsers,
                ) { on -> ConfigStore.update { it.copy(blockBlankBrowsers = on) } }
            }

            // ---- websites --------------------------------------------------------------------------------------
            Group(
                "Websites",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { info = FilterInfo.WEB }) {
                            Icon(LineIcons.Info, contentDescription = "About the adult-site list", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        PadlockButton(cfg.webLock, now) { nav.openLock(Policy.Target.Part(Policy.Area.WEB)) }
                    }
                },
            ) {
                if (!canWeb) LockedNote("Locked - these can only get stricter.")
                val stats = AdultList.stats
                val total = stats.available
                SwitchItem(
                    "Block adult sites",
                    when {
                        !cfg.adultDomainList -> "Off - the list is not even loaded"
                        AdultList.ready && stats.loaded > 0 -> "${fmtCount(stats.loaded)} sites, checked offline"
                        else -> "Loading the list..."
                    },
                    checked = cfg.adultDomainList,
                    enabled = canWeb || !cfg.adultDomainList,
                ) { on -> ConfigStore.update { it.copy(adultDomainList = on) } }
                if (cfg.adultDomainList) {
                    GroupDivider()
                    val shownSize = if (total > 0) cfg.adultListSize.coerceAtMost(total) else cfg.adultListSize.coerceAtMost(Config.ALL_SITES)
                    val size = rememberPending(
                        shownSize, locked = !canWeb,
                        // A smaller list is a weakening.
                        clamp = { it.coerceAtLeast(shownSize) },
                        save = { v -> ConfigStore.update { it.copy(adultListSize = if (total in 1..v) Config.ALL_SITES else v) } },
                    )
                    val shownPick = if (total > 0) size.shown.coerceAtMost(total) else size.shown
                    ItemRow(
                        "How many",
                        "Most visited first" + if (total > 0) ", of ${fmtCount(total)}" else "",
                        trailing = {
                            NumberBox(
                                shownPick,
                                size.pick,
                                100..Config.ALL_SITES,
                                allowed = { canWeb || it >= shownSize },
                                width = 96.dp,
                                live = false,
                            )
                        },
                    )
                    Chips(
                        listOf(1_000 to "1,000", 5_000 to "5,000", 20_000 to "20,000", Config.ALL_SITES to "All"),
                        selected = if (total > 0 && size.shown >= total) Config.ALL_SITES else size.shown,
                        onPick = size.pick,
                        enabled = { n -> canWeb || n >= cfg.adultListSize },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    val sites = { n: Int -> if (n >= Config.ALL_SITES || (total > 0 && n >= total)) "all" else fmtCount(n) }
                    ConfirmRow(
                        size,
                        "From ${sites(shownSize)} to ${sites(size.waiting ?: 0)} sites. While it is locked the list can't be made smaller again.",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }

            // ---- DNS -----------------------------------------------------------------------------------------------
            // Everything below the filter works through it, so it waits greyed out until the filter is started; so does
            // the padlock, since locking DNS keeps the filter running.
            val running = DnsVpnService.running
            val keptOn = Policy.dnsKeptOn(cfg, now)
            Group(
                "DNS",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { info = FilterInfo.DNS }) {
                            Icon(LineIcons.Info, contentDescription = "About the DNS filter", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (running || !canDns) {
                            PadlockButton(cfg.dnsLock, now) { nav.openLock(Policy.Target.Part(Policy.Area.DNS)) }
                        }
                    }
                },
            ) {
                if (!canDns) LockedNote("Locked - the filter stays on and these can only get stricter.")
                ItemRow(
                    "Website filter",
                    when {
                        running && DnsVpnService.bypassedByPrivateDns -> "Running, but skipped: set Private DNS to Automatic"
                        running -> "Running - blocked sites don't load in any app"
                        DnsVpnService.pausedAfterFailure() -> "Paused after a failed self-test, retries every 10 min"
                        else -> "Off - only the browser's address bar is checked"
                    },
                    leading = { IconDot(LineIcons.Globe, if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, 32.dp) },
                    trailing = {
                        when {
                            !running -> FilledTonalButton(onClick = { fix("vpn") }) { Text("Start") }
                            // Stopping the filter is a weakening while DNS (or a block with sites) is locked.
                            !keptOn -> TextButton(onClick = { stopWebFilter(ctx) }) { Text("Stop") }
                            else -> {}
                        }
                    },
                )
                if (running && canDns && keptOn) {
                    Hint(
                        "A locked block has websites, so the filter stays on until that block is unlocked.",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
                GroupDivider()
                SwitchItem(
                    "Safe search",
                    "Google, Bing, DuckDuckGo and YouTube in family mode",
                    checked = cfg.safeSearch,
                    enabled = running && (canDns || !cfg.safeSearch),
                ) { on -> ConfigStore.update { it.copy(safeSearch = on) } }
                GroupDivider()
                SwitchItem(
                    "Close DNS loopholes",
                    "Blocks encrypted DNS and undoes a custom Private DNS",
                    checked = cfg.closeDnsLoopholes,
                    enabled = running && (canDns || !cfg.closeDnsLoopholes),
                ) { on -> ConfigStore.update { it.copy(closeDnsLoopholes = on) } }
                GroupDivider()
                ItemRow("Where allowed lookups go", enabled = running)
                RadioRows(
                    DNS_CHOICES,
                    selected = DNS_CHOICES.firstOrNull { it.first == cfg.upstreamDns }?.first ?: "",
                    onPick = { v -> ConfigStore.update { it.copy(upstreamDns = v) } },
                    // A family DNS server is extra filtering; going back to your own is a weakening.
                    enabled = { v -> running && (canDns || cfg.upstreamDns.isEmpty() || v == cfg.upstreamDns) },
                )
                if (!running) {
                    Hint(
                        "These work through the website filter, so they wait until it is started.",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    )
                }
            }

            // ---- tools -------------------------------------------------------------------------------------------
            Group("Tune it") {
                ItemRow(
                    if (testing) "Checking..." else "Test with a picture",
                    testResult ?: "Runs a picture from your gallery through the model. Nothing is saved or sent.",
                    leading = { IconDot(LineIcons.Picture, size = 32.dp) },
                    enabled = !testing,
                    onClick = { imagePicker.launch("image/*") },
                )
                GroupDivider()
                SwitchItem(
                    "Show live score",
                    "A small readout in the corner: the latest score and how long the check took",
                    checked = cfg.hudEnabled,
                ) { on -> ConfigStore.update { it.copy(hudEnabled = on) } }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    when (info) {
        null -> {}
        FilterInfo.SCAN -> InfoDialog(
            "Screen check",
            "Every few seconds, while the screen is on, Android hands Willbolt a screenshot and a small AI model on this phone looks at it. " +
                "A screen that has not changed since the last check is skipped. Pictures stay in memory for a moment and are never saved or sent.\n\n" +
                "On your phone one check takes about half a second, so 2 s keeps the phone busy about a quarter of the time while the screen changes. The suggested 7 s is easy on the battery.\n\n" +
                "Turn on the live score (below) and browse for a while to find your sensitivity.",
        ) { info = null }
        FilterInfo.WEB -> InfoDialog(
            "Websites",
            "The adult-site list is sorted by how often each site is visited, so a smaller list still covers the big ones. " +
                "A lookup is a single check, so a bigger list does not slow the phone down; it only takes a little memory.\n\n" +
                "Blocked sites are caught in the browser's address bar. With the website filter (DNS, below) running they don't " +
                "load in any app either.",
        ) { info = null }
        FilterInfo.DNS -> InfoDialog(
            "DNS",
            "The website filter is a DNS-only VPN on this phone: it answers lookups for blocked sites with nothing, so they " +
                "don't load in any browser or app. Nothing is routed through a server.\n\n" +
                "Safe search, closing DNS loopholes and the choice of DNS server all work through it, so they stay greyed out " +
                "until it is started.\n\n" +
                "Once it runs, DNS gets a padlock of its own: locked, the filter keeps running and these settings can only get " +
                "stricter.",
        ) { info = null }
    }
}

/** Runs one picture from the gallery through the model, the same way the live check would. */
private fun testImage(ctx: Context, uri: Uri, sensitivity: Int): String {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return "Could not open that picture."
        val scaled = Bitmap.createScaledBitmap(bmp, NsfwClassifier.SIZE, NsfwClassifier.SIZE, true)
        val clf = NsfwClassifier.create(ctx)
        val p = try {
            clf.nsfwProbability(scaled)
        } finally {
            clf.close()
        }
        val (thr, strong) = NsfwClassifier.thresholds(sensitivity)
        val verdict = when {
            p >= strong -> "blocked straight away"
            p >= thr -> "blocked if it stays for two checks"
            else -> "not blocked at this sensitivity"
        }
        "Score %.0f%% - %s.".format(p * 100f, verdict)
    } catch (t: Throwable) {
        "Could not run the model: ${t.message}"
    }
}

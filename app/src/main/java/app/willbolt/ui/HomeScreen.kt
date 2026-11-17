package app.willbolt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.Edition
import app.willbolt.R
import app.willbolt.core.Check
import app.willbolt.core.Policy
import app.willbolt.core.RuleEngine
import app.willbolt.core.UsageEngine
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.EventLog
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.RuleUsage
import app.willbolt.data.ScheduleKind
import app.willbolt.data.UsageStore

@Composable
fun HomeScreen() {
    val nav = LocalNav.current
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val usage by UsageStore.state.collectAsStateWithLifecycle()
    val events by EventLog.events.collectAsStateWithLifecycle()
    val now = rememberNow()
    val checks = rememberChecks()

    var passwordOpen by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf(false) }
    if (feedback) FeedbackDialog(onDismiss = { feedback = false })

    FixHost { fix ->
        val serviceOn = checks.firstOrNull { it.id == "accessibility" }?.ok != false
        val critical = checks.filter { !it.ok && it.critical && it.id != "accessibility" }
        // Optional extras (the screen-check model) are not nagged about here, and the device administrator has its own card.
        val todo = checks.filter { !it.ok && !it.critical && it.actionLabel != null && it.id != "admin" }
        val admin = checks.firstOrNull { it.id == "admin" }?.ok == true
        val limits = cfg.rules.filter { it.enabled && it.mode == RuleMode.LIMIT }
        val parts = activeParts(
            cfg, now,
            scanReady = checks.firstOrNull { it.id == "scanner" }?.ok == true,
            dnsRunning = checks.firstOrNull { it.id == "vpn" }?.ok == true,
        )

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(painterResource(R.drawable.willbolt_mark), contentDescription = null, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Willbolt", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { feedback = true }) { Icon(LineIcons.Chat, contentDescription = "Send feedback") }
                    IconButton(onClick = { nav.openSettings() }) { Icon(LineIcons.Tune, contentDescription = "Settings") }
                }
            }
            item {
                // New installs get the full promise first; after "Got it" it stays as one line under the title.
                if (cfg.privacyNoteSeen) {
                    PrivacyBadge { nav.openLegal(LegalDoc.Privacy, fromSettings = false) }
                } else {
                    PrivacyCard(
                        onPolicy = { nav.openLegal(LegalDoc.Privacy, fromSettings = false) },
                        onGotIt = { ConfigStore.update { it.copy(privacyNoteSeen = true) } },
                    )
                }
            }
            item {
                StatusCard(
                    cfg = cfg,
                    serviceOn = serviceOn,
                    critical = critical,
                    parts = parts,
                    now = now,
                    onTurnOn = { fix("accessibility") },
                    onNewBlock = { nav.openEditor(null) },
                    onLocks = { nav.locksOpen = true },
                )
            }
            // Uninstall protection: important enough for Home, small enough not to push everything else off it.
            item { UninstallCard(cfg, now, admin, onFix = { fix(it) }) }
            if (critical.isNotEmpty()) {
                item {
                    Group("Needs attention") {
                        critical.forEachIndexed { i, c ->
                            if (i > 0) GroupDivider()
                            CheckRow(c) { fix(c.id) }
                        }
                    }
                }
            }
            if (todo.isNotEmpty()) {
                item {
                    Group {
                        ItemRow(
                            title = "Finish setup",
                            subtitle = if (todo.size == 1) todo[0].title else "${todo.size} steps left: " + todo.joinToString(", ") { it.title },
                            leading = { IconDot(LineIcons.Tune, MaterialTheme.colorScheme.tertiary) },
                            trailing = { Icon(LineIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            onClick = { nav.openSettings() },
                        )
                    }
                }
            }
            if (limits.isNotEmpty()) {
                item {
                    Group("Today") {
                        limits.forEachIndexed { i, r ->
                            if (i > 0) GroupDivider()
                            LimitRow(r, usage[r.id] ?: RuleUsage(), now, cfg.clockPenaltyMs) { nav.openEditor(r.id) }
                        }
                    }
                }
            }
            item {
                Group { PasswordRow(cfg, now) { passwordOpen = true } }
            }
            // "Buy me a coffee" (full edition only; the Play edition has no outside payment links).
            if (Edition.FULL) item { SupportRow() }
            item {
                val recent = events.asReversed().filter { it.type != "GUARD" }.take(4)
                Group("Recent", trailing = { TextButton(onClick = { nav.tab = Tab.Activity }) { Text("See all") } }) {
                    if (recent.isEmpty()) {
                        ItemRow("Nothing yet", "Blocks, checks and lock changes show up here.")
                    }
                    recent.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        EventRow(e)
                    }
                }
            }
        }
        // Asked once, after a few days and a few blocks (full edition; see core/Support) - never over another dialog, and
        // not while Willbolt is off.
        SupportPrompt(cfg, now, allowed = serviceOn && !passwordOpen && !feedback)
    }
    if (passwordOpen) PasswordDialog(onDismiss = { passwordOpen = false })
}

/**
 * What is actually working right now: "2 blocks", "1 scheduled", "screen check", "adult sites", "safe search".
 * Paused and finished blocks don't count, nor does safe search while the website filter isn't running.
 */
internal fun activeParts(cfg: Config, now: Long, scanReady: Boolean, dnsRunning: Boolean): List<String> {
    val parts = ArrayList<String>()
    val live = cfg.rules.filter { it.enabled && !(it.schedule.kind == ScheduleKind.UNTIL && now >= it.schedule.until) }
    val active = live.count { RuleEngine.isActive(it, now) }
    if (active > 0) parts += if (active == 1) "1 block" else "$active blocks"
    if (live.size > active) parts += "${live.size - active} scheduled"
    if (cfg.scanEnabled && scanReady) parts += "screen check"
    if (cfg.adultDomainList) parts += "adult sites"
    if (cfg.safeSearch && dnsRunning) parts += "safe search"
    return parts
}

/** The dark teal card at the top: is Willbolt on, what does it do, what is locked. */
@Composable
private fun StatusCard(
    cfg: Config,
    serviceOn: Boolean,
    critical: List<Check>,
    parts: List<String>,
    now: Long,
    onTurnOn: () -> Unit,
    onNewBlock: () -> Unit,
    onLocks: () -> Unit,
) {
    val title: String
    val body: String
    when {
        !serviceOn -> {
            title = "Willbolt is off"
            body = "Turn on its accessibility service. Until then nothing is blocked or checked."
        }
        critical.isNotEmpty() -> {
            title = "Needs attention"
            body = critical.joinToString("\n") { it.title }
        }
        parts.isEmpty() -> {
            title = "Nothing is on"
            body = "Willbolt is running, but no block, screen check or site filter is switched on."
        }
        else -> {
            title = "Willbolt is on"
            body = parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
        }
    }
    val working = serviceOn && critical.isEmpty() && parts.isNotEmpty()
    val locked = Policy.lockedCount(cfg, now)
    val unlocking = Policy.allLocks(cfg).count { Policy.state(it.third, now) == Policy.State.UNLOCKING }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Palette.Deep,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Palette.Deep, Palette.Abyss)))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = Palette.Cream)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = Palette.Cream.copy(alpha = 0.78f))
                }
                Spacer(Modifier.width(12.dp))
                Surface(
                    shape = CircleShape,
                    color = if (working) Palette.Aqua else Palette.Cream.copy(alpha = 0.18f),
                    modifier = Modifier.size(14.dp),
                ) {}
            }
            Spacer(Modifier.height(16.dp))
            // The button keeps its own width; the pill takes what is left (it was the button that got squeezed - "New bloc k" -
            // when the pill read "2 locked · 1 unlocking").
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onLocks,
                    shape = CircleShape,
                    color = Palette.Cream.copy(alpha = 0.12f),
                    contentColor = Palette.Cream,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (locked > 0) LineIcons.Lock else LineIcons.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            // An unlocking part is still locked; count it once, as "unlocking".
                            when {
                                locked == 0 -> "Nothing locked"
                                unlocking == 0 -> "$locked locked"
                                locked == unlocking -> "$unlocking unlocking"
                                else -> "${locked - unlocking} locked · $unlocking unlocking"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!serviceOn || (critical.isEmpty() && parts.isEmpty())) {
                    Button(
                        onClick = if (!serviceOn) onTurnOn else onNewBlock,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Aqua, contentColor = Palette.Deep),
                    ) { Text(if (!serviceOn) "Turn on" else "New block", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/** "Instagram, TikTok   12 of 30 min" with a bar, or "closed until 04:00". */
@Composable
private fun LimitRow(r: Rule, u: RuleUsage, now: Long, penalty: Long, onClick: () -> Unit) {
    val closed = u.blockedUntil > now
    val used = UsageEngine.refresh(r, u, now).usedMs
    val total = r.limit.minutes * 60_000L
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                RuleEngine.displayName(r),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (closed) "closed until " + Fmt.whenAt(wallOf(u.blockedUntil, penalty))
                else "${used / 60_000L} of ${r.limit.minutes} min",
                style = MaterialTheme.typography.bodyMedium,
                color = if (closed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (closed) 1f else (used.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (closed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

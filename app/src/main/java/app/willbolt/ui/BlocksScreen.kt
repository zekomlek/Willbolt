package app.willbolt.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.R
import app.willbolt.core.AdultList
import app.willbolt.core.Policy
import app.willbolt.core.RuleEngine
import app.willbolt.core.UsageEngine
import app.willbolt.data.ConfigStore
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.RuleUsage
import app.willbolt.data.ScheduleKind
import app.willbolt.data.UsageStore

@Composable
fun BlocksScreen() {
    val nav = LocalNav.current
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val usage by UsageStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 100.dp)) {
            item {
                val n = cfg.rules.size
                val locked = cfg.rules.count { Policy.isLocked(it.lock, now) }
                // The adult-site list (Filter tab) blocks on top of these.
                val loaded = AdultList.stats.loaded
                val adult = when {
                    !cfg.adultDomainList -> null
                    AdultList.ready && loaded > 0 -> "also ${fmtCount(loaded)} adult sites"
                    else -> "also the adult-site list"
                }
                val parts = listOfNotNull(if (n == 1) "1 block" else "$n blocks", if (locked > 0) "$locked locked" else null, adult)
                ScreenHeader("Blocks", subtitleContent = if (n == 0) null else ({ DotLine(parts) }))
                Spacer(Modifier.height(4.dp))
            }
            if (cfg.rules.isEmpty()) {
                item { EmptyBlocks { nav.openEditor(null) } }
            }
            items(cfg.rules, key = { it.id }) { r ->
                BlockCard(
                    r, usage[r.id] ?: RuleUsage(), now, cfg.clockPenaltyMs,
                    onOpen = { nav.openEditor(r.id) },
                    onLock = { nav.openLock(Policy.Target.Block(r.id)) },
                )
            }
            if (cfg.rules.isNotEmpty()) {
                item {
                    Hint(
                        "Tap a padlock to lock that block. A locked block can only get stricter until you unlock it.",
                        Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    )
                }
            }
        }
        // Out of the way while the "Changes not saved" popup sits where it would be.
        if (cfg.rules.isNotEmpty() && nav.unsaved == null) ExtendedFloatingActionButton(
            onClick = { nav.openEditor(null) },
            icon = { Icon(LineIcons.Plus, contentDescription = null) },
            text = { Text("New block") },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
private fun EmptyBlocks(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.willbolt_mark), contentDescription = null, modifier = Modifier.size(84.dp))
        Spacer(Modifier.height(16.dp))
        Text("No blocks yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Block an app or a website, give an app a daily time limit, or make it take breaks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd) { Text("New block") }
    }
}

/**
 * "Blocked", "Blocked now · Mon Tue, 09:00-17:00", "12 of 30 min today", "Closed until 04:00", "Paused". [now] is TimeGuard time;
 * the [penalty] turns the times kept in it (a "block until", the end of a closure) into the phone's clock for showing.
 */
private fun blockStatus(r: Rule, u: RuleUsage, now: Long, penalty: Long): Pair<String, Boolean> {
    if (!r.enabled) return "Paused" to false
    val s = r.schedule
    if (s.kind == ScheduleKind.UNTIL && now >= s.until) return "Ended " + Fmt.whenAt(wallOf(s.until, penalty)) to false
    val active = RuleEngine.isActive(r, now)
    val schedule = when (s.kind) {
        ScheduleKind.ALWAYS -> null
        ScheduleKind.UNTIL -> "until " + Fmt.whenAt(wallOf(s.until, penalty))
        ScheduleKind.WEEKLY -> RuleEngine.describeSchedule(r)
    }
    return when (r.mode) {
        RuleMode.BLOCK -> when {
            schedule == null -> "Blocked" to true
            active -> "Blocked $schedule" to true
            else -> "Off now · $schedule" to false
        }
        RuleMode.LIMIT -> when {
            u.blockedUntil > now -> "Closed until " + Fmt.whenAt(wallOf(u.blockedUntil, penalty)) to true
            else -> {
                val used = UsageEngine.refresh(r, u, now).usedMs / 60_000L
                "$used of ${r.limit.minutes} min today" + (schedule?.let { " · $it" } ?: "") to active
            }
        }
        RuleMode.REMIND ->
            "Break every ${fmtDuration(r.breaks.everySec * 1000L)}" + (schedule?.let { " · $it" } ?: "") to active
    }
}

/** What the block covers, for blocks that have a name of their own. */
private fun contents(r: Rule): String =
    (r.apps.map { RuleEngine.appLabel(it) }.sortedBy { it.lowercase() } + r.domains.sorted()).joinToString(", ")

@Composable
private fun BlockCard(
    r: Rule,
    u: RuleUsage,
    now: Long,
    penalty: Long,
    onOpen: () -> Unit,
    onLock: () -> Unit,
) {
    val (status, live) = blockStatus(r, u, now, penalty)
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BlockIcons(r, Modifier.alpha(if (live) 1f else 0.55f))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    RuleEngine.displayName(r),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (r.name.isNotBlank()) {
                    Text(
                        contents(r),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            PadlockButton(r.lock, now, onLock)
        }
    }
}

/** Up to three app icons, overlapping; a globe for a block of websites only. */
@Composable
private fun BlockIcons(r: Rule, modifier: Modifier = Modifier) {
    val apps = r.apps.sorted().take(3)
    Box(modifier.size(48.dp), contentAlignment = Alignment.CenterStart) {
        if (apps.isEmpty()) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                IconDot(LineIcons.Globe, size = 44.dp)
            }
        } else {
            val step = if (apps.size == 1) 0.dp else 20.dp / (apps.size - 1)
            apps.forEachIndexed { i, pkg ->
                AppIcon(
                    pkg,
                    size = if (apps.size == 1) 44.dp else 30.dp,
                    modifier = Modifier.offset(x = step * i, y = if (apps.size == 1) 0.dp else if (i % 2 == 0) (-6).dp else 6.dp),
                )
            }
        }
    }
}

/** An app's launcher icon, round-cornered. */
@Composable
fun AppIcon(pkg: String, size: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp: ImageBitmap? = remember(pkg) {
        try {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
    if (bmp != null) {
        Image(
            bmp,
            contentDescription = null,
            modifier = modifier.size(size).clip(MaterialTheme.shapes.small)
                .border(1.dp, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small),
        )
    } else {
        Box(modifier.size(size).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small))
    }
}

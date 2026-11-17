package app.willbolt.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.core.Policy
import app.willbolt.core.RuleEngine
import app.willbolt.core.TimeGuard
import app.willbolt.data.Breaks
import app.willbolt.data.ConfigStore
import app.willbolt.data.Lock
import app.willbolt.data.Rule
import app.willbolt.data.RuleMode
import app.willbolt.data.Schedule
import app.willbolt.data.ScheduleKind
import app.willbolt.data.UsageLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

private enum class TimeField { START, END, RESET }

/**
 * Everything typed into a block's page, kept outside the page itself: leaving without saving can then be undone, and
 * the "Changes not saved" popup brings the page back exactly as it was, scroll position included.
 */
@Stable
class BlockDraft(
    /** The block as it was when the page opened (null for a new one): what a locked block may not go below. */
    val initial: Rule?,
) {
    val oldUntil = initial?.schedule?.takeIf { it.kind == ScheduleKind.UNTIL }?.until ?: 0L

    var name by mutableStateOf(initial?.name ?: "")
    var mode by mutableStateOf(initial?.mode ?: RuleMode.BLOCK)
    var enabled by mutableStateOf(initial?.enabled ?: true)
    var kind by mutableStateOf(initial?.schedule?.kind ?: ScheduleKind.ALWAYS)
    var days by mutableIntStateOf(initial?.schedule?.days ?: Schedule.ALL_DAYS)
    var startMin by mutableIntStateOf(initial?.schedule?.startMin ?: 9 * 60)
    var endMin by mutableIntStateOf(initial?.schedule?.endMin ?: 17 * 60)

    // "For a while": how long from now. An existing one starts at what is left, rounded up to the hour. (A block's end is kept
    // in TimeGuard time, like a lock's, so moving the phone's clock does not end it early.)
    var forSec by mutableLongStateOf(
        TimeGuard.now().let { now -> if (oldUntil > now) ((oldUntil - now + 3_599_999L) / 3_600_000L) * 3_600L else 3_600L },
    )
    var forTouched by mutableStateOf(false)
    var apps by mutableStateOf(initial?.apps ?: emptySet())
    var sites by mutableStateOf(initial?.domains ?: emptySet())
    var siteInput by mutableStateOf("")
    var limitMin by mutableIntStateOf(initial?.limit?.minutes ?: 30)
    var resetMin by mutableIntStateOf(initial?.limit?.resetMin ?: 0)
    var cooldownMin by mutableIntStateOf(initial?.limit?.cooldownMin ?: 0)
    var breaksOn by mutableStateOf(initial?.breaks?.enabled ?: false)
    var everySec by mutableIntStateOf(initial?.breaks?.everySec ?: 600)
    var lengthSec by mutableIntStateOf(initial?.breaks?.lengthSec ?: 30)
    var onOpen by mutableStateOf(initial?.breaks?.onOpen ?: false)
    var openSec by mutableIntStateOf(initial?.breaks?.openLength() ?: 10)
    var pauseAway by mutableStateOf(initial?.breaks?.pauseAway ?: false)
    val scroll = ScrollState(0)

    fun typedSites() = siteInput.split('\n', ',', ' ', ';').mapNotNull { RuleEngine.normalizeDomain(it) }.toSet()

    /** What saving would keep (as in save() below), so a change that saving would drop is not a change. */
    private fun content(): List<Any?> = listOf(
        name.trim(), mode, enabled, apps,
        if (mode == RuleMode.BLOCK) sites + typedSites() else emptySet<String>(),
        when (kind) {
            ScheduleKind.ALWAYS -> kind
            ScheduleKind.WEEKLY -> listOf(kind, days, startMin, endMin)
            ScheduleKind.UNTIL -> listOf(kind, if (forTouched) forSec else null)
        },
        if (mode == RuleMode.LIMIT) listOf(limitMin, resetMin, cooldownMin) else null,
        if (mode != RuleMode.BLOCK) listOf(breaksOn, everySec, lengthSec, onOpen, openSec, pauseAway) else null,
    )

    private val start = content()

    /** Leaving now would lose something. */
    fun changed() = content() != start

    /** "janitorai.com", "Social" or "New block", for the popup. */
    fun title(): String {
        val things = sites + typedSites()
        return when {
            name.isNotBlank() -> name.trim()
            apps.isEmpty() && things.isEmpty() -> if (initial == null) "New block" else RuleEngine.displayName(initial)
            else -> RuleEngine.displayName(Rule("", "", apps = apps, domains = things, mode = mode))
        }
    }
}

/** A block's page: what it covers, when, and what happens. A locked block only takes changes that make it stricter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditor(d: BlockDraft) {
    val nav = LocalNav.current
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val initial = d.initial
    val live = cfg.rules.firstOrNull { it.id == initial?.id }
    val frozen = live != null && Policy.isLocked(live.lock, now)
    val old = initial

    // The fields live in the draft (see BlockDraft); these are just short names for them.
    var name by d::name
    var mode by d::mode
    var enabled by d::enabled
    var kind by d::kind
    var days by d::days
    var startMin by d::startMin
    var endMin by d::endMin
    val oldUntil = d.oldUntil
    var forSec by d::forSec
    var forTouched by d::forTouched
    var apps by d::apps
    var sites by d::sites
    var siteInput by d::siteInput
    var limitMin by d::limitMin
    var resetMin by d::resetMin
    var cooldownMin by d::cooldownMin
    var breaksOn by d::breaksOn
    var everySec by d::everySec
    var lengthSec by d::lengthSec
    var onOpen by d::onOpen
    var openSec by d::openSec
    var pauseAway by d::pauseAway
    var picker by remember { mutableStateOf(false) }
    var timeFor by remember { mutableStateOf<TimeField?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val oldMode = old?.mode
    val oldBreaks = old?.breaks ?: Breaks()
    val oldBreaksBind = frozen && oldMode != RuleMode.BLOCK && oldBreaks.enabled

    fun typedSites() = d.typedSites()

    fun save() {
        error = null
        val allSites = if (mode == RuleMode.BLOCK) sites + typedSites() else emptySet()
        when {
            mode == RuleMode.BLOCK && apps.isEmpty() && allSites.isEmpty() -> error = "Choose at least one app or add a website."
            mode != RuleMode.BLOCK && apps.isEmpty() -> error = "Choose at least one app. Time limits and breaks work for apps."
            kind == ScheduleKind.WEEKLY && days == 0 -> error = "Pick at least one day."
            mode == RuleMode.REMIND && !breaksOn -> error = "Switch the break timer on - that is all this kind of block does."
        }
        if (error != null) return
        val schedule = when (kind) {
            ScheduleKind.ALWAYS -> Schedule()
            ScheduleKind.WEEKLY -> Schedule(ScheduleKind.WEEKLY, days = days, startMin = startMin, endMin = endMin)
            ScheduleKind.UNTIL -> Schedule(
                ScheduleKind.UNTIL,
                until = if (!forTouched && oldUntil > 0L) oldUntil else TimeGuard.now() + forSec * 1000L,
            )
        }
        val rule = Rule(
            id = initial?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            enabled = enabled,
            apps = apps,
            domains = allSites,
            schedule = schedule,
            lock = initial?.lock ?: Lock(),
            mode = mode,
            limit = if (mode == RuleMode.LIMIT) UsageLimit(limitMin, resetMin, cooldownMin) else (initial?.limit ?: UsageLimit()),
            breaks = if (mode == RuleMode.BLOCK) (initial?.breaks ?: Breaks())
            else Breaks(breaksOn, everySec, lengthSec, onOpen, openSec, pauseAway),
        )
        if (Policy.saveRule(rule)) nav.page = null
        else error = "This block is locked, so it can only get stricter. Unlock it (padlock at the top) to weaken it."
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (initial == null) "New block" else "Edit block") },
            navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(LineIcons.Back, contentDescription = "Back") } },
            actions = {
                if (live != null) PadlockButton(live.lock, now) { nav.openLock(Policy.Target.Block(live.id)) }
                TextButton(onClick = { save() }) { Text("Save", style = MaterialTheme.typography.labelLarge) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(d.scroll).padding(bottom = 32.dp),
        ) {
            if (frozen) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    LockedNote("Locked. You can add apps and sites and make it stricter; to weaken, pause or delete it, unlock it first.")
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            Group("Name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = {
                        Text(RuleEngine.displayName(Rule("", "", apps = apps, domains = sites, mode = mode)), maxLines = 1)
                    },
                    singleLine = true,
                    enabled = !frozen,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                Hint("Optional. Without one it's named after what it blocks.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }

            Group("What happens") {
                Column(Modifier.padding(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(RuleMode.BLOCK to "Block", RuleMode.LIMIT to "Time limit", RuleMode.REMIND to "Breaks")
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(
                                selected = mode == m,
                                onClick = { mode = m; if (m == RuleMode.REMIND) breaksOn = true },
                                // Modes are declared strictest first, so a locked block may only move to the front.
                                enabled = !frozen || m.ordinal <= (oldMode?.ordinal ?: 0),
                                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Hint(
                        when (mode) {
                            RuleMode.BLOCK -> "The apps and sites can't be opened while the block is on."
                            RuleMode.LIMIT -> "The apps can be used for a set time a day, then they're closed until the next day starts."
                            RuleMode.REMIND -> "Nothing is closed. Now and then a countdown covers the app, and it can't be used until it ends."
                        },
                        Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            Group("Apps") {
                val lockedApps = if (frozen) old?.apps.orEmpty() else emptySet()
                apps.sortedBy { RuleEngine.appLabel(it).lowercase() }.forEach { pkg ->
                    ItemRow(
                        title = RuleEngine.appLabel(pkg),
                        leading = { AppIcon(pkg, 32.dp) },
                        trailing = if (pkg in lockedApps) {
                            { Icon(LineIcons.Lock, contentDescription = "Locked in", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                        } else {
                            { IconButton(onClick = { apps = apps - pkg }) { Icon(LineIcons.Close, contentDescription = "Remove") } }
                        },
                    )
                    GroupDivider()
                }
                ItemRow(
                    title = if (apps.isEmpty()) "Choose apps" else "Add or remove apps",
                    leading = { IconDot(LineIcons.Grid, size = 32.dp) },
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = { picker = true },
                )
            }

            if (mode == RuleMode.BLOCK) {
                Group("Websites") {
                    val lockedSites = if (frozen) old?.domains.orEmpty() else emptySet()
                    sites.sorted().forEach { d ->
                        ItemRow(
                            title = d,
                            leading = { IconDot(LineIcons.Globe, size = 32.dp) },
                            trailing = if (d in lockedSites) {
                                { Icon(LineIcons.Lock, contentDescription = "Locked in", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
                            } else {
                                { IconButton(onClick = { sites = sites - d }) { Icon(LineIcons.Close, contentDescription = "Remove") } }
                            },
                        )
                        GroupDivider()
                    }
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = siteInput,
                            onValueChange = { siteInput = it },
                            placeholder = { Text("example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                sites = sites + typedSites()
                                siteInput = ""
                            }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                sites = sites + typedSites()
                                siteInput = ""
                            },
                            enabled = typedSites().isNotEmpty(),
                        ) { Text("Add") }
                    }
                    Hint(
                        "A site also covers its subdomains. Blocked by the DNS filter in every app and by the browser's address bar.",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }

            if (mode == RuleMode.LIMIT) {
                Group("Time limit") {
                    val maxMin = if (frozen && oldMode == RuleMode.LIMIT) old?.limit?.minutes ?: 1440 else 1440
                    ItemRow(
                        "Allowed per day",
                        trailing = { Stepper(limitMin, { limitMin = it }, 1..1440, step = 5, unit = "min", allowed = { it <= maxMin }) },
                    )
                    Chips(
                        listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h"),
                        selected = limitMin,
                        onPick = { limitMin = it },
                        enabled = { it <= maxMin },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                    GroupDivider()
                    val resetFrozen = frozen && oldMode == RuleMode.LIMIT
                    ItemRow(
                        "A new day starts at",
                        "Then the allowance is full again",
                        enabled = !resetFrozen,
                        trailing = { TimeButton(resetMin, enabled = !resetFrozen) { timeFor = TimeField.RESET } },
                    )
                    GroupDivider()
                    ItemRow("When the time is used up")
                    val oldCooldown = if (frozen && oldMode == RuleMode.LIMIT) old?.limit?.cooldownMin ?: 0 else 0
                    RadioRows(
                        listOf(true to "Closed until the new day", false to "Closed for a while"),
                        selected = cooldownMin == 0,
                        onPick = { untilReset -> cooldownMin = if (untilReset) 0 else maxOf(60, oldCooldown) },
                        // "Until the new day" is the longest closure, so it is always allowed.
                        enabled = { untilReset -> untilReset || !frozen || oldCooldown != 0 },
                        extra = { untilReset ->
                            if (!untilReset) {
                                Row(Modifier.padding(start = 56.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    DurationField(
                                        seconds = cooldownMin * 60L,
                                        onSeconds = { cooldownMin = (it / 60).toInt() },
                                        units = listOf(Span.MIN, Span.HOUR),
                                        range = 5 * 60L..24 * 3_600L,
                                        allowed = { oldCooldown == 0 || it >= oldCooldown * 60L },
                                    )
                                }
                            }
                        },
                    )
                    Hint(
                        "Time counts only while the screen is on and one of the apps is in front. A closure never lasts past the start of the next day.",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    )
                }
            }

            if (mode != RuleMode.BLOCK) {
                Group("Break timer") {
                    SwitchItem(
                        "Break timer",
                        "A countdown covers the app; it can't be used until it ends",
                        checked = breaksOn,
                        enabled = !oldBreaksBind,
                    ) { breaksOn = it }
                    if (breaksOn) {
                        GroupDivider()
                        ItemRow(
                            "Break after",
                            "of use",
                            trailing = {
                                DurationField(
                                    seconds = everySec.toLong(),
                                    onSeconds = { everySec = it.toInt() },
                                    units = listOf(Span.SEC, Span.MIN, Span.HOUR),
                                    range = 10L..86_400L,
                                    allowed = { !oldBreaksBind || it <= oldBreaks.everySec },
                                )
                            },
                        )
                        Chips(
                            listOf(300 to "5 min", 600 to "10 min", 1200 to "20 min", 1800 to "30 min"),
                            selected = everySec,
                            onPick = { everySec = it },
                            enabled = { !oldBreaksBind || it <= oldBreaks.everySec },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                        GroupDivider()
                        ItemRow(
                            "Break length",
                            trailing = {
                                DurationField(
                                    seconds = lengthSec.toLong(),
                                    onSeconds = { lengthSec = it.toInt() },
                                    units = listOf(Span.SEC, Span.MIN),
                                    range = 3L..3_600L,
                                    allowed = { !oldBreaksBind || it >= oldBreaks.lengthSec },
                                )
                            },
                        )
                        Chips(
                            listOf(15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min"),
                            selected = lengthSec,
                            onPick = { lengthSec = it },
                            enabled = { !oldBreaksBind || it >= oldBreaks.lengthSec },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        )
                        GroupDivider()
                        SwitchItem(
                            "Also when I open the app",
                            "The timer runs first, if you were away for more than a minute",
                            checked = onOpen,
                            enabled = !(oldBreaksBind && oldBreaks.onOpen),
                        ) { onOpen = it }
                        if (onOpen) {
                            // Its own length, so opening can take a few seconds while the regular break stays longer.
                            val openBind = oldBreaksBind && oldBreaks.onOpen
                            ItemRow(
                                "Timer when opening",
                                trailing = {
                                    DurationField(
                                        seconds = openSec.toLong(),
                                        onSeconds = { openSec = it.toInt() },
                                        units = listOf(Span.SEC, Span.MIN),
                                        range = 3L..3_600L,
                                        allowed = { !openBind || it >= oldBreaks.openLength() },
                                    )
                                },
                            )
                            Chips(
                                listOf(5 to "5 s", 10 to "10 s", 30 to "30 s", 60 to "1 min"),
                                selected = openSec,
                                onPick = { openSec = it },
                                enabled = { !openBind || it >= oldBreaks.openLength() },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            )
                        }
                        GroupDivider()
                        SwitchItem(
                            "Pause when I leave the app",
                            "The countdown stops while you use another app and goes on when you come back",
                            checked = pauseAway,
                            enabled = !(oldBreaksBind && oldBreaks.pauseAway),
                        ) { pauseAway = it }
                    }
                }
            }

            Group(if (mode == RuleMode.BLOCK) "When" else "When it applies") {
                val oldKind = old?.schedule?.kind
                Column(Modifier.padding(12.dp)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val kinds = listOf(ScheduleKind.ALWAYS to "Always", ScheduleKind.WEEKLY to "Schedule", ScheduleKind.UNTIL to "For a while")
                        kinds.forEachIndexed { i, (k, label) ->
                            SegmentedButton(
                                selected = kind == k,
                                onClick = { kind = k },
                                // "Always" is the strictest; a locked block can keep its own kind or go to "Always".
                                enabled = !frozen || k == ScheduleKind.ALWAYS || k == oldKind,
                                shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                }
                if (kind == ScheduleKind.WEEKLY) {
                    val scheduleFrozen = frozen
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DAY_LETTERS.forEachIndexed { i, letter ->
                            val on = (days shr i) and 1 == 1
                            Surface(
                                onClick = { days = days xor (1 shl i) },
                                enabled = !scheduleFrozen,
                                shape = CircleShape,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(40.dp).alpha(if (scheduleFrozen) 0.6f else 1f),
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text(letter, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                    Hint(
                        when (days) {
                            Schedule.ALL_DAYS -> "Every day"
                            0 -> "No day picked yet"
                            else -> DAY_NAMES.filterIndexed { i, _ -> (days shr i) and 1 == 1 }.joinToString(", ")
                        },
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    GroupDivider()
                    ItemRow("From", enabled = !scheduleFrozen, trailing = { TimeButton(startMin, !scheduleFrozen) { timeFor = TimeField.START } })
                    GroupDivider()
                    ItemRow("To", enabled = !scheduleFrozen, trailing = { TimeButton(endMin, !scheduleFrozen) { timeFor = TimeField.END } })
                    Hint(
                        if (startMin == endMin) "Same time: the whole day." else if (endMin < startMin) "Ends the next morning." else "",
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
                if (kind == ScheduleKind.UNTIL) {
                    val minSec = if (frozen && oldUntil > now) (oldUntil - now) / 1000L else 60L
                    ItemRow(
                        "For",
                        trailing = {
                            DurationField(
                                seconds = forSec,
                                onSeconds = {
                                    forSec = it
                                    forTouched = true
                                },
                                units = listOf(Span.MIN, Span.HOUR, Span.DAY),
                                range = 60L..365L * 86_400L,
                                allowed = { it >= minSec },
                            )
                        },
                    )
                    Chips(
                        listOf(3_600L to "1 h", 3 * 3_600L to "3 h", 8 * 3_600L to "8 h", 86_400L to "1 day", 7 * 86_400L to "7 days"),
                        selected = forSec,
                        onPick = {
                            forSec = it
                            forTouched = true
                        },
                        enabled = { it >= minSec },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    val end = if (!forTouched && oldUntil > 0L) oldUntil else now + forSec * 1000L
                    Hint(
                        (if (end <= now) "Ended " else "Until ") + Fmt.whenAt(wallOf(end, cfg.clockPenaltyMs)) +
                            if (!forTouched && oldUntil in 1..now) ". Set a new length to start it again." else "",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                    )
                }
            }

            if (initial != null) {
                Group {
                    SwitchItem(
                        "On",
                        if (enabled) "Switch off to pause it without deleting it" else "Paused - nothing is blocked",
                        checked = enabled,
                        enabled = !frozen || !(old?.enabled ?: false),
                    ) { enabled = it }
                }
                if (!frozen) {
                    TextButton(
                        onClick = { deleting = true },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(LineIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete block", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (picker) {
        AppPicker(
            selected = apps,
            lockedIn = if (frozen) old?.apps.orEmpty() else emptySet(),
            onDone = {
                apps = it
                picker = false
            },
            onCancel = { picker = false },
        )
    }
    timeFor?.let { field ->
        TimeDialog(
            title = when (field) {
                TimeField.START -> "From"
                TimeField.END -> "To"
                TimeField.RESET -> "A new day starts at"
            },
            minutes = when (field) {
                TimeField.START -> startMin
                TimeField.END -> endMin
                TimeField.RESET -> resetMin
            },
            onPick = { m ->
                when (field) {
                    TimeField.START -> startMin = m
                    TimeField.END -> endMin = m
                    TimeField.RESET -> resetMin = m
                }
            },
            onDismiss = { timeFor = null },
        )
    }
    if (deleting && initial != null) {
        ConfirmDialog(
            title = "Delete \"${RuleEngine.displayName(initial)}\"?",
            text = "Its apps and sites will no longer be blocked or limited.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                if (Policy.deleteRule(initial.id)) nav.page = null
                else error = "This block is locked. Unlock it first."
            },
            onDismiss = { deleting = false },
        )
    }
}

// ---- app picker ------------------------------------------------------------------------------------------------------

private data class AppEntry(val pkg: String, val label: String)

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
        .filter { !RuleEngine.isProtectedPackage(it.pkg) }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppPicker(selected: Set<String>, lockedIn: Set<String>, onDone: (Set<String>) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                TopAppBar(
                    title = { Text("Choose apps") },
                    navigationIcon = { IconButton(onClick = onCancel) { Icon(LineIcons.Close, contentDescription = "Cancel") } },
                    actions = { TextButton(onClick = { onDone(picked) }) { Text("Done (${picked.size})") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(LineIcons.Search, contentDescription = null) },
                    singleLine = true,
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (picked.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        picked.sortedBy { RuleEngine.appLabel(it).lowercase() }.forEach { pkg ->
                            AppIcon(pkg, 28.dp)
                        }
                    }
                }
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val shown = list.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.pkg }) { a ->
                            val locked = a.pkg in lockedIn
                            val checked = a.pkg in picked || locked
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = !locked) { picked = if (checked) picked - a.pkg else picked + a.pkg }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(a.pkg, 36.dp)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    a.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Checkbox(
                                    checked = checked,
                                    enabled = !locked,
                                    onCheckedChange = { on -> picked = if (on) picked + a.pkg else picked - a.pkg },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package app.willbolt.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.willbolt.core.Policy
import app.willbolt.core.TimeGuard
import app.willbolt.core.pendingValue
import app.willbolt.data.Lock
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---- layout ------------------------------------------------------------------------------------------------------

/** The big title at the top of a tab ("Blocks"), with optional actions on the right. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    /** In place of [subtitle], for a line that is more than plain text. */
    subtitleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 4.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            actions()
        }
        if (subtitleContent != null) {
            Box(Modifier.padding(end = 12.dp)) { subtitleContent() }
        } else if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

/** "2 blocks • 1 locked • also 5,000 adult sites": short facts with small accent dots between them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DotLine(parts: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, p ->
            if (i > 0) {
                Box(Modifier.align(Alignment.CenterVertically).size(5.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            Text(
                p,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/** A labelled, rounded group of rows - the basic building block of every screen. */
@Composable
fun Group(
    label: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (label != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 16.dp, bottom = 4.dp).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
        } else {
            Spacer(Modifier.size(12.dp))
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

/** One row in a [Group]. */
@Composable
fun ItemRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ItemRow(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

/** Small explanation under a control. */
@Composable
fun Hint(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = textAlign, modifier = modifier)
}

/** An icon in a soft circle, used at the start of rows. */
@Composable
fun IconDot(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

// ---- numbers you can type ---------------------------------------------------------------------------------------

/**
 * A number you can type. A value outside [range] or not [allowed] turns the box red and is not taken; leaving the
 * box puts the last good value back. With [live] every good keystroke counts at once; without it the value is taken
 * when typing pauses, on Done, or when the box goes away.
 */
@Composable
fun NumberBox(
    value: Int,
    onValue: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowed: (Int) -> Boolean = { true },
    width: Dp = 76.dp,
    live: Boolean = true,
) {
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(value.toString()) }
    var hasFocus by remember { mutableStateOf(false) }
    // Changed from outside (a - / + button, a quick choice): show it. Our own commits already match the text, so
    // nothing is replaced under the keyboard while you type.
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    val currentValue by rememberUpdatedState(value)
    val currentOnValue by rememberUpdatedState(onValue)
    val parsed = text.toIntOrNull()
    val good = parsed != null && parsed in range && allowed(parsed)

    fun commit() {
        val n = text.toIntOrNull()
        if (n != null && n in range && allowed(n) && n != currentValue) currentOnValue(n)
    }

    if (!live) {
        LaunchedEffect(text) {
            delay(900)
            commit()
        }
        DisposableEffect(Unit) { onDispose { commit() } }
    }

    val cs = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { t ->
            text = t.filter(Char::isDigit).take(7)
            if (live) commit()
        },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(
            color = if (good || hasFocus.not()) cs.onSurface else cs.error,
            textAlign = TextAlign.Center,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focus.clearFocus()
        }),
        cursorBrush = SolidColor(cs.primary),
        modifier = modifier
            .width(width)
            .alpha(if (enabled) 1f else 0.5f)
            .onFocusChanged { f ->
                if (hasFocus && !f.isFocused) {
                    commit()
                    text = currentValue.toString()
                }
                hasFocus = f.isFocused
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .background(cs.surfaceContainerHighest, MaterialTheme.shapes.small)
                    .border(
                        width = if (hasFocus) 2.dp else 1.dp,
                        color = when {
                            !good -> cs.error
                            hasFocus -> cs.primary
                            else -> cs.outlineVariant
                        },
                        shape = MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { inner() }
        },
    )
}

/** [-] [ 7 ] [+] with a unit after it. The box can be typed in; the buttons step by [step]. */
@Composable
fun Stepper(
    value: Int,
    onValue: (Int) -> Unit,
    range: IntRange,
    step: Int = 1,
    unit: String? = null,
    enabled: Boolean = true,
    allowed: (Int) -> Boolean = { true },
    width: Dp = 64.dp,
) {
    val down = (((value - 1) / step) * step).coerceAtLeast(range.first)
    val up = ((value / step + 1) * step).coerceAtMost(range.last)
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(LineIcons.Minus, "Less", enabled && value > range.first && allowed(down)) { onValue(down) }
        NumberBox(value, onValue, range, Modifier.padding(horizontal = 6.dp), enabled, allowed, width)
        StepButton(LineIcons.Plus, "More", enabled && value < range.last && allowed(up)) { onValue(up) }
        if (unit != null) {
            Spacer(Modifier.width(8.dp))
            Text(unit, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(36.dp).alpha(if (enabled) 1f else 0.4f),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) }
    }
}

enum class Span(val label: String, val seconds: Long) {
    SEC("s", 1),
    MIN("min", 60),
    HOUR("h", 3_600),
    DAY("days", 86_400),
}

/** The biggest of [units] that shows [seconds] as a whole number ("600" -> 10 min). */
fun bestUnit(seconds: Long, units: List<Span>): Span =
    units.sortedByDescending { it.seconds }.firstOrNull { seconds > 0 && seconds % it.seconds == 0L } ?: units.first()

/**
 * A length of time you can type: a number and a unit ("10" "min"). Changing the unit keeps the number ("2" min -> 2 h).
 * [range] and [allowed] are in seconds.
 */
@Composable
fun DurationField(
    seconds: Long,
    onSeconds: (Long) -> Unit,
    units: List<Span>,
    range: LongRange,
    enabled: Boolean = true,
    allowed: (Long) -> Boolean = { true },
    live: Boolean = true,
) {
    var picked by remember { mutableStateOf(bestUnit(seconds, units)) }
    // Someone else changed the value (a quick chip) so it is no whole number of the picked unit: show the best one.
    val unit = if (seconds % picked.seconds == 0L) picked else bestUnit(seconds, units)
    val number = (seconds / unit.seconds).toInt()
    val lo = ((range.first + unit.seconds - 1) / unit.seconds).toInt().coerceAtLeast(1)
    val hi = (range.last / unit.seconds).toInt().coerceAtLeast(lo)
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        NumberBox(
            value = number,
            onValue = { onSeconds(it * unit.seconds) },
            range = lo..hi,
            enabled = enabled,
            allowed = { allowed(it * unit.seconds) },
            live = live,
        )
        Spacer(Modifier.width(6.dp))
        Box {
            Surface(
                onClick = { menu = true },
                enabled = enabled && units.size > 1,
                shape = MaterialTheme.shapes.small,
                color = Color.Transparent,
            ) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(unit.label, style = MaterialTheme.typography.bodyLarge)
                    if (units.size > 1) {
                        Icon(
                            LineIcons.ChevronDown, contentDescription = "Unit",
                            modifier = Modifier.padding(start = 2.dp).size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                units.forEach { u ->
                    DropdownMenuItem(text = { Text(u.label) }, onClick = {
                        menu = false
                        val n = number.toLong().coerceAtLeast(1)
                        val s = (n * u.seconds).coerceIn(range)
                        picked = u
                        if (allowed(s)) onSeconds(s)
                    })
                }
            }
        }
    }
}

// ---- choices -----------------------------------------------------------------------------------------------------

/** Quick choices that wrap onto the next line instead of scrolling out of sight. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(
    options: List<Pair<T, String>>,
    selected: T?,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        options.forEach { (v, label) ->
            FilterChip(
                selected = v == selected,
                onClick = { onPick(v) },
                enabled = enabled(v),
                label = { Text(label) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

/** One option per line, with room under the chosen one for its own setting. */
@Composable
fun <T> RadioRows(
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
    extra: @Composable (T) -> Unit = {},
) {
    Column {
        options.forEach { (v, label) ->
            val on = enabled(v)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = on) { onPick(v) }.padding(horizontal = 8.dp, vertical = 2.dp)
                    .alpha(if (on) 1f else 0.45f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = v == selected, onClick = { onPick(v) }, enabled = on)
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
            if (v == selected) extra(v)
        }
    }
}

// ---- locks -------------------------------------------------------------------------------------------------------

/** The padlock next to a block or a part of the app. Filled = locked; a countdown under it while it unlocks. */
@Composable
fun PadlockButton(lock: Lock, now: Long, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = Policy.state(lock, now)
    val locked = state != Policy.State.OPEN
    val color = when (state) {
        Policy.State.OPEN -> Color.Transparent
        Policy.State.UNLOCKING -> cs.tertiary
        else -> cs.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 48.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            contentColor = if (locked) cs.surface else cs.onSurfaceVariant,
            border = if (locked) null else androidx.compose.foundation.BorderStroke(1.dp, cs.outline),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (locked) LineIcons.Lock else LineIcons.LockOpen,
                    contentDescription = when (state) {
                        Policy.State.OPEN -> "Not locked - tap to lock"
                        Policy.State.UNLOCKING -> "Unlocking"
                        else -> "Locked"
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (state == Policy.State.UNLOCKING) {
            Text(
                fmtCountdown(Policy.opensAt(lock) - now),
                style = MaterialTheme.typography.labelSmall,
                color = cs.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** "Locked - you can only make it stricter." strip at the top of a locked section. */
@Composable
fun LockedNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LineIcons.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

// ---- locked numbers: pick, then Confirm -----------------------------------------------------------------------------

/** What [rememberPending] gives the controls: the number to show, the pick waiting for Confirm (if any), and what to call. */
@Stable
class Pending(
    val shown: Int,
    val waiting: Int?,
    val pick: (Int) -> Unit,
    val confirm: () -> Unit,
    val cancel: () -> Unit,
)

/**
 * A number that can only get stricter while its part is [locked]. Unlocked, [Pending.pick] saves at once. Locked, it only
 * remembers the pick (held at [clamp], the strictest side of the saved [current]) until [ConfirmRow] applies it, so a
 * slider, a stepper or a typed number that went too far can still be pulled back; see [pendingValue].
 */
@Composable
fun rememberPending(current: Int, locked: Boolean, clamp: (Int) -> Int, save: (Int) -> Unit): Pending {
    var picked by remember(locked) { mutableStateOf<Int?>(null) }
    val clampNow by rememberUpdatedState(clamp)
    val saveNow by rememberUpdatedState(save)
    val waiting = pendingValue(current, picked, locked, clamp)
    return Pending(
        shown = waiting ?: current,
        waiting = waiting,
        pick = { v ->
            if (locked) picked = clampNow(v) else if (v != current) saveNow(v)
        },
        confirm = {
            val v = waiting?.let(clampNow)
            picked = null
            if (v != null && v != current) saveNow(v)
        },
        cancel = { picked = null },
    )
}

/** "From 7 to 3. ..." with Cancel and Confirm, shown while a locked number has a pick waiting. */
@Composable
fun ConfirmRow(p: Pending, text: String, modifier: Modifier = Modifier) {
    if (p.waiting == null) return
    Column(modifier.fillMaxWidth()) {
        Hint(text)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = p.cancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = p.confirm) { Text("Confirm") }
        }
    }
}

// ---- dialogs -----------------------------------------------------------------------------------------------------

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

// ---- time --------------------------------------------------------------------------------------------------------

/** A "now" (TimeGuard time) that ticks once a second while the screen is visible. */
@Composable
fun rememberNow(): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(TimeGuard.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = TimeGuard.now()
                delay(1000)
            }
        }
    }
    return now
}

/** Dates and times the way this phone shows them (24 h or 12 h), with English day and month names. */
object Fmt {
    var is24 = true
        private set

    fun init(ctx: Context) {
        is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    }

    private fun f(pattern: String) = SimpleDateFormat(pattern, Locale.ENGLISH)

    fun clock(epoch: Long): String = f(if (is24) "HH:mm" else "h:mm a").format(Date(epoch))

    /** "14:05", "tomorrow 09:00", "Sat 1 Nov, 19:40". */
    fun whenAt(epoch: Long, nowWall: Long = System.currentTimeMillis()): String {
        val days = dayIndex(epoch) - dayIndex(nowWall)
        return when (days) {
            0L -> clock(epoch)
            1L -> "tomorrow " + clock(epoch)
            -1L -> "yesterday " + clock(epoch)
            else -> f("EEE d MMM").format(Date(epoch)) + ", " + clock(epoch)
        }
    }

    /** "Today", "Yesterday", "Thu 23 Oct". */
    fun day(epoch: Long, nowWall: Long = System.currentTimeMillis()): String = when (dayIndex(nowWall) - dayIndex(epoch)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> f("EEE d MMM").format(Date(epoch))
    }

    /** For the middle of a sentence: "at 14:05", "tomorrow at 09:00", "on Sat 1 Nov at 19:40". */
    fun at(epoch: Long, nowWall: Long = System.currentTimeMillis()): String = when (dayIndex(epoch) - dayIndex(nowWall)) {
        0L -> "at " + clock(epoch)
        1L -> "tomorrow at " + clock(epoch)
        -1L -> "yesterday at " + clock(epoch)
        else -> "on " + f("EEE d MMM").format(Date(epoch)) + " at " + clock(epoch)
    }

    /** A day ahead: "Today", "Tomorrow", "Thu 23 Oct". */
    fun date(epoch: Long, nowWall: Long = System.currentTimeMillis()): String = when (dayIndex(epoch) - dayIndex(nowWall)) {
        0L -> "Today"
        1L -> "Tomorrow"
        else -> f("EEE d MMM").format(Date(epoch))
    }

    /** Days since 1970 on this phone's calendar, so "tomorrow" also works across New Year. */
    fun dayIndex(epoch: Long): Long = java.time.Instant.ofEpochMilli(epoch).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
}

/** "47,662" - English grouping, to match the rest of the text. */
fun fmtCount(n: Int): String = "%,d".format(Locale.ENGLISH, n)

/** "2 days 3 h", "3 h 5 min", "12 min", "45 s". */
fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    return when {
        d > 0 -> if (h > 0) "$d day${if (d == 1L) "" else "s"} $h h" else "$d day${if (d == 1L) "" else "s"}"
        h > 0 -> if (m > 0) "$h h $m min" else "$h h"
        m > 0 -> "$m min"
        else -> "$s s"
    }
}

/** "4:05", "1:02:03", "2 days 3 h" - for a timer that runs down. */
fun fmtCountdown(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return when {
        s >= 86_400 -> fmtDuration(ms)
        s >= 3_600 -> "%d:%02d:%02d".format(s / 3_600, (s % 3_600) / 60, s % 60)
        else -> "%d:%02d".format(s / 60, s % 60)
    }
}

/** A trusted (TimeGuard) time shown on the phone's own clock. */
fun wallOf(trusted: Long, penalty: Long) = trusted - penalty

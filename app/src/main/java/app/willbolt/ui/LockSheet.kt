package app.willbolt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.Edition
import app.willbolt.core.Health
import app.willbolt.core.Passwords
import app.willbolt.core.Policy
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.data.Lock
import app.willbolt.data.LockKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val WAITS = listOf(5 to "5 min", 30 to "30 min", 120 to "2 h", 1_440 to "1 day")
private val WAIT_RANGE = 60L..Policy.MAX_DELAY_MIN * 60L
private const val MAX_AHEAD_MS = 365L * 86_400_000L

/** What locking means for this particular thing, in one or two sentences. */
private fun meaning(t: Policy.Target): String = when (t) {
    is Policy.Target.Block ->
        "While it is locked you can still add apps and sites or make it stricter. Pausing it, weakening it or deleting it needs unlocking first."
    is Policy.Target.Part -> when (t.area) {
        Policy.Area.SCAN ->
            "While it is locked the screen check stays on, checks at least as often and only gets more sensitive."
        Policy.Area.WEB ->
            "While locked, adult sites stay blocked and the list can only get longer."
        Policy.Area.DNS ->
            "While it is locked the website filter keeps running, and safe search, the DNS loopholes and the DNS server can only get stricter."
        Policy.Area.SELF ->
            "Locking it switches the uninstall protection on and keeps it on: Willbolt closes its own app info, uninstall and device-admin screens and the phone's battery-manager screens that list it (Honor: App launch). When the lock opens, the protection stays on until you switch it off."
    }
}

/** The sheet behind every padlock: lock it, see how it opens, unlock it its own way. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockSheet(target: Policy.Target, onDismiss: () -> Unit) {
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val lock = Policy.lockOf(cfg, target)
    if (lock == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val name = Policy.nameOf(cfg, target)
    val state = Policy.state(lock, now)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
                .padding(bottom = 16.dp).navigationBarsPadding(),
        ) {
            when {
                // Locking DNS keeps the website filter running, so it has to be started first.
                state == Policy.State.OPEN && target == Policy.Target.Part(Policy.Area.DNS) && !cfg.webFilterOn -> {
                    SheetHead(LineIcons.LockOpen, MaterialTheme.colorScheme.primary, "Start the website filter first", meaning(target))
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Close") }
                }
                state == Policy.State.OPEN -> {
                    SheetHead(LineIcons.LockOpen, MaterialTheme.colorScheme.primary, "Lock $name", meaning(target))
                    LockForm(target, cfg, current = null, now = now, onDone = onDismiss)
                    // (The uninstall protection's own lock is what keeps it on, so it has no "turn it on" advice.)
                    val note = if (target == Policy.SELF_LOCK) "Safe Mode or a USB cable still get you out." else Edition.LOCK_NOTE
                    Hint(note, Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                }
                state == Policy.State.UNLOCKING -> UnlockingView(target, cfg, name, lock, now, onDismiss)
                else -> LockedView(target, cfg, name, lock, state, now, onDismiss)
            }
        }
    }
}

@Composable
private fun SheetHead(icon: ImageVector, tint: Color, title: String, text: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** The next full hour a day from now: a round default for "opens by itself". */
private fun defaultEndWall(): Long = (System.currentTimeMillis() + 86_400_000L + 3_599_999L) / 3_600_000L * 3_600_000L

/**
 * "Unlocking takes 30 min", "Opens with the password", "Can't be unlocked before tomorrow 18:00" - and, with [guard], that
 * the uninstall protection stays on as long as it is locked.
 */
private fun describe(l: Lock, penalty: Long, guard: Boolean = false): String {
    val end = if (l.ends != 0L) Fmt.at(wallOf(l.ends, penalty)) else null
    val how = when (l.kind) {
        LockKind.WAIT -> "Unlocking takes ${fmtDuration(l.delayMin * 60_000L)}" + (end?.let { ". It opens by itself $it" } ?: "") + "."
        LockKind.PASSWORD -> "The password opens it" + (end?.let { ". It also opens by itself $it" } ?: "") + "."
        LockKind.TIME -> "It opens by itself $end, and nothing opens it before then."
    }
    return if (guard && l.guard) "$how Uninstall protection is on while it is locked." else how
}

/**
 * How it unlocks and whether it ends by itself. With [current] set it makes an existing lock stricter: choices that
 * could only loosen it are greyed out, and anything else that would is explained instead of saved.
 */
@Composable
private fun LockForm(target: Policy.Target, cfg: Config, current: Lock?, now: Long, onDone: () -> Unit) {
    val penalty = cfg.clockPenaltyMs
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Any lock but the one on Willbolt itself (which is the protection and nothing else) can hold the uninstall protection.
    val canGuard = Edition.FULL && target != Policy.SELF_LOCK
    // The way used last time - but not the password while there is none (say it was removed since).
    var kind by remember {
        mutableStateOf(current?.kind ?: cfg.lockKind.takeIf { it != LockKind.PASSWORD || cfg.password != null } ?: LockKind.WAIT)
    }
    var waitSec by remember { mutableLongStateOf((current?.delayMin ?: cfg.lockDelayMin).coerceAtLeast(1) * 60L) }
    var endsOn by remember { mutableStateOf(current != null && current.ends != 0L) }
    var endWall by remember { mutableLongStateOf(current?.ends?.takeIf { it != 0L }?.let { wallOf(it, penalty) } ?: defaultEndWall()) }
    var guardOn by remember { mutableStateOf(canGuard && (current?.guard ?: cfg.lockGuard)) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val needsPassword = kind == LockKind.PASSWORD && cfg.password == null
    val hasEnd = kind == LockKind.TIME || endsOn
    val want = Lock(
        on = true, kind = kind, delayMin = (waitSec / 60L).toInt(), ends = if (hasEnd) endWall + penalty else 0L,
        guard = canGuard && guardOn,
    )
    // What it would become: a 0.3 lock's "no unlocking before" is kept (see Policy.locked).
    val candidate = want.copy(until = if (current != null && current.until > now) current.until else 0L)
    val looser = current?.let { Policy.looserBy(it, candidate, now) }
    val oldEndWall = current?.ends?.takeIf { it != 0L }?.let { wallOf(it, penalty) }
    // A locked one can keep its way of opening, or become timed if it already has an end.
    val kindOk = { k: LockKind -> current == null || k == current.kind || (k == LockKind.TIME && current.ends != 0L) }
    val minWait = if (current?.kind == LockKind.WAIT && kind == LockKind.WAIT) current.delayMin * 60L else 60L
    val endOk = { wall: Long -> wall > System.currentTimeMillis() && (oldEndWall == null || wall >= oldEndWall) }

    Text("How it unlocks", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            WayRow(LineIcons.Hourglass, "After a wait", "Start unlocking, then wait it out", kind == LockKind.WAIT, kindOk(LockKind.WAIT)) {
                kind = LockKind.WAIT
            }
            if (kind == LockKind.WAIT) {
                Column(Modifier.padding(start = 16.dp, end = 4.dp, bottom = 12.dp)) {
                    DurationField(
                        seconds = waitSec,
                        onSeconds = { waitSec = it },
                        units = listOf(Span.MIN, Span.HOUR, Span.DAY),
                        range = WAIT_RANGE,
                        allowed = { it >= minWait },
                    )
                    Chips(
                        WAITS.map { (m, label) -> m * 60L to label },
                        selected = waitSec,
                        onPick = { waitSec = it },
                        enabled = { it >= minWait },
                    )
                    Hint("An urge usually passes before the wait is over.")
                }
            }
            GroupDivider()
            WayRow(
                LineIcons.Key, "With the password",
                if (cfg.password == null) "Set it up here - one password for every lock" else "Your Willbolt password opens it at once",
                kind == LockKind.PASSWORD, kindOk(LockKind.PASSWORD),
            ) { kind = LockKind.PASSWORD }
            if (needsPassword) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    NewPasswordFields(pw1, { pw1 = it }, pw2, { pw2 = it })
                }
            }
            GroupDivider()
            WayRow(LineIcons.Clock, "Not before a set time", "Nothing opens it early - it opens by itself then", kind == LockKind.TIME, kindOk(LockKind.TIME)) {
                kind = LockKind.TIME
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    if (kind == LockKind.TIME) {
        Text("Opens at", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        EndPicker(endWall, { endWall = it }, MAX_AHEAD_MS, enabled = endOk)
        Hint(
            if (Edition.FULL) "Safe Mode or a USB cable still get you out."
            else "Switching Willbolt off in Android's settings, or uninstalling it, still gets you out.",
            Modifier.padding(top = 4.dp),
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Opens by itself", style = MaterialTheme.typography.labelLarge)
                Hint(if (endsOn) "At this time, even if you never unlock it" else "Off - it stays locked until it's unlocked")
            }
            // Taking an end away is stricter; adding one to a lock that has none is not.
            Switch(checked = endsOn, onCheckedChange = { endsOn = it }, enabled = current == null || current.ends != 0L)
        }
        if (endsOn) {
            Spacer(Modifier.height(8.dp))
            EndPicker(endWall, { endWall = it }, MAX_AHEAD_MS, enabled = endOk)
        }
    }

    if (canGuard) {
        // The uninstall protection rides on this lock: it is on exactly as long as the lock holds, and the lock's own way of
        // opening is the only way to switch it off. A locked lock can take it on (stricter) but not give it up.
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Uninstall protection while locked", style = MaterialTheme.typography.labelLarge)
                Hint(
                    when {
                        guardOn -> "On: Willbolt's app info, uninstall and battery-manager screens stay closed while this is locked"
                        cfg.guardOn -> "Off - it is switched on now, but can be switched off while this is locked"
                        else -> "Off - Android's Settings can still stop or remove Willbolt"
                    },
                )
            }
            Switch(checked = guardOn, onCheckedChange = { guardOn = it }, enabled = current?.guard != true)
        }
    }

    Spacer(Modifier.height(20.dp))
    val problem = when {
        hasEnd && !endOk(endWall) && endWall <= System.currentTimeMillis() -> "Pick a time that is still ahead."
        looser != null && current != null -> looserText(looser, current, penalty)
        else -> error
    }
    Text(
        problem ?: describe(want, penalty, guard = canGuard),
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    val passwordReady = !needsPassword || (pw1.length >= Passwords.MIN_LENGTH && pw1 == pw2)
    Button(
        onClick = {
            busy = true
            error = null
            scope.launch {
                if (needsPassword) {
                    val r = withContext(Dispatchers.Default) { Policy.setPassword(pw1, null) }
                    if (r != Policy.PwResult.OK) {
                        error = pwText(r, cfg)
                        busy = false
                        return@launch
                    }
                }
                val wasProtected = Policy.selfProtected(ConfigStore.value)
                if (Policy.lock(target, want)) {
                    // The protection has a second half only Android can switch on: the device administrator. Asked for when
                    // this lock is what turns the protection on (not again for every lock after it).
                    if (!wasProtected && Policy.selfProtected(ConfigStore.value) && !Health.isAdminActive(ctx)) {
                        ctx.startActivity(Fixes.adminIntent(ctx))
                    }
                    onDone()
                } else {
                    error = "That would loosen it, so nothing was changed."
                }
                busy = false
            }
        },
        enabled = !busy && passwordReady && looser == null && (!hasEnd || endOk(endWall)),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(LineIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(if (current == null) "Lock" else "Make it stricter")
    }
}

/** One way of unlocking: icon, what it is, and the radio button. */
@Composable
private fun WayRow(icon: ImageVector, title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDot(icon, tint = if (selected) cs.primary else cs.onSurfaceVariant, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
    }
}

private fun way(l: Lock) = when (l.kind) {
    LockKind.WAIT -> "after a wait"
    LockKind.PASSWORD -> "with the password"
    LockKind.TIME -> "only at its set time"
}

/** Why a "stricter" change would in fact loosen [old]. */
private fun looserText(why: Policy.Looser, old: Lock, penalty: Long): String = when (why) {
    Policy.Looser.ENDS_SOONER ->
        if (old.ends == 0L) "It stays locked until it's unlocked, so it can't get a time to open by itself."
        else "It can't open by itself before ${Fmt.whenAt(wallOf(old.ends, penalty))}."
    Policy.Looser.UNLOCKS_SOONER -> {
        val until = maxOf(old.until, if (old.kind == LockKind.TIME) old.ends else 0L)
        "Nothing can unlock it before ${Fmt.whenAt(wallOf(until, penalty))}."
    }
    Policy.Looser.OTHER_WAY -> "While it's locked it keeps opening ${way(old)}."
    Policy.Looser.SHORTER_WAIT -> "The wait can't get shorter than ${fmtDuration(old.delayMin * 60_000L)}."
    Policy.Looser.GUARD_OFF -> "While it's locked it keeps the uninstall protection on."
}

@Composable
private fun LockedView(
    target: Policy.Target,
    cfg: Config,
    name: String,
    lock: Lock,
    state: Policy.State,
    now: Long,
    onDismiss: () -> Unit,
) {
    var stricter by remember { mutableStateOf(false) }
    val penalty = cfg.clockPenaltyMs
    val wait = fmtDuration(lock.delayMin * 60_000L)
    val legacyFixed = state == Policy.State.FIXED && lock.kind != LockKind.TIME
    val text = when {
        legacyFixed ->
            "No unlocking before ${Fmt.whenAt(wallOf(lock.until, penalty))} (${fmtDuration(lock.until - now)} from now). After that, unlocking takes $wait."
        else -> describe(lock, penalty, guard = Edition.FULL && target != Policy.SELF_LOCK)
    }
    SheetHead(LineIcons.Lock, MaterialTheme.colorScheme.primary, "$name ${Policy.isOrAre(target)} locked", text)
    if (stricter) {
        LockForm(target, cfg, current = lock, now = now, onDone = onDismiss)
        return
    }
    when {
        lock.kind == LockKind.TIME -> {
            Text(
                fmtCountdown(lock.ends - now),
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("until it opens", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        lock.kind == LockKind.PASSWORD && !legacyFixed -> PasswordUnlock(target, cfg, now, onOpened = onDismiss)
        else -> Button(
            onClick = { Policy.startUnlock(target) },
            enabled = state == Policy.State.LOCKED,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (legacyFixed) "Can't unlock yet" else "Start unlocking ($wait)")
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { stricter = true }) { Text("Lock it tighter") }
        TextButton(onClick = onDismiss) { Text("Close") }
    }
}

/** The password box under a password lock. */
@Composable
private fun PasswordUnlock(target: Policy.Target, cfg: Config, now: Long, onOpened: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Policy.PwResult?>(null) }
    val paused = now < cfg.passwordPauseUntil

    fun tryIt() {
        if (busy || pw.isEmpty() || paused) return
        busy = true
        scope.launch {
            result = withContext(Dispatchers.Default) { Policy.unlockWithPassword(target, pw) }
            busy = false
            if (result == Policy.PwResult.OK) onOpened() else pw = ""
        }
    }

    PasswordField(
        pw, { pw = it; result = null }, "Password",
        isError = result == Policy.PwResult.WRONG, imeAction = ImeAction.Done, onDone = ::tryIt,
        modifier = Modifier.fillMaxWidth(),
    )
    val r = result
    val note = when {
        paused -> "Too many wrong tries. Try again in ${fmtCountdown(cfg.passwordPauseUntil - now)}."
        r != null && r != Policy.PwResult.OK -> pwText(r, cfg)
        else -> null
    }
    if (note != null) Hint(note, Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = ::tryIt,
        enabled = !busy && pw.isNotEmpty() && !paused,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text(if (busy) "Checking..." else "Unlock") }
    Hint(
        "Forgot it? On Home you can set a new one without the old one after a 4-day wait.",
        Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun UnlockingView(target: Policy.Target, cfg: Config, name: String, lock: Lock, now: Long, onDismiss: () -> Unit) {
    SheetHead(
        LineIcons.Clock, MaterialTheme.colorScheme.tertiary, "Unlocking $name",
        "It stays locked until the timer runs out, then it opens by itself. You can call it off any time.",
    )
    Text(
        fmtCountdown(Policy.opensAt(lock) - now),
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.tertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick = {
            Policy.keepLocked(target)
            onDismiss()
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Keep it locked") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Close") }
}

/** Every padlock in one list: the three parts of the app and each block. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocksSheet(onPick: (Policy.Target) -> Unit, onDismiss: () -> Unit) {
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text("Locks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
            Hint(
                "A locked part can only get stricter. Tap a padlock to lock or unlock it.",
                Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            // DNS only once its filter has been started (or while it is locked).
            val shown = Policy.allLocks(cfg).filter { (t, _, l) ->
                t != Policy.Target.Part(Policy.Area.DNS) || cfg.webFilterOn || Policy.isLocked(l, now)
            }
            shown.forEach { (target, name, lock) ->
                ItemRow(
                    title = name,
                    subtitle = lockStatus(lock, now, cfg.clockPenaltyMs) +
                        if (Edition.FULL && lock.guard && target != Policy.SELF_LOCK && Policy.isLocked(lock, now)) " · uninstall protection" else "",
                    leading = {
                        IconDot(
                            when (target) {
                                is Policy.Target.Block -> LineIcons.Wall
                                is Policy.Target.Part -> when (target.area) {
                                    Policy.Area.SCAN -> LineIcons.Eye
                                    Policy.Area.WEB -> LineIcons.Globe
                                    Policy.Area.DNS -> LineIcons.Funnel
                                    Policy.Area.SELF -> LineIcons.Shield
                                }
                            },
                        )
                    },
                    trailing = { PadlockButton(lock, now) { onPick(target) } },
                    onClick = { onPick(target) },
                )
            }
        }
    }
}

/** "Not locked", "Locked - unlocking takes 30 min", "Locked with the password", "Locked until Sat 1 Nov, 19:40". */
fun lockStatus(l: Lock, now: Long, penalty: Long): String {
    val end = if (l.ends != 0L) " · opens " + Fmt.at(wallOf(l.ends, penalty)) else ""
    return when (Policy.state(l, now)) {
        Policy.State.OPEN -> "Not locked"
        Policy.State.LOCKED -> when (l.kind) {
            LockKind.PASSWORD -> "Locked with the password$end"
            else -> "Locked - unlocking takes ${fmtDuration(l.delayMin * 60_000L)}$end"
        }
        Policy.State.FIXED ->
            if (l.kind == LockKind.TIME) "Locked until ${Fmt.whenAt(wallOf(l.ends, penalty))}"
            else "No unlocking before ${Fmt.whenAt(wallOf(l.until, penalty))}"
        Policy.State.UNLOCKING -> "Unlocks in ${fmtCountdown(Policy.opensAt(l) - now)}"
    }
}

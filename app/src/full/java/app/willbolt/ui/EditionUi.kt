package app.willbolt.ui

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.willbolt.core.Policy
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore

// The screens that differ between the editions. This is the full edition's; the Play edition has smaller versions of
// the same functions in src/play.

private const val ADB_GRANT = "adb shell pm grant app.willbolt android.permission.WRITE_SECURE_SETTINGS"

/** What the Home card says about the uninstall protection, whether it shows as on, and whether it offers Activate. */
internal class GuardStatus(val on: Boolean, val text: String, val warn: Boolean, val activate: Boolean)

/** A lock's name short enough for a line of the card ("Instagram, TikTok, YouTube..."). */
private fun brief(name: String) = if (name.length <= 22) name else name.take(21).trimEnd() + "…"

/**
 * How the locks in [holders] ([Policy.guardLocks]) hold the protection on, with the switch [on] or off: "On and locked"
 * (its own padlock), "On, locked by Screen check" (the switch is on and that lock keeps it so), "On while Websites are
 * locked" (only the lock keeps it on, until it opens). Null when no lock holds it.
 */
internal fun heldText(on: Boolean, holders: List<Pair<Policy.Target, String>>): String? {
    if (holders.isEmpty()) return null
    if (holders.any { it.first == Policy.SELF_LOCK }) return "On and locked"
    val who = if (holders.size == 1) brief(holders[0].second) else "${holders.size} locks"
    val verb = if (holders.size == 1) Policy.isOrAre(holders[0].first) else "are"
    return if (on) "On, locked by $who" else "On while $who $verb locked"
}

/**
 * [on] is the protection's switch ([Config.guardOn]), [holders] the locks that hold it on right now, [admin] whether
 * Android's device administrator is on and [locked] whether anything is locked. One short line: this sits on Home.
 */
internal fun guardStatus(on: Boolean, holders: List<Pair<Policy.Target, String>>, admin: Boolean, locked: Boolean): GuardStatus {
    val state = heldText(on, holders) ?: if (on) "On" else null
    return when {
        state != null && admin -> GuardStatus(true, "$state. Settings can't stop or remove Willbolt.", warn = false, activate = false)
        state != null -> GuardStatus(true, "$state. One step left: activate device admin.", warn = true, activate = true)
        locked -> GuardStatus(false, "Off, yet something is locked. Tap to turn it on.", warn = true, activate = false)
        else -> GuardStatus(false, "Off. Tap to turn it on.", warn = false, activate = false)
    }
}

/**
 * The card on Home: whether Willbolt closes its own Settings screens (app info with Force stop and Clear data, uninstall,
 * device admin, the phone's battery manager). One tap turns it on or off, and the card turns teal while it is on. The
 * padlock is optional: locked, the protection stays on until the lock opens (as it does while another lock made with
 * "Uninstall protection while locked" is locked), and a tap then says why instead.
 */
@Composable
fun UninstallCard(cfg: Config, now: Long, admin: Boolean, onFix: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val holders = Policy.guardLocks(cfg, now).map { it.first to it.second }
    val s = guardStatus(cfg.guardOn, holders, admin, Policy.anyLocked(cfg, now))
    val bg by animateColorAsState(if (s.on) cs.primaryContainer else cs.surfaceContainer, label = "guard card")
    val tap = {
        when {
            // Held by its own padlock: its sheet is where it can be unlocked.
            holders.any { it.first == Policy.SELF_LOCK } -> nav.openLock(Policy.SELF_LOCK)
            holders.isNotEmpty() -> {
                val why = if (holders.size == 1) "${holders[0].second} ${Policy.isOrAre(holders[0].first)}" else "${holders.size} locks are"
                Toast.makeText(ctx, "It stays on while $why locked.", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Policy.setGuard(!cfg.guardOn)
            }
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = bg,
        contentColor = if (s.on) cs.onPrimaryContainer else cs.onSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(value = s.on, role = Role.Switch, onValueChange = { tap() })
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = if (s.activate) 2.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (s.on) {
                    Box(Modifier.size(32.dp).background(cs.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(LineIcons.Shield, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconDot(LineIcons.Shield, tint = if (s.warn) cs.tertiary else cs.onSurfaceVariant, size = 32.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Uninstall protection", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        s.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            s.warn -> cs.tertiary
                            s.on -> cs.onPrimaryContainer.copy(alpha = 0.8f)
                            else -> cs.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
                PadlockButton(cfg.selfLock, now) { nav.openLock(Policy.SELF_LOCK) }
            }
            if (s.activate) {
                // Lined up with the text above (icon 32 + gap 14 + row padding 16, less the button's own inset).
                TextButton(
                    onClick = { onFix("admin") },
                    modifier = Modifier.padding(start = 54.dp, bottom = 4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Activate device admin")
                }
            }
        }
    }
}

/**
 * Settings > Uninstall protection: its switch (with the optional padlock), the device administrator, and the way to
 * uninstall Willbolt while the protection is off. One short line each.
 */
@Composable
fun UninstallGroup(cfg: Config, now: Long, admin: Boolean, onFix: (String) -> Unit, onTurnOffAdmin: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val holders = Policy.guardLocks(cfg, now).map { it.first to it.second }
    val on = Policy.selfProtected(cfg, now)
    Group(
        "Uninstall protection",
        trailing = { PadlockButton(cfg.selfLock, now) { nav.openLock(Policy.SELF_LOCK) } },
    ) {
        SwitchItem(
            "Protect Willbolt",
            heldText(cfg.guardOn, holders)
                ?: if (on) "Settings can't stop, clear or uninstall it" else "Settings can stop, clear or uninstall it",
            checked = on,
            // While a lock holds it on, the lock's own way of opening is the way to turn it off.
            enabled = holders.isEmpty(),
        ) { Policy.setGuard(it) }
        GroupDivider()
        ItemRow(
            "Device administrator",
            if (admin) "On: Android itself refuses too" else "Optional: makes Android itself refuse too",
            trailing = {
                when {
                    !admin -> FilledTonalButton(onClick = { onFix("admin") }) { Text("Activate") }
                    !on -> TextButton(onClick = onTurnOffAdmin) { Text("Turn off") }
                    else -> {}
                }
            },
        )
        if (!on) {
            GroupDivider()
            ItemRow(
                "Uninstall Willbolt",
                if (admin) "Turn off the device administrator first" else null,
                enabled = !admin,
                titleColor = MaterialTheme.colorScheme.error,
                onClick = { ctx.startActivity(Fixes.uninstallIntent(ctx)) },
            )
        }
    }
}

/** Settings > Advanced: what the uninstall guard sees. (Self-repair is one of the Setup steps, not here.) */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AdvancedGroup(cfg: Config, onFix: (String) -> Unit) {
    Group("Advanced") {
        SwitchItem(
            "Guard diagnostics",
            "Logs what the uninstall guard sees in Settings windows, to tune it for your phone",
            checked = cfg.diagnostics,
        ) { on -> ConfigStore.update { it.copy(diagnostics = on) } }
    }
}

/** How to give Willbolt the one permission only a computer can give. */
@Composable
fun SelfRepairDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Self-repair (one USB step)") },
        text = {
            Column {
                Text(
                    "Only a computer can give Willbolt this one permission. With it, Willbolt turns its accessibility service " +
                        "back on within a second if anything switches it off, and undoes Private DNS tricks.\n\n" +
                        "Easiest: connect the phone with USB debugging on and run tools\\install-to-phone.ps1. Or run:",
                )
                Spacer(Modifier.height(8.dp))
                Text(ADB_GRANT, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { Fixes.copy(ctx, "adb command", ADB_GRANT) }) { Text("Copy command") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

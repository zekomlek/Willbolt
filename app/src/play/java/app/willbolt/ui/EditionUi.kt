package app.willbolt.ui

import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.willbolt.data.Config

// The screens that differ between the editions. This is the Play edition's: no padlock for Willbolt itself (Play does not
// allow guarding an app's own uninstall), no guard diagnostics, no USB step. The full edition's are in src/full.

/**
 * The row on Home: the optional device administrator, which makes Android ask you to switch it off before Willbolt can be
 * uninstalled. Only Activate is here; turning it off stays in Settings. Worded the way the rest of this edition is - it
 * promises nothing beyond what Android's own prompt does.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun UninstallCard(cfg: Config, now: Long, admin: Boolean, onFix: (String) -> Unit) {
    Group {
        ItemRow(
            "Device admin",
            if (admin) "On: Android asks you to switch it off before uninstalling."
            else "Optional: Android asks you to switch it off before uninstalling.",
            leading = { IconDot(LineIcons.Shield, size = 32.dp) },
            trailing = if (admin) {
                { Icon(LineIcons.Check, contentDescription = "On", tint = MaterialTheme.colorScheme.primary) }
            } else {
                { TextButton(onClick = { onFix("admin") }) { Text("Activate") } }
            },
        )
    }
}

/** Settings > Uninstalling: the optional device administrator, which makes Android ask you to switch it off first. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun UninstallGroup(cfg: Config, now: Long, admin: Boolean, onFix: (String) -> Unit, onTurnOffAdmin: () -> Unit) {
    val ctx = LocalContext.current
    Group("Uninstalling") {
        ItemRow(
            "Device administrator",
            if (admin) "On - Android asks you to turn this off before Willbolt can be uninstalled"
            else "Optional - Android asks you to turn it off first, a small speed bump",
            trailing = {
                if (!admin) {
                    FilledTonalButton(onClick = { onFix("admin") }) { Text("Activate") }
                } else {
                    TextButton(onClick = onTurnOffAdmin) { Text("Turn off") }
                }
            },
        )
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

/** Nothing under Advanced in the Play edition. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AdvancedGroup(cfg: Config, onFix: (String) -> Unit) {
}

/** The Play edition has no USB step. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SelfRepairDialog(onDismiss: () -> Unit) {
}

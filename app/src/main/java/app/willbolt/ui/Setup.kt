package app.willbolt.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.willbolt.Edition
import app.willbolt.R
import app.willbolt.core.Check
import app.willbolt.core.Health
import app.willbolt.core.Oem
import app.willbolt.data.ConfigStore
import app.willbolt.vpn.DnsVpnService
import kotlinx.coroutines.delay

/** The setup checks, refreshed every second while the screen is visible. */
@Composable
fun rememberChecks(): List<Check> {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var checks by remember { mutableStateOf(Health.run(ctx)) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Health.refreshAsync(ctx)
            while (true) {
                checks = Health.run(ctx)
                delay(1000)
            }
        }
    }
    return checks
}

/** Starts the DNS website filter and remembers that you want it running. */
fun startWebFilter(ctx: Context) {
    ConfigStore.update { it.copy(webFilterOn = true) }
    DnsVpnService.start(ctx, force = true)
}

fun stopWebFilter(ctx: Context) {
    ConfigStore.update { it.copy(webFilterOn = false) }
    DnsVpnService.stop(ctx)
}

private enum class SetupDialog { DISCLOSURE, OEM, SELF_REPAIR }

/**
 * Gives [content] a `fix(checkId)` that does whatever the matching "Fix" button means (open a system screen, ask for
 * a permission, show the steps) and shows the dialogs that go with it.
 */
@Composable
fun FixHost(content: @Composable (fix: (String) -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SetupDialog?>(null) }
    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) startWebFilter(ctx)
    }

    val fix: (String) -> Unit = { id ->
        when (id) {
            "accessibility" -> if (!cfg.disclosureAccepted) dialog = SetupDialog.DISCLOSURE else Fixes.turnOnAccessibility(ctx)
            "admin" -> ctx.startActivity(Fixes.adminIntent(ctx))
            "vpn" -> {
                if (DnsVpnService.running) {
                    // Running but skipped because of a fixed Private DNS server: that is changed in the network settings.
                    ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    val consent = VpnService.prepare(ctx)
                    if (consent == null) startWebFilter(ctx) else vpnLauncher.launch(consent)
                }
            }
            "battery" -> {
                Edition.batteryHint?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                ctx.startActivity(Fixes.batteryIntent(ctx))
            }
            "hibernation" -> ctx.startActivity(Fixes.unusedRestrictionsIntent(ctx))
            "oem" -> dialog = SetupDialog.OEM
            "selfheal" -> dialog = SetupDialog.SELF_REPAIR
            "autotime" -> ctx.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    content(fix)

    when (dialog) {
        null -> {}
        // The prominent disclosure Google Play and Android's own rules ask for: what is read, what it is for, and that
        // nothing is stored or sent. Only "Agree" goes on; tapping outside or "No thanks" turns nothing on.
        SetupDialog.DISCLOSURE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Willbolt needs the Accessibility service") },
            text = { Text(stringResource(R.string.disclosure_body), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = {
                    ConfigStore.update { it.copy(disclosureAccepted = true) }
                    dialog = null
                    Fixes.turnOnAccessibility(ctx)
                }) { Text("Agree") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("No thanks") } },
        )
        SetupDialog.OEM -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Keep Willbolt running") },
            text = {
                Column {
                    Text("${Oem.displayName}'s own battery manager can stop apps. Do this once:")
                    Spacer(Modifier.height(8.dp))
                    Oem.steps().forEachIndexed { i, s -> Text("${i + 1}. $s", Modifier.padding(vertical = 3.dp)) }
                    if (Edition.FULL) {
                        Spacer(Modifier.height(8.dp))
                        Hint(
                            "While Uninstall protection is on, Willbolt keeps its app info page and these battery screens closed " +
                                "(so nobody can switch Willbolt off there): do the steps first. If the phone is stopping Willbolt anyway, " +
                                "they stay open for two hours so it can be fixed.",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ConfigStore.update { it.copy(oemStepsDone = true, oemDoneMs = System.currentTimeMillis()) }
                    dialog = null
                }) { Text("Done") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { Oem.openBackgroundSettings(ctx) }) { Text("Open settings") }
                    TextButton(onClick = { dialog = null }) { Text("Later") }
                }
            },
        )
        SetupDialog.SELF_REPAIR -> SelfRepairDialog(onDismiss = { dialog = null })
    }
}

/** One setup check as a row: a tick or a warning, what it is, and its button. */
@Composable
fun CheckRow(c: Check, onFix: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ItemRow(
        title = c.title,
        subtitle = c.detail,
        leading = {
            IconDot(
                if (c.ok) LineIcons.Check else LineIcons.Alert,
                tint = when {
                    c.ok -> cs.primary
                    c.critical -> cs.error
                    else -> cs.tertiary
                },
                size = 32.dp,
            )
        },
        trailing = if (c.actionLabel != null) {
            { FilledTonalButton(onClick = onFix) { Text(c.actionLabel) } }
        } else null,
    )
}

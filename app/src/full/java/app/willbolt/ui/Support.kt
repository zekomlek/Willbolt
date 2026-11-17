package app.willbolt.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.willbolt.core.Support
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore

// Supporting Willbolt: full edition only. Play allows tips only through its own billing, so the Play edition has empty
// stand-ins with the same names in src/play (and this link is not in its build at all).

private const val COFFEE_URL = "https://buymeacoffee.com/zekomlek"

/** Opens the page in the browser. Willbolt itself sends nothing. */
private fun openCoffee(ctx: Context) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(COFFEE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Fixes.copy(ctx, "Buy me a coffee", COFFEE_URL)
        Toast.makeText(ctx, "No browser found. The link is copied.", Toast.LENGTH_LONG).show()
    }
}

/** When Willbolt was first installed on this phone (its clock); 0 when Android won't say. */
private fun installedAt(ctx: Context): Long = try {
    @Suppress("DEPRECATION")
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).firstInstallTime
} catch (_: Exception) {
    0L
}

/**
 * Home, under Password: the way to support Willbolt, there all the time. A warm coffee-coloured card of its own (the
 * theme's amber tertiary colours, so it reads well in light and dark) with a solid cup badge and a filled pill button.
 */
@Composable
fun SupportRow() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = { openCoffee(ctx) },
        shape = MaterialTheme.shapes.large,
        color = cs.tertiaryContainer,
        contentColor = cs.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp),
    ) {
        Row(
            Modifier
                .background(Brush.horizontalGradient(listOf(cs.tertiaryContainer, cs.tertiary.copy(alpha = 0.22f))))
                .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).background(cs.tertiary, CircleShape), contentAlignment = Alignment.Center) {
                Icon(LineIcons.Coffee, contentDescription = null, tint = cs.onTertiary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Buy me a coffee", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Willbolt is free. Like it? Support my projects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onTertiaryContainer.copy(alpha = 0.78f),
                )
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { openCoffee(ctx) },
                colors = ButtonDefaults.buttonColors(containerColor = cs.tertiary, contentColor = cs.onTertiary),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Support", maxLines = 1, softWrap = false) }
        }
    }
}

/**
 * Asked once on Home, when [Support.due] says so (installed 3+ days, 5+ blocks): either button, or tapping beside the
 * popup, puts it away for good. [allowed] is false while something else needs the screen (Willbolt is off, another
 * dialog is open). [now] is TimeGuard time; the install time is on the phone's clock, so the clock penalty is taken off.
 */
@Composable
fun SupportPrompt(cfg: Config, now: Long, allowed: Boolean) {
    val ctx = LocalContext.current
    val installed = remember { installedAt(ctx) }
    if (!allowed || !Support.due(installed, wallOf(now, cfg.clockPenaltyMs), cfg.blocksSeen, cfg.supportAsked)) return
    val done = { ConfigStore.update { it.copy(supportAsked = true) } }
    AlertDialog(
        onDismissRequest = done,
        icon = { Icon(LineIcons.Coffee, contentDescription = null) },
        title = { Text("Happy with Willbolt?") },
        text = {
            Text(
                "Willbolt is free: no ads, no paywall, no account. If it helps you, a coffee supports my projects.\n\n" +
                    "This is asked only once. The button stays on Home, under Password.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                openCoffee(ctx)
                done()
            }) { Text("Buy me a coffee") }
        },
        dismissButton = { TextButton(onClick = done) { Text("Not now") } },
    )
}

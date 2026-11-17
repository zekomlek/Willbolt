package app.willbolt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// What Willbolt promises about your data. Keep it true to PRIVACY.md: that file is the policy, this is its first line.
private val POINTS = listOf(
    "No account, no ads, no analytics and no server of its own - there is nowhere for your data to go.",
    "The screen check runs on this phone. Pictures are looked at in memory and thrown away.",
    "Your blocks, limits and activity log stay in Willbolt's private storage. Uninstalling deletes them.",
)
private const val DNS_NOTE =
    "Only if you start the website filter: DNS lookups go to the DNS server you pick (your network's by default)."

/** The first thing a new install shows on Home, so nobody has to dig for it. Gone for good after "Got it". */
@Composable
fun PrivacyCard(onPolicy: () -> Unit, onGotIt: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconDot(LineIcons.Shield, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Fully private", style = MaterialTheme.typography.titleLarge)
                    Text("Willbolt keeps everything on this phone.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            POINTS.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Icon(LineIcons.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(p, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Hint(DNS_NOTE, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPolicy) { Text("Privacy policy") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onGotIt) { Text("Got it") }
            }
        }
    }
}

/** The same promise as one quiet line under the title, once the card has been put away. */
@Composable
fun PrivacyBadge(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LineIcons.Shield, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Fully private - Willbolt keeps everything on this phone",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(LineIcons.Chevron, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

package app.willbolt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.BuildConfig
import app.willbolt.Edition
import app.willbolt.core.Health
import app.willbolt.data.ConfigStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val nav = LocalNav.current
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val checks = rememberChecks()
    var removing by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf(false) }

    FixHost { fix ->
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(LineIcons.Back, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                val setup = checks.filter { it.id != "admin" }
                val done = setup.count { it.ok }
                Group("Setup", trailing = { Hint("$done of ${setup.size} done", Modifier.padding(end = 8.dp)) }) {
                    setup.forEachIndexed { i, c ->
                        if (i > 0) GroupDivider()
                        CheckRow(c) { fix(c.id) }
                    }
                }

                // What is in these two depends on the edition: the full one can lock Willbolt itself and has guard diagnostics.
                UninstallGroup(cfg, now, admin = Health.isAdminActive(ctx), onFix = fix, onTurnOffAdmin = { removing = true })
                AdvancedGroup(cfg, fix)

                Group("About") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Willbolt ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                            Hint("${Edition.NAME}. Fully private: everything runs on this phone. No accounts, ads or analytics.")
                        }
                    }
                    GroupDivider()
                    AboutRow("Send feedback", "Opens your email app") { feedback = true }
                    GroupDivider()
                    AboutRow("Privacy policy", "What Willbolt reads and keeps") { nav.openLegal(LegalDoc.Privacy) }
                    GroupDivider()
                    AboutRow("Open-source licences", "The model, lists and libraries it is built from") { nav.openLegal(LegalDoc.Licences) }
                }
            }
        }
    }

    if (removing) {
        ConfirmDialog(
            title = "Turn off the device administrator?",
            text = "Willbolt can then be uninstalled like any other app.",
            confirmLabel = "Turn off",
            destructive = true,
            onConfirm = { Fixes.removeAdmin(ctx) },
            onDismiss = { removing = false },
        )
    }
    if (feedback) FeedbackDialog(onDismiss = { feedback = false })
}

@Composable
private fun AboutRow(title: String, subtitle: String, onClick: () -> Unit) {
    ItemRow(
        title,
        subtitle,
        trailing = { Icon(LineIcons.Chevron, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        onClick = onClick,
    )
}

package app.willbolt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.Edition
import app.willbolt.data.Event
import app.willbolt.data.EventLog

/** How an Activity entry looks: its icon and a plain name for its kind. */
private data class Look(val icon: ImageVector, val title: String, val warn: Boolean = false)

private fun look(type: String): Look = when (type) {
    "APP_BLOCK" -> Look(LineIcons.Wall, "App blocked")
    "SITE_BLOCK" -> Look(LineIcons.Globe, "Site blocked")
    "NSFW" -> Look(LineIcons.Eye, "Screen check", warn = true)
    "BLANK" -> Look(LineIcons.Eye, "Private tab blocked")
    "LIMIT" -> Look(LineIcons.Timer, "Time limit")
    "BREAK" -> Look(LineIcons.Hourglass, "Break")
    "LOCK" -> Look(LineIcons.Lock, "Lock")
    "PASSWORD" -> Look(LineIcons.Key, "Password")
    "TAMPER" -> Look(LineIcons.Shield, "Guarded", warn = true)
    "ADMIN" -> Look(LineIcons.Shield, if (Edition.FULL) "Uninstall protection" else "Device administrator")
    "HEAL" -> Look(LineIcons.Check, "Self-repair")
    "VPN" -> Look(LineIcons.Globe, "Website filter")
    "CLOCK" -> Look(LineIcons.Clock, "Clock changed", warn = true)
    "GAP" -> Look(LineIcons.Alert, "Switched off", warn = true)
    "SERVICE" -> Look(LineIcons.Info, "Accessibility")
    "BOOT" -> Look(LineIcons.Info, "Phone started")
    "UPDATE" -> Look(LineIcons.Info, "Updated")
    "GUARD" -> Look(LineIcons.Info, "Guard diagnostics")
    else -> Look(LineIcons.Info, type.lowercase().replaceFirstChar { it.uppercase() })
}

/** One entry: icon, what happened, details, time. */
@Composable
fun EventRow(e: Event) {
    val l = look(e.type)
    val tint = if (l.warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDot(l.icon, tint = tint, size = 34.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                e.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(Fmt.clock(e.t), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ActivityScreen() {
    val events by EventLog.events.collectAsStateWithLifecycle()
    var clearing by remember { mutableStateOf(false) }
    val byDay = events.asReversed().groupBy { Fmt.dayIndex(it.t) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Activity", "What Willbolt did and when. Never pictures - only the app, site or action.") {
                if (events.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Clear") }
            }
        }
        if (events.isEmpty()) {
            item { Group { ItemRow("Nothing yet", "Blocks, checks and lock changes show up here.") } }
        }
        byDay.forEach { (_, list) ->
            item(key = "day-${list.first().t}") {
                Group(Fmt.day(list.first().t)) {
                    list.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        EventRow(e)
                    }
                }
            }
        }
    }

    if (clearing) {
        ConfirmDialog(
            title = "Clear the activity log?",
            text = "Everything in it is deleted from this phone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = { EventLog.clear() },
            onDismiss = { clearing = false },
        )
    }
}

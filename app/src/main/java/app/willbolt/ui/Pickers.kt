package app.willbolt.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** "09:00" (or a date) as a button that opens a picker. */
@Composable
fun PickButton(text: String, enabled: Boolean = true, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** A time of day ([minutes] after midnight) as a button that opens [TimeDialog]. */
@Composable
fun TimeButton(minutes: Int, enabled: Boolean, onClick: () -> Unit) =
    PickButton(Fmt.clock(minutesToEpoch(minutes)), enabled, onClick = onClick)

/** Today at [minutes] past midnight, only for formatting a time of day. */
fun minutesToEpoch(minutes: Int): Long {
    val c = java.util.Calendar.getInstance()
    c.set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
    c.set(java.util.Calendar.MINUTE, minutes % 60)
    return c.timeInMillis
}

/** A time of day, typed (the default) or picked on a clock face. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, minutes: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = Fmt.is24)
    var clockFace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (clockFace) TimePicker(state) else TimeInput(state)
                TextButton(onClick = { clockFace = !clockFace }) { Text(if (clockFace) "Type it instead" else "Pick on a clock") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A calendar day, picked on a month page or typed. The picker is shown in British English (day/month/year), like the
 * rest of Willbolt's text, whatever language the phone is in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(day: LocalDate, first: LocalDate, last: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMillis(day),
        yearRange = first.year..last.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcDay(utcTimeMillis) in first..last
            override fun isSelectableYear(year: Int) = year in first.year..last.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(utcDay(it)) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        // Inside the dialog's own window, which otherwise brings back the phone's language for month and day names.
        val base = LocalConfiguration.current
        val english = remember(base) { Configuration(base).apply { setLocale(Locale.UK) } }
        CompositionLocalProvider(LocalConfiguration provides english) { DatePicker(state, showModeToggle = true) }
    }
}

// The date picker speaks in UTC midnights.
private fun utcMillis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun utcDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

private val QUICK_ENDS = listOf(3_600_000L to "1 h", 86_400_000L to "1 day", 7 * 86_400_000L to "1 week", 30 * 86_400_000L to "30 days")

/**
 * A moment in the next [maxAheadMs]: a day and a time of day, each its own button, plus quick choices counted from
 * now. [endWall] and [onEnd] are on the phone's clock (epoch ms), whole minutes.
 */
@Composable
fun EndPicker(endWall: Long, onEnd: (Long) -> Unit, maxAheadMs: Long, modifier: Modifier = Modifier, enabled: (Long) -> Boolean = { true }) {
    val zone = ZoneId.systemDefault()
    val nowWall = System.currentTimeMillis()
    val at = Instant.ofEpochMilli(endWall).atZone(zone)
    var dateOpen by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val passed = endWall <= nowWall
    val bad = passed || !enabled(endWall)

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PickButton(Fmt.date(endWall), color = if (bad) MaterialTheme.colorScheme.error else Color.Unspecified) { dateOpen = true }
            PickButton(Fmt.clock(endWall), color = if (bad) MaterialTheme.colorScheme.error else Color.Unspecified) { timeOpen = true }
            Text(
                if (passed) "has passed" else "in " + fmtDuration(endWall - nowWall),
                style = MaterialTheme.typography.bodyMedium,
                color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Chips(
            QUICK_ENDS,
            selected = null,
            onPick = { ms -> onEnd(wholeMinute(System.currentTimeMillis() + ms)) },
            enabled = { ms -> enabled(wholeMinute(nowWall + ms)) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (dateOpen) {
        val today = LocalDate.now(zone)
        DateDialog(
            day = at.toLocalDate(),
            first = today,
            last = Instant.ofEpochMilli(nowWall + maxAheadMs).atZone(zone).toLocalDate(),
            onPick = { d -> onEnd(at.with(d).toInstant().toEpochMilli()) },
            onDismiss = { dateOpen = false },
        )
    }
    if (timeOpen) {
        TimeDialog(
            title = "Time",
            minutes = at.hour * 60 + at.minute,
            onPick = { m -> onEnd(at.withHour(m / 60).withMinute(m % 60).withSecond(0).withNano(0).toInstant().toEpochMilli()) },
            onDismiss = { timeOpen = false },
        )
    }
}

/** Rounded up to the next whole minute. */
fun wholeMinute(ms: Long) = (ms + 59_999L) / 60_000L * 60_000L

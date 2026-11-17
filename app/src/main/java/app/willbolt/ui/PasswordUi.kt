package app.willbolt.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.willbolt.core.Passwords
import app.willbolt.core.Policy
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A password box with an eye to show what was typed. */
@Composable
fun PasswordField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) LineIcons.EyeOff else LineIcons.Eye, contentDescription = if (shown) "Hide password" else "Show password")
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier,
    )
}

/** A new password, typed twice, and what to know about it. */
@Composable
fun NewPasswordFields(pw1: String, onPw1: (String) -> Unit, pw2: String, onPw2: (String) -> Unit) {
    val short = pw1.isNotEmpty() && pw1.length < Passwords.MIN_LENGTH
    val differ = pw2.isNotEmpty() && pw1 != pw2
    PasswordField(pw1, onPw1, "New password", Modifier.fillMaxWidth(), isError = short)
    Spacer(Modifier.height(8.dp))
    PasswordField(pw2, onPw2, "Repeat it", Modifier.fillMaxWidth(), isError = differ, imeAction = ImeAction.Done)
    Hint(
        when {
            short -> "At least ${Passwords.MIN_LENGTH} characters."
            differ -> "The two don't match yet."
            else -> "One password for every lock. Changing it needs this one - or a 4-day wait if it's lost."
        },
        Modifier.padding(top = 6.dp),
        color = if (short || differ) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** What a refused password means, in a sentence. */
fun pwText(r: Policy.PwResult, cfg: Config): String = when (r) {
    Policy.PwResult.OK -> ""
    Policy.PwResult.WRONG -> {
        val left = Passwords.TRIES - cfg.passwordFails % Passwords.TRIES
        "That's not the password." + if (left <= 2) " $left more ${if (left == 1) "try" else "tries"} before a 5-minute pause." else ""
    }
    Policy.PwResult.PAUSED -> "Too many wrong tries - wait a few minutes."
    Policy.PwResult.TOO_SHORT -> "At least ${Passwords.MIN_LENGTH} characters."
    Policy.PwResult.NOT_SET -> "There is no password yet."
    Policy.PwResult.IN_USE -> "Some locks open with it. Unlock them first."
}

/** The password row on Home: set it up, change it, or see the 4-day way round. */
@Composable
fun PasswordRow(cfg: Config, now: Long, onOpen: () -> Unit) {
    val uses = Policy.passwordLocks(cfg, now).size
    val subtitle = when {
        cfg.password == null -> "Not set - one password that can open your locks"
        cfg.passwordResetAt != 0L && now >= cfg.passwordResetAt -> "You can set a new one now, without the old one"
        cfg.passwordResetAt != 0L -> "Without the old one: possible " + Fmt.at(wallOf(cfg.passwordResetAt, cfg.clockPenaltyMs))
        uses == 1 -> "Set - it opens 1 lock"
        uses > 1 -> "Set - it opens $uses locks"
        else -> "Set"
    }
    ItemRow(
        title = "Password",
        subtitle = subtitle,
        leading = { IconDot(LineIcons.Key) },
        trailing = { TextButton(onClick = onOpen) { Text(if (cfg.password == null) "Set up" else "Change") } },
        onClick = onOpen,
    )
}

/**
 * Setting the password the first time, or changing it: with the current one, or without it once the 4-day wait asked
 * for under "Forgot it?" is over.
 */
@Composable
fun PasswordDialog(onDismiss: () -> Unit) {
    val cfg by ConfigStore.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var forgot by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val has = cfg.password != null
    val free = Policy.passwordFree(cfg, now)
    val paused = !free && now < cfg.passwordPauseUntil

    fun run(job: () -> Policy.PwResult) {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.Default) { job() }
            busy = false
            if (r == Policy.PwResult.OK) onDismiss() else {
                error = pwText(r, ConfigStore.value)
                old = ""
            }
        }
    }

    if (forgot) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(LineIcons.Hourglass, contentDescription = null) },
            title = { Text("Forgot the password?") },
            text = {
                Text(
                    "Without it you can set a new one in 4 days, " +
                        Fmt.at(System.currentTimeMillis() + Passwords.RESET_WAIT_MS) +
                        ". Until then everything stays as it is, and you can call it off.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Policy.askPasswordReset()
                    onDismiss()
                }) { Text("Start the 4-day wait") }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("Back") } },
        )
        return
    }

    val ready = !busy && !paused && pw1.length >= Passwords.MIN_LENGTH && pw1 == pw2 && (free || old.isNotEmpty())
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(LineIcons.Key, contentDescription = null) },
        title = { Text(if (!has) "Set a password" else if (free) "Set a new password" else "Change the password") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (has && free) {
                    Text("The 4-day wait is over, so the old password isn't needed.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                if (has && !free) {
                    PasswordField(old, { old = it; error = null }, "Current password", Modifier.fillMaxWidth(), isError = error != null)
                    Spacer(Modifier.height(8.dp))
                }
                NewPasswordFields(pw1, { pw1 = it }, pw2, { pw2 = it })
                val note = when {
                    paused -> "Too many wrong tries. Try again in ${fmtCountdown(cfg.passwordPauseUntil - now)}."
                    else -> error
                }
                if (note != null) Hint(note, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error)
                if (has && !free) {
                    if (cfg.passwordResetAt == 0L) {
                        TextButton(onClick = { forgot = true }) { Text("Forgot it?") }
                    } else {
                        Hint(
                            "Without the old one: possible " + Fmt.at(wallOf(cfg.passwordResetAt, cfg.clockPenaltyMs)) + ".",
                            Modifier.padding(top = 10.dp),
                        )
                        TextButton(onClick = { Policy.cancelPasswordReset() }) { Text("Call that off") }
                    }
                }
                if (has && free) TextButton(onClick = { Policy.cancelPasswordReset(); onDismiss() }) { Text("Keep the old one") }
                if (has && Policy.passwordLocks(cfg, now).isEmpty()) {
                    val canRemove = !busy && !paused && (free || old.isNotEmpty())
                    TextButton(onClick = { run { Policy.removePassword(old) } }, enabled = canRemove) {
                        Text("Remove the password", color = MaterialTheme.colorScheme.error.copy(alpha = if (canRemove) 1f else 0.38f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { run { Policy.setPassword(pw1, old) } }, enabled = ready) {
                Text(if (busy) "Checking..." else if (has) "Change" else "Set")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

package app.willbolt.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.willbolt.BuildConfig
import app.willbolt.Edition

/**
 * Feedback is an email you write yourself: Willbolt opens your email app with the address filled in and sends nothing on
 * its own (it has no server and no network code for this). The version and phone model go at the bottom so a problem can
 * be found; they are text in the email that you can read and delete.
 */
object Feedback {
    fun compose(ctx: Context) {
        val body = "\n\n\n--\nWillbolt ${BuildConfig.VERSION_NAME} (${Edition.NAME}), " +
            "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}"
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (BuildConfig.FEEDBACK_EMAIL.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Willbolt feedback")
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No email app: hand over the address instead.
            if (BuildConfig.FEEDBACK_EMAIL.isNotBlank()) Fixes.copy(ctx, "Feedback address", BuildConfig.FEEDBACK_EMAIL)
            android.widget.Toast.makeText(ctx, "No email app found. The address is copied.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun FeedbackDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send feedback") },
        text = {
            Text(
                "This opens your email app with a message to the developer. Nothing is sent until you press send there.\n\n" +
                    "The message ends with Willbolt's version and your phone's model, which helps to find problems. " +
                    "Read it, delete what you like, and don't include anything private.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                Feedback.compose(ctx)
                onDismiss()
            }) { Text("Write email") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

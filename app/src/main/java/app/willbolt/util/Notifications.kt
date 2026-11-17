package app.willbolt.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.willbolt.R
import app.willbolt.core.Policy
import app.willbolt.core.TimeGuard
import app.willbolt.data.ConfigStore
import app.willbolt.ui.MainActivity
import java.text.DateFormat
import java.util.Date

/**
 * Willbolt sends no alerts. The only notification left is the one Android requires while a service runs in the
 * background - the guard while something is locked, the website filter while it is on - and it is as quiet as
 * Android allows: no sound, no pop-up, no status-bar icon. On Android 13 and newer Willbolt does not even ask to
 * post notifications, so these stay out of the notification shade altogether (they show only in Android's list of
 * active apps).
 */
object Notifications {
    private const val CH_BACKGROUND = "background"

    /** Channels of earlier versions ("Running", "Alerts"); deleting them also clears anything still showing. */
    private val OLD_CHANNELS = listOf("protection", "alerts")

    /** Willbolt's deep teal, for the small icon's accent. */
    private const val ACCENT = 0xFF12566C.toInt()

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        for (id in OLD_CHANNELS) {
            try {
                nm.deleteNotificationChannel(id)
            } catch (_: Throwable) {
            }
        }
        nm.createNotificationChannel(
            NotificationChannel(CH_BACKGROUND, "Running in the background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Android needs one while Willbolt guards something locked or runs the website filter"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun foreground(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_willbolt)
            .setColor(ACCENT)
            .setContentTitle("Willbolt")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** What the guard's notification says. */
    fun statusLine(): String {
        val cfg = ConfigStore.value
        val now = TimeGuard.now()
        val next = Policy.allLocks(cfg)
            .filter { Policy.state(it.third, now) == Policy.State.UNLOCKING }
            .minByOrNull { Policy.opensAt(it.third) }
        if (next != null) {
            val at = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Policy.opensAt(next.third) - cfg.clockPenaltyMs))
            return "${next.second} unlocks at $at"
        }
        return when (val n = Policy.lockedCount(cfg, now)) {
            0 -> "Nothing is locked"
            1 -> "Guarding 1 locked thing"
            else -> "Guarding $n locked things"
        }
    }
}

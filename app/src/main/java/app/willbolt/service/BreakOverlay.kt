package app.willbolt.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.willbolt.core.TimeGuard
import kotlin.math.ceil

private const val TAG = "WillboltBreak"

/**
 * Full-screen countdown laid over an app during a "take a break" timer. It swallows every touch, so the app underneath
 * cannot be used until the timer has run out; it removes itself when the time is up. Pressing Home still works.
 *
 * While it is on screen the phone's media sound (videos, music, games) is muted, and muted again if a volume key turns it
 * back on; when it goes, the sound is put back as it was.
 */
class BreakOverlay(private val service: AccessibilityService) {
    companion object {
        private const val PREFS = "break_sound"
        private const val KEY_MUTED = "muted"

        /**
         * Willbolt was stopped while a countdown had the sound muted (a crash, an update): turn it back on. Called when the
         * accessibility service starts.
         */
        fun restoreSound(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!prefs.getBoolean(KEY_MUTED, false)) return
                context.getSystemService(AudioManager::class.java)
                    ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                prefs.edit().remove(KEY_MUTED).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "could not turn the sound back on", t)
            }
        }
    }

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val audio: AudioManager? = service.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var countdown: TextView? = null
    private var bar: ProgressBar? = null
    private var shownFor: Pair<String, Boolean>? = null

    /** The sound is being held muted for this countdown. */
    private var holdingMute = false

    /** The sound was already muted when the countdown came up, so it stays muted afterwards. */
    private var wasMuted = false

    /** When the timer on screen ends (TimeGuard time); 0 when nothing is showing. */
    var deadline = 0L
        private set
    private var totalMs = 1L

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (root != null) handler.postDelayed(this, 200)
        }
    }

    val isShowing: Boolean get() = root != null

    /**
     * Shows the countdown over [appLabel] until [deadline]; [totalMs] is the whole break, for the bar. [pausesAway]: the
     * timer stops while another app is in use, which the screen says.
     */
    fun show(appLabel: String, deadline: Long, totalMs: Long, pausesAway: Boolean, onLeave: () -> Unit) {
        if (root != null && shownFor == (appLabel to pausesAway)) {
            // Same screen, another end (one break straight after the other): just move the countdown; the sound stays off.
            this.deadline = deadline
            this.totalMs = totalMs.coerceAtLeast(1L)
            refresh()
            return
        }
        removeView()
        this.deadline = deadline
        this.totalMs = totalMs.coerceAtLeast(1L)
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val view = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(OverlayStyle.BACKGROUND or 0xFF000000.toInt())
            setPadding(dp(36), dp(32), dp(36), dp(32))
            isClickable = true // swallow touches
        }
        val (mark, markLp) = OverlayStyle.mark(service, dp(56), dp(24))
        view.addView(mark, markLp)
        view.addView(OverlayStyle.title(service, "Take a moment"))
        val body = "$appLabel is paused until the timer ends." +
            if (pausesAway) "\nThe timer stops while you're in another app." else ""
        view.addView(OverlayStyle.body(service, body, dp(8)).apply { setPadding(0, dp(8), 0, dp(28)) })
        val time = TextView(service).apply {
            setTextColor(OverlayStyle.TITLE)
            textSize = 76f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
        }
        countdown = time
        view.addView(time)
        val progress = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(OverlayStyle.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3376DBD1"))
        }
        bar = progress
        view.addView(progress, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        view.addView(
            Button(service).apply {
                text = "Go to home screen"
                isAllCaps = false
                setTextColor(OverlayStyle.TITLE)
                textSize = 15f
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), OverlayStyle.ACCENT)
                    setColor(Color.TRANSPARENT)
                }
                stateListAnimator = null
                setPadding(dp(28), dp(10), dp(28), dp(10))
                setOnClickListener { onLeave() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(32)
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(view, lp)
            root = view
            shownFor = appLabel to pausesAway
            refresh()
            handler.postDelayed(ticker, 200)
        } catch (t: Throwable) {
            Log.w(TAG, "could not show the break timer", t)
            countdown = null
            bar = null
            this.deadline = 0L
            releaseSound()
        }
    }

    private fun refresh() {
        val left = deadline - TimeGuard.now()
        if (left <= 0L) {
            hide()
            return
        }
        holdSound()
        countdown?.text = formatCountdown(left)
        bar?.progress = (1000L - (left * 1000L / totalMs)).coerceIn(0L, 1000L).toInt()
    }

    fun hide() {
        removeView()
        releaseSound()
    }

    private fun removeView() {
        handler.removeCallbacks(ticker)
        val v = root ?: return
        root = null
        shownFor = null
        countdown = null
        bar = null
        deadline = 0L
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    /** Mutes the media sound, and mutes it again if it was turned back on (checked with every update of the countdown). */
    private fun holdSound() {
        val am = audio ?: return
        try {
            if (!holdingMute) {
                holdingMute = true
                wasMuted = am.isStreamMute(AudioManager.STREAM_MUSIC)
                if (!wasMuted) prefs().edit().putBoolean(KEY_MUTED, true).apply()
            }
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not mute the sound", t)
        }
    }

    /** Puts the sound back as it was before the countdown. */
    private fun releaseSound() {
        if (!holdingMute) return
        holdingMute = false
        if (wasMuted) return
        try {
            audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            prefs().edit().remove(KEY_MUTED).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "could not turn the sound back on", t)
        }
    }

    private fun prefs() = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun formatCountdown(ms: Long): String {
        val s = ceil(ms / 1000.0).toLong()
        return "%d:%02d".format(s / 60, s % 60)
    }
}

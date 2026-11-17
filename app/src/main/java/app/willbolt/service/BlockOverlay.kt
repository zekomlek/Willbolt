package app.willbolt.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.willbolt.R

private const val TAG = "WillboltOverlay"

/** The look of Willbolt's full-screen cards (same palette as the app). */
internal object OverlayStyle {
    val BACKGROUND = Color.parseColor("#F5061A22")
    val TITLE = Color.parseColor("#F6EDD8")
    val TEXT = Color.parseColor("#A8C4C6")
    val ACCENT = Color.parseColor("#76DBD1")

    fun mark(service: AccessibilityService, sizePx: Int, bottomPx: Int): Pair<View, LinearLayout.LayoutParams> =
        ImageView(service).apply { setImageResource(R.drawable.willbolt_mark) } to
            LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = bottomPx
            }

    fun title(service: AccessibilityService, text: String) = TextView(service).apply {
        this.text = text
        setTextColor(TITLE)
        textSize = 24f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }

    fun body(service: AccessibilityService, text: String, topPx: Int) = TextView(service).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 16f
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.15f)
        setPadding(0, topPx, 0, 0)
    }
}

/** Full-screen "this is blocked" card. Uses the accessibility-overlay window type, so no extra permission is needed. */
class BlockOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val hider = Runnable { hide() }

    val isShowing: Boolean get() = view != null

    fun show(title: String, message: String, holdMs: Long = 3000L) {
        handler.removeCallbacks(hider)
        hide()
        val density = service.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(OverlayStyle.BACKGROUND)
            setPadding(dp(36), dp(32), dp(36), dp(32))
        }
        val (mark, markLp) = OverlayStyle.mark(service, dp(72), dp(28))
        root.addView(mark, markLp)
        root.addView(OverlayStyle.title(service, title))
        root.addView(OverlayStyle.body(service, message, dp(10)))
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
            wm.addView(root, lp)
            view = root
            handler.postDelayed(hider, holdMs)
        } catch (t: Throwable) {
            Log.w(TAG, "could not show overlay", t)
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }
}

/** Tiny corner readout of the live adult-content score, for tuning the sensitivity on your own phone. */
class ScoreHud(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var tv: TextView? = null

    fun update(text: String) {
        val t = tv ?: create() ?: return
        t.text = text
    }

    private fun create(): TextView? {
        val t = TextView(service).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            textSize = 11f
            setPadding(12, 6, 12, 6)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8
            y = 140
        }
        return try {
            wm.addView(t, lp)
            tv = t
            t
        } catch (e: Throwable) {
            Log.w(TAG, "could not show HUD", e)
            null
        }
    }

    fun hide() {
        val t = tv ?: return
        tv = null
        try {
            wm.removeView(t)
        } catch (_: Throwable) {
        }
    }
}

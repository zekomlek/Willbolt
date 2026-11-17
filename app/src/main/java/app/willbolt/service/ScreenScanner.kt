package app.willbolt.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import app.willbolt.data.Config
import app.willbolt.data.ConfigStore
import app.willbolt.ml.NsfwClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The "live screen check". Every [app.willbolt.data.Config.scanIntervalSec] seconds, while the screen is on and unlocked,
 * it asks Android for a screenshot (AccessibilityService.takeScreenshot, Android 11+), cuts it into one or two pieces,
 * runs each piece through the on-device model and decides whether to act. Frames live in memory for a fraction of a
 * second and are never written to disk or sent anywhere.
 */
@RequiresApi(Build.VERSION_CODES.R)
class ScreenScanner(
    private val svc: GuardAccessibilityService,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "ScreenScanner"
    }

    private sealed interface Shot {
        class Ok(val bitmap: Bitmap) : Shot
        class Fail(val code: Int) : Shot
    }

    private class Scan(val prob: Float, val blank: Boolean, val ran: Boolean, val ms: Long)

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "willbolt-nsfw").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val dispatcher = executor.asCoroutineDispatcher()

    private var classifier: NsfwClassifier? = null
    private var job: Job? = null

    private val recent = ArrayDeque<Float>()
    private var cooldownUntil = 0L
    private var blankStreak = 0

    private val lastThumbs = arrayOfNulls<IntArray>(3)
    private val lastProbs = FloatArray(3)
    private var lastTileCount = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        executor.execute {
            try {
                classifier?.close()
            } catch (_: Throwable) {
            }
            classifier = null
        }
        executor.shutdown()
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val wait = try {
                step()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "scan step failed", t)
                4_000L
            }
            delay(wait)
        }
    }

    private suspend fun step(): Long {
        val cfg = ConfigStore.value
        if (!cfg.scanEnabled) {
            svc.hud.hide()
            return 3_000L
        }
        if (!NsfwClassifier.isAvailable(svc)) return 15_000L

        val pm = svc.getSystemService(PowerManager::class.java)
        val km = svc.getSystemService(KeyguardManager::class.java)
        if (pm == null || !pm.isInteractive || km?.isKeyguardLocked == true) return 2_500L

        val fg = svc.foregroundPkg
        if (fg.isEmpty() || fg == svc.packageName || svc.overlayShowing()) return 1_500L

        val shot = capture()
        if (shot is Shot.Fail) {
            return if (shot.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 1_200L else 3_000L
        }
        val bitmap = (shot as Shot.Ok).bitmap
        val scan = try {
            withContext(dispatcher) { analyze(bitmap, cfg) }
        } finally {
            bitmap.recycle()
        }

        if (cfg.hudEnabled) {
            svc.hud.update("nsfw %.2f  %d ms  %s".format(scan.prob, scan.ms, fg.substringAfterLast('.')))
        } else {
            svc.hud.hide()
        }

        // A browser window that comes back completely black is almost always a private/incognito tab,
        // which Android hides from screenshots. Treat it like an unscannable page.
        if (scan.blank && cfg.blockBlankBrowsers && BrowserUrl.isBrowser(fg)) {
            blankStreak++
            if (blankStreak >= 3) {
                blankStreak = 0
                svc.onBlankBrowser(fg)
            }
        } else {
            blankStreak = 0
        }

        decide(scan.prob, cfg, fg)
        return cfg.scanIntervalSec.coerceIn(1, 120) * 1000L
    }

    // ---- screenshot ----------------------------------------------------------------------------------

    private suspend fun capture(): Shot = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var bmp: Bitmap? = null
                        try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            hb.close()
                        } catch (t: Throwable) {
                            Log.w(TAG, "screenshot decode failed", t)
                        }
                        if (cont.isActive) cont.resume(if (bmp != null) Shot.Ok(bmp) else Shot.Fail(-1))
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(Shot.Fail(errorCode))
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot threw", t)
            if (cont.isActive) cont.resume(Shot.Fail(-2))
        }
    }

    // ---- analysis (runs on the single "willbolt-nsfw" thread) ----------------------------------------------

    private fun analyze(bmp: Bitmap, cfg: Config): Scan {
        val t0 = SystemClock.elapsedRealtime()
        val blank = isBlank(bmp)
        val tiles = tileRects(bmp.width, bmp.height, tileCount(bmp.width, bmp.height))
        if (tiles.size != lastTileCount) {
            lastThumbs.fill(null)
            lastTileCount = tiles.size
        }
        val clf = classifier ?: NsfwClassifier.create(svc).also { classifier = it }

        var maxP = 0f
        var ran = false
        for ((i, r) in tiles.withIndex()) {
            val tile = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
            val thumb = thumbnail(tile)
            val prev = lastThumbs[i]
            val p = if (prev != null && difference(prev, thumb) < 5.0) {
                lastProbs[i]
            } else {
                val scaled = toModelInput(tile)
                val v = clf.nsfwProbability(scaled)
                if (scaled !== tile) scaled.recycle()
                ran = true
                v
            }
            lastThumbs[i] = thumb
            lastProbs[i] = p
            if (tile !== bmp) tile.recycle()
            maxP = max(maxP, p)
        }
        return Scan(maxP, blank, ran, SystemClock.elapsedRealtime() - t0)
    }

    /**
     * The model looks at a 384x384 square. A tall phone screen squeezed into one square would lose small pictures, so
     * tall screens are checked as two overlapping halves; squarish screens (a tablet, a folded phone) as one piece.
     */
    private fun tileCount(w: Int, h: Int): Int = if (max(w, h).toFloat() / minOf(w, h) >= 1.5f) 2 else 1

    /** Slices along the long edge, overlapping by ~10%, each roughly square so little is distorted. */
    private fun tileRects(w: Int, h: Int, n: Int): List<Rect> {
        if (n <= 1) return listOf(Rect(0, 0, w, h))
        val out = ArrayList<Rect>(n)
        if (h >= w) {
            val th = minOf(h, ceil(h / n.toFloat() * 1.1f).toInt())
            for (i in 0 until n) {
                val top = (i * (h - th)) / (n - 1)
                out.add(Rect(0, top, w, top + th))
            }
        } else {
            val tw = minOf(w, ceil(w / n.toFloat() * 1.1f).toInt())
            for (i in 0 until n) {
                val left = (i * (w - tw)) / (n - 1)
                out.add(Rect(left, 0, left + tw, h))
            }
        }
        return out
    }

    /** Halves repeatedly (a cheap box filter) until within 2x of the model size, then a final resize. */
    private fun toModelInput(src: Bitmap): Bitmap {
        val size = NsfwClassifier.SIZE
        var cur = src
        while (cur.width >= size * 2 && cur.height >= size * 2) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src && next !== cur) cur.recycle()
            cur = next
        }
        val out = Bitmap.createScaledBitmap(cur, size, size, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    private fun thumbnail(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256)
        s.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (s !== b) s.recycle()
        return IntArray(256) { i ->
            val p = px[i]
            (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }
    }

    private fun difference(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    /** True if the middle 80% of the frame is pure black - what a FLAG_SECURE window looks like. */
    private fun isBlank(b: Bitmap): Boolean {
        val w = b.width
        val h = b.height
        val y0 = (h * 0.1f).toInt()
        val y1 = (h * 0.9f).toInt()
        for (iy in 0 until 20) {
            for (ix in 0 until 20) {
                val x = (w - 1) * ix / 19
                val y = y0 + (y1 - y0 - 1) * iy / 19
                val p = b.getPixel(x, y)
                if (((p shr 16) and 0xFF) > 3 || ((p shr 8) and 0xFF) > 3 || (p and 0xFF) > 3) return false
            }
        }
        return true
    }

    // ---- decision -------------------------------------------------------------------------------------------

    private fun decide(p: Float, cfg: Config, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < cooldownUntil) return
        val (thr, strong) = NsfwClassifier.thresholds(cfg.scanSensitivity)

        recent.addLast(p)
        while (recent.size > 4) recent.removeFirst()
        val hits = recent.count { it >= thr }
        if (p >= strong || hits >= 2) {
            cooldownUntil = now + 5_000L
            recent.clear()
            svc.onNsfwDetected(p, pkg)
        }
    }
}

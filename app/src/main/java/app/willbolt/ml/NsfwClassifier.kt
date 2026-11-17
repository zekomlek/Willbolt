package app.willbolt.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * On-device adult-content classifier: Marqo/nsfw-image-detection-384 (Apache-2.0), a tiny ViT, converted to ONNX.
 * Input is a 384x384 RGB picture scaled to [-1, 1]; output is two logits, index 0 = NSFW, index 1 = safe.
 * Runs entirely on the phone - pictures are never saved or sent anywhere.
 */
class NsfwClassifier private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
) : AutoCloseable {

    companion object {
        const val SIZE = 384
        private const val MODEL_ASSET = "nsfw/model.onnx"

        /**
         * Sensitivity 0..100 (higher = stricter) -> (threshold, strongThreshold).
         * One scan at or above "strong" acts immediately; two of the last four at or above "threshold" also act.
         */
        fun thresholds(sensitivity: Int): Pair<Float, Float> {
            val thr = 0.95f - 0.35f * (sensitivity.coerceIn(0, 100) / 100f)
            return thr to (thr + (1f - thr) * 0.6f)
        }

        fun isAvailable(ctx: Context): Boolean = try {
            ctx.assets.open(MODEL_ASSET).close()
            true
        } catch (_: Exception) {
            false
        }

        fun create(ctx: Context): NsfwClassifier {
            val env = OrtEnvironment.getEnvironment()
            // onnxruntime-android ships a Microsoft telemetry client. Its provider is removed in the manifest so it never
            // starts; this turns it off in the library as well. Willbolt sends nothing anywhere.
            try {
                env.setTelemetry(false)
            } catch (_: Throwable) {
            }
            val bytes = ctx.assets.open(MODEL_ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(bytes, opts)
            return NsfwClassifier(env, session, session.inputNames.first())
        }
    }

    private val pixels = IntArray(SIZE * SIZE)
    private val floats: FloatBuffer =
        ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** Probability (0..1) that [bmp] (exactly 384x384) is adult content. Not thread-safe. */
    fun nsfwProbability(bmp: Bitmap): Float {
        require(bmp.width == SIZE && bmp.height == SIZE) { "bitmap must be ${SIZE}x$SIZE" }
        bmp.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            floats.put(i, ((p shr 16) and 0xFF) / 127.5f - 1f)
            floats.put(plane + i, ((p shr 8) and 0xFF) / 127.5f - 1f)
            floats.put(2 * plane + i, (p and 0xFF) / 127.5f - 1f)
        }
        floats.rewind()
        OnnxTensor.createTensor(env, floats, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val logits = (out.get(0).value as Array<FloatArray>)[0]
                val m = max(logits[0], logits[1])
                val e0 = exp(logits[0] - m)
                val e1 = exp(logits[1] - m)
                return e0 / (e0 + e1)
            }
        }
    }

    override fun close() {
        session.close()
    }
}

package local.chessbridge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

internal class OnnxBoard(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private fun load(context: Context, asset: String) = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open(asset).use { it.readBytes() }, options)
    }
    private val classifier = load(context, "chess-layout.onnx")
    private val locator = lazy { load(context, "chess-corners.onnx") }

    private fun tensor(bitmap: Bitmap, width: Int, height: Int, destination: RectF): OnnxTensor {
        val resized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        try {
            Canvas(resized).drawBitmap(bitmap, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally { resized.recycle() }
        val values = FloatArray(pixels.size * 3)
        for ((i, pixel) in pixels.withIndex()) {
            values[i] = (((pixel shr 16) and 255) - 123.675f) / 58.395f
            values[i + pixels.size] = (((pixel shr 8) and 255) - 116.28f) / 57.12f
            values[i + 2 * pixels.size] = ((pixel and 255) - 103.53f) / 57.375f
        }
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(values),
            longArrayOf(1, 3, height.toLong(), width.toLong()))
    }

    fun locate(bitmap: Bitmap): Corners? {
        // Upstream desktop RTMPose uses a centered square with 25% padding.
        val size = 256
        val scale = size / (1.25f * maxOf(bitmap.width, bitmap.height))
        val left = (size - bitmap.width * scale) / 2
        val top = (size - bitmap.height * scale) / 2
        val destination = RectF(left, top, size - left, size - top)
        val session = locator.value
        tensor(bitmap, size, size, destination).use { input ->
            session.run(mapOf(session.inputNames.single() to input)).use { outputs ->
                val x = coordinates(outputs[0] as OnnxTensor, size * 2, left, scale) ?: return null
                val y = coordinates(outputs[1] as OnnxTensor, size * 2, top, scale) ?: return null
                return rectangularGrid(x, y, bitmap.width, bitmap.height)
            }
        }
    }

    private fun coordinates(output: OnnxTensor, bins: Int, offset: Float, scale: Float): FloatArray? {
        val scores = output.floatBuffer
        if (scores.remaining() != 4 * bins) return null
        val points = FloatArray(4)
        for (i in points.indices) {
            var peak = 0f
            var index = 0
            for (bin in 0 until bins) {
                val score = scores.get()
                if (score > peak) {
                    peak = score
                    index = bin
                }
            }
            if (peak <= 0 || !peak.isFinite()) return null
            points[i] = (index / 2f - offset) / scale
        }
        return points
    }

    private fun rectangularGrid(x: FloatArray, y: FloatArray, width: Int, height: Int): Corners? {
        val left = (x[0] + x[2]) / 2
        val right = (x[1] + x[3]) / 2
        val top = (y[0] + y[1]) / 2
        val bottom = (y[2] + y[3]) / 2
        val stepX = (right - left) / 8
        val stepY = (bottom - top) / 9
        if (stepX < 10 || stepY / stepX !in 0.7f..1.4f) return null
        if (x.any { it < 0 || it >= width - 0.5f } || y.any { it < 0 || it >= height - 0.5f }) return null
        if (maxOf(abs(x[0] - x[2]), abs(x[1] - x[3])) > stepX / 2 ||
            maxOf(abs(y[0] - y[1]), abs(y[2] - y[3])) > stepY / 2) return null
        return Corners(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    }

    fun read(bitmap: Bitmap, layout: Calibration): Board? {
        if (!layout.matchesSize(bitmap)) return null
        // Upstream maps the outer intersections to (50,50)..(400,450)
        // then crops 25 pixels on each side and scales by 0.7.
        val xScale = 245f / (layout.x1 - layout.x0)
        val yScale = 280f / (layout.y1 - layout.y0)
        val left = 17.5f - layout.x0 * xScale
        val top = 17.5f - layout.y0 * yScale
        val destination = RectF(left, top, left + bitmap.width * xScale, top + bitmap.height * yScale)
        val labels = ".xKABNRCPkabnrcp"
        tensor(bitmap, 280, 315, destination).use { tensor ->
            classifier.run(mapOf(classifier.inputNames.single() to tensor)).use { outputs ->
                val scores = (outputs[0] as OnnxTensor).floatBuffer
                if (scores.remaining() != 90 * labels.length) return null
                val cells = CharArray(90)
                for (i in cells.indices) {
                    var best = Float.NEGATIVE_INFINITY
                    var second = Float.NEGATIVE_INFINITY
                    var index = 0
                    for (label in labels.indices) {
                        val score = scores.get()
                        if (!score.isFinite()) return null
                        if (score > best) {
                            second = best
                            best = score
                            index = label
                        } else if (score > second) second = score
                    }
                    if (index == 1 || best - second < 0.35f) return null
                    cells[if (layout.red) i else 89 - i] = labels[index]
                }
                return Board(String(cells), layout.side).takeIf(Board::plausible)
            }
        }
    }

    override fun close() {
        if (locator.isInitialized()) locator.value.close()
        classifier.close()
    }
}

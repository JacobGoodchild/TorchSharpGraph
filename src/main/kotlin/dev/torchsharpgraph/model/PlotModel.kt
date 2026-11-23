package dev.torchsharpgraph.model

import java.util.Base64

enum class PlotKind { XY, LOSS, HEATMAP, IMAGE }

enum class SeriesStyle { SCATTER, LINE }

/** One named set of points. Grows in place when points are appended (loss curves). */
class Series(val name: String, var style: SeriesStyle) {
    var xs = DoubleArray(16); private set
    var ys = DoubleArray(16); private set
    var size = 0; private set

    /** True while x never decreases: lets drawing and hover skip to the visible part quickly. */
    var sortedByX = true; private set

    fun replace(x: DoubleArray, y: DoubleArray) {
        val n = minOf(x.size, y.size)
        xs = x.copyOf(maxOf(n, 16)); ys = y.copyOf(maxOf(n, 16)); size = n
        sortedByX = true
        for (i in 1 until n) if (!(xs[i] >= xs[i - 1])) { if (!xs[i].isNaN() && !xs[i - 1].isNaN()) { sortedByX = false; break } }
    }

    /** Puts points at [start]; points already there and after are replaced. */
    fun putAt(start: Int, x: DoubleArray, y: DoubleArray) {
        val from = start.coerceIn(0, size)
        val n = minOf(x.size, y.size)
        ensure(from + n)
        for (i in 0 until n) {
            val at = from + i
            xs[at] = x[i]; ys[at] = y[i]
            if (at > 0 && xs[at] < xs[at - 1]) sortedByX = false
        }
        size = from + n
    }

    private fun ensure(capacity: Int) {
        if (capacity <= xs.size) return
        var c = xs.size
        while (c < capacity) c = c * 2
        xs = xs.copyOf(c); ys = ys.copyOf(c)
    }
}

class Heatmap(
    val rows: Int, val cols: Int, val values: DoubleArray,
    val x0: Double, val x1: Double, val y0: Double, val y1: Double, val contours: Int,
) {
    val min: Double
    val max: Double

    init {
        var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
        for (v in values) if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
        min = if (lo <= hi) lo else 0.0
        max = if (lo <= hi) hi else 1.0
    }

    fun value(row: Int, col: Int): Double = values[row * cols + col]
}

/** A picture: RGBA bytes with row 0 at the top, plus each pixel's original number for reading on hover. */
class ImageData(val width: Int, val height: Int, val rgba: ByteArray, val raw: DoubleArray, val count: Int)

class PlotData(val name: String, var kind: PlotKind) {
    var title: String? = null
    var xLabel: String? = null
    var yLabel: String? = null
    /** What the program asked for; the user's choice in the window is kept separately. */
    var logY: Boolean? = null
    val series = ArrayList<Series>()
    var heatmap: Heatmap? = null
    var image: ImageData? = null

    fun series(name: String, style: SeriesStyle): Series =
        series.firstOrNull { it.name == name }?.also { it.style = style } ?: Series(name, style).also { series.add(it) }
}

/** What a program sent in one run. */
class RunInfo(val runId: String, val program: String, val pid: Int, val directory: String?, val startedAt: Long) {
    @Volatile var endedAt: Long? = null
    @Volatile var connected = true
}

/** Limits that keep a misbehaving program from using up the IDE's memory. */
object Limits {
    const val MAX_PLOTS = 64
    const val MAX_SERIES_PER_PLOT = 32
    const val MAX_POINTS_PER_SERIES = 5_000_000
    const val MAX_HEATMAP_CELLS = 4_000_000
    const val MAX_IMAGE_PIXELS = 16_000_000
}

/** Decodes base64 image bytes, or null if they don't match the size. */
internal fun decodeRgba(text: String?, width: Int, height: Int): ByteArray? {
    if (text == null || width <= 0 || height <= 0 || width.toLong() * height > Limits.MAX_IMAGE_PIXELS) return null
    val bytes = try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { return null }
    return bytes.takeIf { it.size == width * height * 4 }
}

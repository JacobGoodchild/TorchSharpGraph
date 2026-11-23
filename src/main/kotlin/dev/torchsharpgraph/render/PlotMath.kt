package dev.torchsharpgraph.render

import dev.torchsharpgraph.model.Heatmap
import java.awt.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A closed range of numbers. For a log axis the numbers are already log10 of the data. */
data class Range(val min: Double, val max: Double) {
    val span get() = max - min

    fun union(o: Range?): Range = if (o == null) this else Range(min(min, o.min), max(max, o.max))

    /** Widens an empty or one-value range so it can be drawn. */
    fun nonEmpty(): Range {
        if (span > 1e-300 * max(1.0, abs(min))) return this
        val d = if (min == 0.0) 1.0 else abs(min) * 0.1
        return Range(min - d, max + d)
    }

    fun padded(fraction: Double): Range = Range(min - span * fraction, max + span * fraction)

    /** Zooms by [factor] (below 1 zooms in) keeping [anchor] where it is. */
    fun zoom(anchor: Double, factor: Double): Range = Range(anchor - (anchor - min) * factor, anchor + (max - anchor) * factor)

    fun shift(by: Double) = Range(min + by, max + by)

    companion object {
        fun of(values: DoubleArray, count: Int, log: Boolean = false): Range? {
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (i in 0 until count) {
                var v = values[i]
                if (log) v = if (v > 0) log10(v) else continue
                if (!v.isFinite()) continue
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return if (lo <= hi) Range(lo, hi) else null
        }
    }
}

/** Maps numbers on one axis to pixels. */
class Scale(val range: Range, val pixelFrom: Double, val pixelTo: Double, val log: Boolean = false) {
    /** Pixel for a data value (log10 is applied for log axes). NaN for values a log axis can't show. */
    fun toPixel(value: Double): Double {
        val v = if (log) (if (value > 0) log10(value) else return Double.NaN) else value
        return toPixelTransformed(v)
    }

    fun toPixelTransformed(v: Double): Double {
        val p = pixelFrom + (v - range.min) / range.span * (pixelTo - pixelFrom)
        // Java2D misbehaves with huge coordinates (deep zoom): keep them in a safe band.
        return p.coerceIn(-1e6, 1e6)
    }

    /** The transformed value (log10 for log axes) at a pixel. */
    fun fromPixel(pixel: Double): Double = range.min + (pixel - pixelFrom) / (pixelTo - pixelFrom) * range.span

    fun toData(transformed: Double): Double = if (log) 10.0.pow(transformed) else transformed
}

object Ticks {
    /** Round-number tick positions (steps of 1, 2 or 5 times a power of ten). */
    fun linear(range: Range, maxTicks: Int): Pair<List<Double>, Double> {
        val raw = range.span / max(1, maxTicks)
        if (!(raw > 0) || !raw.isFinite()) return listOf(range.min) to 1.0
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw * 0.999 }
        val first = ceil(range.min / step - 1e-9) * step
        val ticks = ArrayList<Double>()
        var v = first
        var guard = 0
        while (v <= range.max + step * 1e-9 && guard++ < 1000) {
            ticks.add(if (abs(v) < step * 1e-9) 0.0 else v)
            v = first + step * ticks.size
        }
        return ticks to step
    }

    /** Tick positions on a log axis, as log10 values: whole powers of ten, or 1-2-5 steps when the range is short. */
    fun log(range: Range, maxTicks: Int): List<Double> {
        val lo = floor(range.min); val hi = ceil(range.max)
        val decades = (hi - lo).toInt()
        if (decades <= 1) {
            val out = ArrayList<Double>()
            for (d in lo.toInt()..hi.toInt()) for (m in listOf(1.0, 2.0, 5.0)) {
                val t = d + log10(m)
                if (t >= range.min - 1e-9 && t <= range.max + 1e-9) out.add(t)
            }
            if (out.size >= 2) return out
            return linear(range, maxTicks).first
        }
        val every = max(1, ceil(decades.toDouble() / max(1, maxTicks)).toInt())
        val out = ArrayList<Double>()
        var d = lo.toInt()
        while (d <= hi.toInt()) {
            if (d >= range.min - 1e-9 && d <= range.max + 1e-9) out.add(d.toDouble())
            d += every
        }
        return out
    }

    /** A tick label that shows just enough digits for the step between ticks. */
    fun label(value: Double, step: Double): String {
        if (value == 0.0) return "0"
        val a = abs(value)
        if (a >= 1e6 || a < 1e-4) return sci(value)
        val decimals = max(0, -floor(log10(step) + 1e-9).toInt())
        return String.format(Locale.ROOT, "%.${min(decimals, 8)}f", value)
    }

    fun logLabel(exponent: Double): String {
        val v = 10.0.pow(exponent)
        val e = exponent.roundToInt()
        if (abs(exponent - e) < 1e-9 && (e > 4 || e < -3)) return "1e$e"
        return value(v)
    }

    /** A value for reading: about four significant digits. */
    fun value(v: Double): String {
        if (v.isNaN()) return "none"
        if (v.isInfinite()) return if (v > 0) "infinity" else "-infinity"
        if (v == 0.0) return "0"
        val a = abs(v)
        if (a >= 1e6 || a < 1e-4) return sci(v)
        if (v == Math.rint(v) && a < 1e6) return v.toLong().toString()
        val digits = max(0, 3 - floor(log10(a)).toInt())
        return String.format(Locale.ROOT, "%.${min(digits, 8)}f", v).trimEnd('0').trimEnd('.')
    }

    private fun sci(v: Double): String {
        val s = String.format(Locale.ROOT, "%.3e", v)
        val (m, e) = s.split('e')
        val mant = m.trimEnd('0').trimEnd('.')
        return "${mant}e${e.toInt()}"
    }
}

/** The viridis colour map (perceptually even, readable for colour-blind people; CC0, from matplotlib). */
object Viridis {
    private val stops = intArrayOf(
        0x440154, 0x481a6c, 0x472f7d, 0x414487, 0x39568c, 0x31688e, 0x2a788e, 0x23888e,
        0x1f988b, 0x22a884, 0x35b779, 0x54c568, 0x7ad151, 0xa5db36, 0xd2e21b, 0xfde725,
    )

    val table: IntArray = IntArray(256) { i ->
        val t = i / 255.0 * (stops.size - 1)
        val a = floor(t).toInt().coerceIn(0, stops.size - 1)
        val b = min(a + 1, stops.size - 1)
        val f = t - a
        fun ch(c: Int, shift: Int) = (c shr shift) and 0xff
        fun mix(shift: Int) = (ch(stops[a], shift) * (1 - f) + ch(stops[b], shift) * f).roundToInt()
        (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun argb(t: Double): Int = table[(t.coerceIn(0.0, 1.0) * 255).roundToInt()]

    fun color(t: Double) = Color(argb(t), true)
}

/** Contour lines by marching squares, as line segments in data coordinates (x0, y0, x1, y1, ...). */
object Contours {
    fun levels(h: Heatmap): DoubleArray {
        if (h.contours <= 0 || !(h.max > h.min)) return DoubleArray(0)
        return DoubleArray(h.contours) { h.min + (h.max - h.min) * (it + 1) / (h.contours + 1) }
    }

    fun segments(h: Heatmap, level: Double): DoubleArray {
        val out = ArrayList<Double>()
        val cw = (h.x1 - h.x0) / h.cols
        val ch = (h.y1 - h.y0) / h.rows
        // Values sit at cell centres.
        fun cx(c: Double) = h.x0 + (c + 0.5) * cw
        fun cy(r: Double) = h.y0 + (r + 0.5) * ch
        for (r in 0 until h.rows - 1) for (c in 0 until h.cols - 1) {
            val a = h.value(r, c); val b = h.value(r, c + 1); val d = h.value(r + 1, c); val e = h.value(r + 1, c + 1)
            if (!a.isFinite() || !b.isFinite() || !d.isFinite() || !e.isFinite()) continue
            // Corners: a (bottom-left), b (bottom-right), e (top-right), d (top-left).
            val pts = ArrayList<Double>(8)
            fun edge(v1: Double, v2: Double, x1: Double, y1: Double, x2: Double, y2: Double) {
                if ((v1 < level) != (v2 < level)) {
                    val t = (level - v1) / (v2 - v1)
                    pts.add(x1 + (x2 - x1) * t); pts.add(y1 + (y2 - y1) * t)
                }
            }
            val r0 = r.toDouble(); val c0 = c.toDouble()
            edge(a, b, cx(c0), cy(r0), cx(c0 + 1), cy(r0))
            edge(b, e, cx(c0 + 1), cy(r0), cx(c0 + 1), cy(r0 + 1))
            edge(e, d, cx(c0 + 1), cy(r0 + 1), cx(c0), cy(r0 + 1))
            edge(d, a, cx(c0), cy(r0 + 1), cx(c0), cy(r0))
            if (pts.size >= 4) { out.add(pts[0]); out.add(pts[1]); out.add(pts[2]); out.add(pts[3]) }
            if (pts.size == 8) { out.add(pts[4]); out.add(pts[5]); out.add(pts[6]); out.add(pts[7]) }
        }
        return out.toDoubleArray()
    }
}

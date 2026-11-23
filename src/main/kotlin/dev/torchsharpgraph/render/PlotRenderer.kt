package dev.torchsharpgraph.render

import dev.torchsharpgraph.model.Heatmap
import dev.torchsharpgraph.model.ImageData
import dev.torchsharpgraph.model.PlotData
import dev.torchsharpgraph.model.PlotKind
import dev.torchsharpgraph.model.Series
import dev.torchsharpgraph.model.SeriesStyle
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Colours and fonts for drawing; built from the IDE theme in the window, or fixed in tests. */
class PlotTheme(
    val background: Color,
    val foreground: Color,
    val muted: Color,
    val grid: Color,
    val axis: Color,
    val palette: List<Color>,
    val tooltipBackground: Color,
    val tooltipBorder: Color,
    val font: Font,
    val smallFont: Font,
    val dark: Boolean,
) {
    fun seriesColor(i: Int): Color = palette[Math.floorMod(i, palette.size)]

    companion object {
        val LIGHT_PALETTE = listOf(0x1f77b4, 0xe8710a, 0x2ca02c, 0xd62728, 0x9467bd, 0x8c564b, 0xd8559f, 0x6b6b6b, 0xa9a81c, 0x17a3b8).map { Color(it) }
        val DARK_PALETTE = listOf(0x5aa7ff, 0xffa24c, 0x5ccf6a, 0xff6b6b, 0xb894f0, 0xd4a58a, 0xf08fd0, 0xb8b8b8, 0xdada55, 0x45d6e8).map { Color(it) }

        fun light(font: Font = Font(Font.SANS_SERIF, Font.PLAIN, 13)) = PlotTheme(
            Color(0xffffff), Color(0x1e1f22), Color(0x6c707e), Color(0xebecf0), Color(0xa8adbd), LIGHT_PALETTE,
            Color(0xffffff), Color(0xc9ccd6), font, font.deriveFont(font.size2D - 1.5f), false,
        )

        fun dark(font: Font = Font(Font.SANS_SERIF, Font.PLAIN, 13)) = PlotTheme(
            Color(0x1e1f22), Color(0xdfe1e5), Color(0x8c8f96), Color(0x2b2d30), Color(0x55585e), DARK_PALETTE,
            Color(0x2b2d30), Color(0x4e5157), font, font.deriveFont(font.size2D - 1.5f), true,
        )
    }
}

/** What the user did to one plot in the window: zoom, pan, log scale. Null means "automatic". */
class ViewState {
    var x: Range? = null
    var y: Range? = null
    var logY: Boolean? = null
    /** Loss curves: 0 shows the raw values; 0.6 to 0.99 also draws a smoothed curve (as TensorBoard does). */
    var smoothing: Double = 0.0
    val zoomed get() = x != null || y != null
    fun reset() { x = null; y = null }
}

/** Where the last paint put things, for mouse handling. */
class Frame(val plot: Rectangle2D.Double, val xs: Scale, val ys: Scale, val logY: Boolean, val image: Boolean)

/** Text shown next to the mouse, and the point it is about. */
class Hover(val lines: List<String>, val px: Double, val py: Double, val color: Color?)

object PlotRenderer {
    private const val PAD = 10.0

    fun logY(plot: PlotData, view: ViewState): Boolean =
        plot.kind != PlotKind.IMAGE && (view.logY ?: plot.logY ?: false)

    /** The ranges that show all the data (y is log10 when [logY]). */
    fun autoRanges(plot: PlotData, logY: Boolean): Pair<Range, Range> {
        plot.image?.takeIf { plot.kind == PlotKind.IMAGE }?.let { return Range(0.0, it.width.toDouble()) to Range(0.0, it.height.toDouble()) }
        var x: Range? = null
        var y: Range? = null
        for (s in plot.series) {
            x = Range.of(s.xs, s.size)?.union(x) ?: x
            y = Range.of(s.ys, s.size, logY)?.union(y) ?: y
        }
        val h = plot.heatmap
        if (x != null) x = x.nonEmpty().padded(if (h != null) 0.0 else 0.04)
        if (y != null) y = y.nonEmpty().padded(if (h != null) 0.0 else 0.06)
        if (h != null) {
            x = Range(h.x0, h.x1).union(x)
            y = if (logY) y else Range(h.y0, h.y1).union(y)
        }
        // A loss curve reads best with its axis starting at the first step.
        if (plot.kind == PlotKind.LOSS && x != null && plot.series.isNotEmpty()) {
            val first = plot.series.mapNotNull { s -> Range.of(s.xs, s.size)?.min }.minOrNull()
            if (first != null) x = Range(first, max(x.max, first + 1e-9)).nonEmpty()
        }
        return (x ?: Range(0.0, 1.0)) to (y ?: if (logY) Range(-1.0, 1.0) else Range(0.0, 1.0))
    }

    /**
     * Paints [plot] into a [width] x [height] area (logical pixels; the IDE scales them for high-DPI screens).
     * Returns where things went, for mouse handling.
     */
    fun paint(
        g: Graphics2D, width: Int, height: Int, plot: PlotData, view: ViewState, theme: PlotTheme,
        hover: Hover? = null, title: String? = null, hints: Boolean = true,
    ): Frame {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        if (g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING) == null ||
            g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING) == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = theme.background
        g.fillRect(0, 0, width, height)

        val isImage = plot.kind == PlotKind.IMAGE && plot.image != null
        val logY = logY(plot, view)
        val (autoX, autoY) = autoRanges(plot, logY)
        var xr = view.x ?: autoX
        var yr = view.y ?: autoY

        val small = g.getFontMetrics(theme.smallFont)
        val normal = g.getFontMetrics(theme.font)
        val shownTitle = title ?: plot.title
        val top = PAD + (if (shownTitle != null) normal.height + 6.0 else 4.0)
        val heat = plot.heatmap?.takeIf { plot.kind == PlotKind.HEATMAP }
        val right = PAD + (if (heat != null) 18.0 + 8 + small.stringWidth("-0.0000") + 6 else 6.0)
        val bottom = PAD + small.height + 4 + (if (plot.xLabel != null) normal.height + 2.0 else 0.0)

        // Left margin: the widest y tick label (worked out on a first guess of the plot height).
        val guessH = max(20.0, height - top - bottom)
        val yTickGuess = yTicks(yr, logY, guessH, small.height)
        val yLabelW = yTickGuess.maxOfOrNull { small.stringWidth(it.second) } ?: 0
        val left = PAD + yLabelW + 6 + (if (plot.yLabel != null) normal.height + 4.0 else 0.0)
        val rect = Rectangle2D.Double(left, top, max(10.0, width - left - right), max(10.0, height - top - bottom))

        if (isImage) {
            // Square pixels: widen whichever range has room to spare.
            val img = plot.image!!
            val sx = rect.width / xr.span; val sy = rect.height / yr.span
            if (view.x == null && view.y == null) {
                if (sx > sy) xr = Range(img.width / 2.0 - rect.width / sy / 2, img.width / 2.0 + rect.width / sy / 2)
                else yr = Range(img.height / 2.0 - rect.height / sx / 2, img.height / 2.0 + rect.height / sx / 2)
            }
        }
        val xs = Scale(xr, rect.x, rect.x + rect.width)
        // Images have row 0 at the top; everything else has y going up.
        val ys = if (isImage) Scale(yr, rect.y, rect.y + rect.height) else Scale(yr, rect.y + rect.height, rect.y, logY)
        val frame = Frame(rect, xs, ys, logY, isImage)

        // Grid and tick labels.
        val (xTickValues, xStep) = Ticks.linear(xr, max(3, (rect.width / max(55.0, small.stringWidth("-0.00") + 18.0)).toInt()))
        val yTickList = yTicks(yr, logY, rect.height, small.height)
        g.stroke = BasicStroke(1f)
        if (!isImage) {
            g.color = theme.grid
            for (t in xTickValues) { val px = xs.toPixelTransformed(t); g.draw(Line2D.Double(px, rect.y, px, rect.maxY)) }
            for ((t, _) in yTickList) { val py = ys.toPixelTransformed(t); g.draw(Line2D.Double(rect.x, py, rect.maxX, py)) }
        }

        val oldClip = g.clip
        g.clip(rect)
        if (heat != null) drawHeatmap(g, heat, xs, ys, theme)
        if (isImage) drawImage(g, plot.image!!, xs, ys)
        val smooth = view.smoothing.takeIf { plot.kind == PlotKind.LOSS && it > 0.0 }
        plot.series.forEachIndexed { i, s ->
            val color = seriesColor(plot, theme, i)
            if (smooth != null && s.style == SeriesStyle.LINE) {
                // The raw curve faint behind, the smoothed one on top.
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                drawSeries(g, s, xs, ys, color, rect)
                g.composite = old
                drawSeries(g, smoothed(s, smooth), xs, ys, color, rect)
            } else drawSeries(g, s, xs, ys, color, rect)
        }
        g.clip = oldClip

        // Axes.
        g.color = theme.axis
        g.draw(Line2D.Double(rect.x, rect.maxY, rect.maxX, rect.maxY))
        g.draw(Line2D.Double(rect.x, rect.y, rect.x, rect.maxY))
        g.font = theme.smallFont
        g.color = theme.muted
        for (t in xTickValues) {
            val px = xs.toPixelTransformed(t)
            if (px < rect.x - 1 || px > rect.maxX + 1) continue
            val label = if (isImage) Ticks.value(t) else Ticks.label(t, xStep)
            val w = small.stringWidth(label)
            g.draw(Line2D.Double(px, rect.maxY, px, rect.maxY + 3))
            g.drawString(label, (px - w / 2.0).toFloat().coerceIn(0f, (width - w).toFloat()), (rect.maxY + 4 + small.ascent).toFloat())
        }
        for ((t, label) in yTickList) {
            val py = ys.toPixelTransformed(t)
            if (py < rect.y - 1 || py > rect.maxY + 1) continue
            g.draw(Line2D.Double(rect.x - 3, py, rect.x, py))
            g.drawString(label, (rect.x - 6 - small.stringWidth(label)).toFloat(), (py + small.ascent / 2.0 - 1).toFloat())
        }

        // Labels and title.
        g.font = theme.font
        g.color = theme.foreground
        plot.xLabel?.let {
            val w = normal.stringWidth(it)
            g.drawString(it, (rect.centerX - w / 2).toFloat(), (rect.maxY + 4 + small.height + 2 + normal.ascent).toFloat())
        }
        plot.yLabel?.let {
            val text = if (logY) "$it (log)" else it
            val w = normal.stringWidth(text)
            val old = g.transform
            g.rotate(-Math.PI / 2)
            g.drawString(text, (-rect.centerY - w / 2).toFloat(), (PAD + normal.ascent).toFloat())
            g.transform = old
        }
        if (plot.yLabel == null && logY) {
            g.font = theme.smallFont; g.color = theme.muted
            g.drawString("log", (rect.x + 4).toFloat(), (rect.y + small.ascent + 2).toFloat())
        }
        shownTitle?.let {
            g.font = theme.font.deriveFont(Font.BOLD)
            g.color = theme.foreground
            val fm = g.fontMetrics
            val w = fm.stringWidth(it)
            g.drawString(it, (rect.centerX - w / 2).toFloat().coerceAtLeast(PAD.toFloat()), (PAD + fm.ascent).toFloat())
        }

        if (heat != null) drawColorBar(g, heat, rect, theme)
        if (plot.series.size > 1 || (heat != null && plot.series.isNotEmpty())) drawLegend(g, plot, rect, theme, xs, ys)

        if (plot.series.all { it.size == 0 } && plot.heatmap == null && plot.image == null) {
            g.font = theme.font; g.color = theme.muted
            val text = "No data yet"
            g.drawString(text, (rect.centerX - normal.stringWidth(text) / 2).toFloat(), rect.centerY.toFloat())
        }
        if (view.zoomed && hints) {
            g.font = theme.smallFont; g.color = theme.muted
            val text = "Double-click to reset zoom"
            g.drawString(text, (rect.maxX - small.stringWidth(text) - 4).toFloat(), (rect.maxY - small.descent - 3).toFloat())
        }
        hover?.let { drawHover(g, it, theme, width, height) }
        return frame
    }

    private fun yTicks(yr: Range, logY: Boolean, heightPx: Double, fontH: Int): List<Pair<Double, String>> {
        val count = max(3, (heightPx / max(40.0, fontH * 2.5)).toInt())
        if (logY) return Ticks.log(yr, count).map { it to Ticks.logLabel(it) }
        val (values, step) = Ticks.linear(yr, count)
        return values.map { it to Ticks.label(it, step) }
    }

    // ---- Layers ---------------------------------------------------------------------------------------------

    private val heatmapImages: MutableMap<Heatmap, BufferedImage> = Collections.synchronizedMap(WeakHashMap())
    private val contourCache: MutableMap<Heatmap, List<DoubleArray>> = Collections.synchronizedMap(WeakHashMap())
    private val pictureImages: MutableMap<ImageData, BufferedImage> = Collections.synchronizedMap(WeakHashMap())

    private fun heatmapImage(h: Heatmap): BufferedImage = heatmapImages.getOrPut(h) {
        val img = BufferedImage(h.cols, h.rows, BufferedImage.TYPE_INT_ARGB)
        val span = h.max - h.min
        for (r in 0 until h.rows) for (c in 0 until h.cols) {
            val v = h.value(r, c)
            val argb = if (!v.isFinite()) 0 else Viridis.argb(if (span > 0) (v - h.min) / span else 0.5)
            img.setRGB(c, h.rows - 1 - r, argb) // row 0 is at the bottom
        }
        img
    }

    private fun drawHeatmap(g: Graphics2D, h: Heatmap, xs: Scale, ys: Scale, theme: PlotTheme) {
        val img = heatmapImage(h)
        val x0 = xs.toPixelTransformed(h.x0); val x1 = xs.toPixelTransformed(h.x1)
        val yTop = ys.toPixelTransformed(h.y1); val yBottom = ys.toPixelTransformed(h.y0)
        val old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val tx = java.awt.geom.AffineTransform(
            (x1 - x0) / h.cols, 0.0, 0.0, (yBottom - yTop) / h.rows, x0, yTop,
        )
        g.drawImage(img, tx, null)
        if (old != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old)
        val levels = contourCache.getOrPut(h) { Contours.levels(h).map { Contours.segments(h, it) } }
        if (levels.isEmpty()) return
        g.color = Color(255, 255, 255, if (theme.dark) 150 else 170)
        g.stroke = BasicStroke(1f)
        val line = Line2D.Double()
        for (seg in levels) {
            var i = 0
            while (i + 3 < seg.size) {
                line.setLine(xs.toPixelTransformed(seg[i]), ys.toPixelTransformed(seg[i + 1]),
                    xs.toPixelTransformed(seg[i + 2]), ys.toPixelTransformed(seg[i + 3]))
                g.draw(line)
                i += 4
            }
        }
    }

    private fun picture(img: ImageData): BufferedImage = pictureImages.getOrPut(img) {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_ARGB)
        val px = IntArray(img.width * img.height)
        for (i in px.indices) {
            val o = i * 4
            px[i] = ((img.rgba[o + 3].toInt() and 0xff) shl 24) or ((img.rgba[o].toInt() and 0xff) shl 16) or
                ((img.rgba[o + 1].toInt() and 0xff) shl 8) or (img.rgba[o + 2].toInt() and 0xff)
        }
        out.setRGB(0, 0, img.width, img.height, px, 0, img.width)
        out
    }

    private fun drawImage(g: Graphics2D, img: ImageData, xs: Scale, ys: Scale) {
        val x0 = xs.toPixelTransformed(0.0); val x1 = xs.toPixelTransformed(img.width.toDouble())
        val y0 = ys.toPixelTransformed(0.0); val y1 = ys.toPixelTransformed(img.height.toDouble())
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val tx = java.awt.geom.AffineTransform((x1 - x0) / img.width, 0.0, 0.0, (y1 - y0) / img.height, x0, y0)
        g.drawImage(picture(img), tx, null)
    }

    private fun drawSeries(g: Graphics2D, s: Series, xs: Scale, ys: Scale, color: Color, rect: Rectangle2D.Double) {
        if (s.size == 0) return
        val (from, to) = visibleIndices(s, xs)
        g.color = color
        if (s.style == SeriesStyle.SCATTER) {
            if (to - from > 20_000) { drawManyPoints(g, s, from, to, xs, ys, color, rect); return }
            val r = 3.5
            val dot = Ellipse2D.Double()
            val old = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f)
            for (i in from until to) {
                val px = xs.toPixel(s.xs[i]); val py = ys.toPixel(s.ys[i])
                if (!px.isFinite() || !py.isFinite()) continue
                if (px < rect.x - r || px > rect.maxX + r || py < rect.y - r || py > rect.maxY + r) continue
                dot.setFrame(px - r, py - r, 2 * r, 2 * r); g.fill(dot)
            }
            g.composite = old
            return
        }
        g.stroke = BasicStroke(1.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val path = Path2D.Double()
        val columns = rect.width
        if (s.sortedByX && to - from > columns * 4) {
            // Many more points than pixels: per pixel column keep the first, lowest, highest and last point.
            var col = Long.MIN_VALUE
            var firstY = 0.0; var minY = 0.0; var maxY = 0.0; var lastY = 0.0; var colX = 0.0
            var started = false
            fun flush() {
                if (col == Long.MIN_VALUE) return
                if (!started) { path.moveTo(colX, firstY); started = true } else path.lineTo(colX, firstY)
                path.lineTo(colX, minY); path.lineTo(colX, maxY); path.lineTo(colX, lastY)
            }
            for (i in from until to) {
                val px = xs.toPixel(s.xs[i]); val py = ys.toPixel(s.ys[i])
                if (!px.isFinite() || !py.isFinite()) { flush(); col = Long.MIN_VALUE; started = false; continue }
                val c = floor(px).toLong()
                if (c != col) {
                    flush()
                    col = c; colX = px; firstY = py; minY = py; maxY = py; lastY = py
                } else {
                    minY = min(minY, py); maxY = max(maxY, py); lastY = py
                }
            }
            flush()
        } else {
            var penDown = false
            for (i in from until to) {
                val px = xs.toPixel(s.xs[i]); val py = ys.toPixel(s.ys[i])
                if (!px.isFinite() || !py.isFinite()) { penDown = false; continue }
                if (penDown) path.lineTo(px, py) else { path.moveTo(px, py); penDown = true }
            }
        }
        g.draw(path)
    }

    /**
     * Very many points: written straight into a pixel buffer at the screen's real resolution (so it stays sharp on
     * scaled screens), then drawn in one go. Each point is a small square about 1.5 logical pixels across.
     */
    private fun drawManyPoints(g: Graphics2D, s: Series, from: Int, to: Int, xs: Scale, ys: Scale, color: Color, rect: Rectangle2D.Double) {
        val sx = g.transform.scaleX.coerceAtLeast(1.0); val sy = g.transform.scaleY.coerceAtLeast(1.0)
        val w = kotlin.math.ceil(rect.width * sx).toInt().coerceIn(1, 16000)
        val h = kotlin.math.ceil(rect.height * sy).toInt().coerceIn(1, 16000)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val data = (img.raster.dataBuffer as java.awt.image.DataBufferInt).data
        val argb = (0xd9 shl 24) or (color.rgb and 0xffffff)
        val size = maxOf(1, (1.5 * sx).toInt())
        for (i in from until to) {
            val px = xs.toPixel(s.xs[i]); val py = ys.toPixel(s.ys[i])
            if (!px.isFinite() || !py.isFinite()) continue
            val ix = ((px - rect.x) * sx).toInt() - size / 2
            val iy = ((py - rect.y) * sy).toInt() - size / 2
            if (ix < -size || iy < -size || ix >= w || iy >= h) continue
            for (dy in 0 until size) {
                val y = iy + dy
                if (y < 0 || y >= h) continue
                val row = y * w
                for (dx in 0 until size) { val x = ix + dx; if (x in 0 until w) data[row + x] = argb }
            }
        }
        g.drawImage(img, java.awt.geom.AffineTransform(1 / sx, 0.0, 0.0, 1 / sy, rect.x, rect.y), null)
    }

    private val smoothCache: MutableMap<Series, Triple<Int, Double, Series>> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Exponential moving average with TensorBoard's start-up correction (so the curve doesn't start at zero):
     * s_t = w * s_(t-1) + (1 - w) * y_t, shown as s_t / (1 - w^t).
     */
    fun smoothed(s: Series, weight: Double): Series {
        smoothCache[s]?.let { (n, w, out) -> if (n == s.size && w == weight) return out }
        val ys = DoubleArray(s.size)
        var last = 0.0
        var count = 0
        for (i in 0 until s.size) {
            val y = s.ys[i]
            if (!y.isFinite()) { ys[i] = Double.NaN; continue }
            last = weight * last + (1 - weight) * y
            count++
            ys[i] = last / (1 - Math.pow(weight, count.toDouble()))
        }
        val out = Series(s.name, s.style)
        out.replace(s.xs.copyOf(s.size), ys)
        smoothCache[s] = Triple(s.size, weight, out)
        return out
    }

    /** The index range worth drawing: for sorted series only the visible part (plus one point each side). */
    private fun visibleIndices(s: Series, xs: Scale): Pair<Int, Int> {
        if (!s.sortedByX || s.size < 1000) return 0 to s.size
        val lo = lowerBound(s, xs.range.min)
        val hi = lowerBound(s, xs.range.max)
        return max(0, lo - 1) to min(s.size, hi + 1)
    }

    private fun lowerBound(s: Series, x: Double): Int {
        var a = 0; var b = s.size
        while (a < b) { val m = (a + b) ushr 1; if (s.xs[m] < x) a = m + 1 else b = m }
        return a
    }

    private fun drawColorBar(g: Graphics2D, h: Heatmap, rect: Rectangle2D.Double, theme: PlotTheme) {
        val x = rect.maxX + 10
        val bar = Rectangle2D.Double(x, rect.y, 14.0, rect.height)
        val steps = max(2, rect.height.toInt())
        for (i in 0 until steps) {
            g.color = Viridis.color(1.0 - i.toDouble() / (steps - 1))
            g.fill(Rectangle2D.Double(bar.x, bar.y + bar.height * i / steps, bar.width, bar.height / steps + 1))
        }
        g.color = theme.axis
        g.draw(bar)
        val range = Range(h.min, h.max).nonEmpty()
        val (ticks, step) = Ticks.linear(range, max(2, (rect.height / 40).toInt()))
        g.font = theme.smallFont
        val fm = g.fontMetrics
        g.color = theme.muted
        for (t in ticks) {
            val py = bar.maxY - (t - range.min) / range.span * bar.height
            if (py < bar.y - 1 || py > bar.maxY + 1) continue
            g.drawString(Ticks.label(t, step), (bar.maxX + 4).toFloat(), (py + fm.ascent / 2.0 - 1).toFloat())
        }
    }

    private fun drawLegend(g: Graphics2D, plot: PlotData, rect: Rectangle2D.Double, theme: PlotTheme, xs: Scale, ys: Scale) {
        g.font = theme.smallFont
        val fm = g.fontMetrics
        val rowH = fm.height + 2.0
        val w = (plot.series.maxOfOrNull { fm.stringWidth(it.name) } ?: 0) + 34.0
        val h = rowH * plot.series.size + 8
        // Too small to hold a legend without hiding the data: leave it out.
        if (h > rect.height * 0.6 || w > rect.width * 0.6) return
        // The corner that hides the fewest points.
        val corners = listOf(
            rect.maxX - w - 8 to rect.y + 8, rect.x + 8 to rect.y + 8,
            rect.maxX - w - 8 to rect.maxY - h - 8, rect.x + 8 to rect.maxY - h - 8,
        )
        val (bx, by) = corners.minByOrNull { (cx, cy) -> pointsInside(plot, xs, ys, Rectangle2D.Double(cx, cy, w, h)) } ?: corners[0]
        val box = RoundRectangle2D.Double(bx, by, w, h, 8.0, 8.0)
        g.color = Color(theme.tooltipBackground.red, theme.tooltipBackground.green, theme.tooltipBackground.blue, 225)
        g.fill(box)
        g.color = theme.tooltipBorder
        g.draw(box)
        plot.series.forEachIndexed { i, s ->
            val cy = box.y + 4 + rowH * i + rowH / 2
            g.color = seriesColor(plot, theme, i)
            if (s.style == SeriesStyle.SCATTER) g.fill(Ellipse2D.Double(box.x + 12 - 3.5, cy - 3.5, 7.0, 7.0))
            else { g.stroke = BasicStroke(2f); g.draw(Line2D.Double(box.x + 6, cy, box.x + 20, cy)) }
            g.color = theme.foreground
            g.drawString(s.name, (box.x + 26).toFloat(), (cy + fm.ascent / 2.0 - 1).toFloat())
        }
    }

    private fun pointsInside(plot: PlotData, xs: Scale, ys: Scale, r: Rectangle2D.Double): Int {
        var n = 0
        for (s in plot.series) {
            val step = max(1, s.size / 2000)
            var i = 0
            while (i < s.size) {
                if (r.contains(xs.toPixel(s.xs[i]), ys.toPixel(s.ys[i]))) n += step
                i += step
            }
        }
        return n
    }

    private fun drawHover(g: Graphics2D, h: Hover, theme: PlotTheme, width: Int, height: Int) {
        if (h.color != null) {
            g.color = theme.background
            g.stroke = BasicStroke(3f)
            g.draw(Ellipse2D.Double(h.px - 5, h.py - 5, 10.0, 10.0))
            g.color = h.color
            g.stroke = BasicStroke(2f)
            g.draw(Ellipse2D.Double(h.px - 5, h.py - 5, 10.0, 10.0))
        }
        g.font = theme.smallFont
        val fm = g.fontMetrics
        val w = (h.lines.maxOfOrNull { fm.stringWidth(it) } ?: 0) + 16.0
        val ht = fm.height * h.lines.size + 10.0
        var x = h.px + 12; var y = h.py - ht - 8
        if (x + w > width - 2) x = h.px - w - 12
        if (y < 2) y = h.py + 12
        if (y + ht > height - 2) y = height - ht - 2
        x = x.coerceAtLeast(2.0)
        val box = RoundRectangle2D.Double(x, y, w, ht, 8.0, 8.0)
        g.color = theme.tooltipBackground
        g.fill(box)
        g.color = theme.tooltipBorder
        g.stroke = BasicStroke(1f)
        g.draw(box)
        g.color = theme.foreground
        h.lines.forEachIndexed { i, line -> g.drawString(line, (x + 8).toFloat(), (y + 5 + fm.ascent + fm.height * i).toFloat()) }
    }

    // ---- Reading values under the mouse ----------------------------------------------------------------------

    /** What is under the mouse at ([mx], [my]), or null. */
    fun hover(frame: Frame, plot: PlotData, mx: Double, my: Double, theme: PlotTheme): Hover? {
        if (!frame.plot.contains(mx, my)) return null
        var best: Hover? = null
        var bestD = 14.0
        val multi = plot.series.size > 1
        plot.series.forEachIndexed { si, s ->
            val (from, to) = nearIndices(s, frame, mx)
            for (i in from until to) {
                val px = frame.xs.toPixel(s.xs[i]); val py = frame.ys.toPixel(s.ys[i])
                if (!px.isFinite() || !py.isFinite()) continue
                val d = hypot(px - mx, py - my)
                if (d < bestD) {
                    bestD = d
                    val lines = ArrayList<String>()
                    if (multi || plot.heatmap != null) lines.add(s.name)
                    if (plot.kind == PlotKind.LOSS) {
                        lines.add("${plot.xLabel ?: "step"} ${Ticks.value(s.xs[i])}")
                        lines.add("${plot.yLabel ?: "loss"} = ${Ticks.value(s.ys[i])}")
                    } else {
                        lines.add("${plot.xLabel ?: "x"} = ${Ticks.value(s.xs[i])}")
                        lines.add("${plot.yLabel ?: "y"} = ${Ticks.value(s.ys[i])}")
                    }
                    best = Hover(lines, px, py, seriesColor(plot, theme, si))
                }
            }
        }
        best?.let { return it }
        val x = frame.xs.fromPixel(mx)
        val yT = frame.ys.fromPixel(my)
        plot.image?.takeIf { frame.image }?.let { img ->
            val col = floor(x).toInt(); val row = floor(yT).toInt()
            if (col !in 0 until img.width || row !in 0 until img.height) return null
            val raw = img.raw[row * img.width + col]
            if (raw.isNaN()) return null
            val o = (row * img.width + col) * 4
            val rgb = "${img.rgba[o].toInt() and 0xff}, ${img.rgba[o + 1].toInt() and 0xff}, ${img.rgba[o + 2].toInt() and 0xff}"
            return Hover(listOf("pixel ($col, $row)", "value = ${Ticks.value(raw)}", "colour = $rgb"), mx, my, null)
        }
        plot.heatmap?.let { h ->
            if (frame.logY) return null
            val col = floor((x - h.x0) / (h.x1 - h.x0) * h.cols).toInt()
            val row = floor((yT - h.y0) / (h.y1 - h.y0) * h.rows).toInt()
            if (col !in 0 until h.cols || row !in 0 until h.rows) return null
            return Hover(listOf(
                "${plot.xLabel ?: "x"} = ${Ticks.value(x)}", "${plot.yLabel ?: "y"} = ${Ticks.value(yT)}",
                "value = ${Ticks.value(h.value(row, col))}",
            ), mx, my, null)
        }
        return null
    }

    /** For sorted series, only the points within a few pixels of the mouse's x. */
    private fun nearIndices(s: Series, frame: Frame, mx: Double): Pair<Int, Int> {
        if (!s.sortedByX || s.size < 2000) return 0 to s.size
        val lo = lowerBound(s, frame.xs.fromPixel(mx - 16))
        val hi = lowerBound(s, frame.xs.fromPixel(mx + 16))
        val mid = (lo + hi) / 2
        // Very dense lines: look at a bounded window around the mouse.
        return if (hi - lo > 5000) max(0, mid - 2500) to min(s.size, mid + 2500) else max(0, lo - 1) to min(s.size, hi + 1)
    }

    /** Zooms [view] by [factor] around the pixel ([mx], [my]). */
    fun zoom(frame: Frame, view: ViewState, mx: Double, my: Double, factor: Double, xOnly: Boolean = false, yOnly: Boolean = false) {
        if (!yOnly) view.x = frame.xs.range.zoom(frame.xs.fromPixel(mx), factor)
        else view.x = frame.xs.range
        if (!xOnly) view.y = frame.ys.range.zoom(frame.ys.fromPixel(my), factor)
        else view.y = frame.ys.range
        clamp(view)
    }

    /** Moves [view] by a mouse drag of ([dx], [dy]) pixels. */
    fun pan(frame: Frame, view: ViewState, dx: Double, dy: Double) {
        view.x = frame.xs.range.shift(frame.xs.fromPixel(frame.plot.x) - frame.xs.fromPixel(frame.plot.x + dx))
        view.y = frame.ys.range.shift(frame.ys.fromPixel(frame.plot.y) - frame.ys.fromPixel(frame.plot.y + dy))
        clamp(view)
    }

    private fun clamp(view: ViewState) {
        fun ok(r: Range?) = r != null && r.min.isFinite() && r.max.isFinite() && r.span > 1e-12 * max(1.0, abs(r.min)) && r.span < 1e300
        if (!ok(view.x) || !ok(view.y)) view.reset()
    }

    /** Colours that stand out on the heatmap's purple-to-yellow are used for points and lines drawn over it. */
    private val OVER_HEATMAP = listOf(Color(0xff4d4d), Color(0xffffff), Color(0xff9f1c), Color(0x3ad6ff))

    fun seriesColor(plot: PlotData, theme: PlotTheme, i: Int): Color =
        if (plot.heatmap != null && plot.kind == PlotKind.HEATMAP) OVER_HEATMAP[Math.floorMod(i, OVER_HEATMAP.size)] else theme.seriesColor(i)
}

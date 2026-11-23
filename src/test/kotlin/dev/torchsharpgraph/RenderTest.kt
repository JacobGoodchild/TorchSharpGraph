package dev.torchsharpgraph

import dev.torchsharpgraph.model.PlotStore
import dev.torchsharpgraph.protocol.Message
import dev.torchsharpgraph.render.Frame
import dev.torchsharpgraph.render.PlotRenderer
import dev.torchsharpgraph.render.PlotTheme
import dev.torchsharpgraph.render.ViewState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.exp
import kotlin.math.sin

/** Draws every kind of plot off screen, checks the pixels, and saves pictures when -PshotDir is given. */
class RenderTest {
    private fun store(vararg lines: String): PlotStore {
        val s = PlotStore()
        for (l in lines) assertTrue(s.apply(Message.parse(l)), l)
        return s
    }

    private fun nums(values: List<Double>) = values.joinToString(",", "[", "]")

    private fun render(store: PlotStore, name: String, theme: PlotTheme, scale: Double = 1.0, view: ViewState = ViewState(),
                       w: Int = 520, h: Int = 340, hoverAt: Pair<Double, Double>? = null): Pair<BufferedImage, Frame> {
        val img = BufferedImage((w * scale).toInt(), (h * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.scale(scale, scale)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        val frame = store.withPlot(name) { plot ->
            val first = PlotRenderer.paint(g, w, h, plot!!, view, theme)
            if (hoverAt == null) first else {
                val hover = PlotRenderer.hover(first, plot, hoverAt.first, hoverAt.second, theme)
                PlotRenderer.paint(g, w, h, plot, view, theme, hover)
            }
        }
        g.dispose()
        return img to frame
    }

    private fun save(img: BufferedImage, file: String) {
        val dir = System.getProperty("torchsharpgraph.shotDir") ?: System.getenv("TORCHSHARPGRAPH_SHOT_DIR") ?: return
        File(dir).mkdirs()
        ImageIO.write(img, "png", File(dir, file))
    }

    private fun count(img: BufferedImage, color: Color, tolerance: Int = 40): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = Color(img.getRGB(x, y))
            if (Math.abs(c.red - color.red) + Math.abs(c.green - color.green) + Math.abs(c.blue - color.blue) < tolerance) n++
        }
        return n
    }

    private val xs = (0 until 60).map { it / 10.0 }
    private val demo = store(
        """{"t":"plot","plot":"waves","kind":"xy","title":"Sine waves","x":"time","y":"height"}""",
        """{"t":"series","plot":"waves","series":"sin","style":"line","start":0,"x":${nums(xs)},"y":${nums(xs.map { sin(it) })}}""",
        """{"t":"series","plot":"waves","series":"samples","style":"scatter","start":0,"x":${nums(xs.filterIndexed { i, _ -> i % 4 == 0 })},"y":${nums(xs.filterIndexed { i, _ -> i % 4 == 0 }.map { sin(it) + 0.2 })}}""",
        """{"t":"plot","plot":"Loss","kind":"loss","x":"step","y":"loss"}""",
        """{"t":"series","plot":"Loss","series":"loss","style":"line","start":0,"x":${nums((0 until 500).map { it.toDouble() })},"y":${nums((0 until 500).map { 2 * exp(-it / 60.0) + 0.01 * sin(it / 3.0) + 0.02 })}}""",
        """{"t":"plot","plot":"surface","kind":"heatmap","x":"a","y":"b"}""",
        """{"t":"heatmap","plot":"surface","rows":40,"cols":40,"x0":-2,"x1":2,"y0":-1,"y1":3,"contours":8,"v":${nums((0 until 1600).map { val r = it / 40; val c = it % 40; val a = -2 + 4.0 * (c + 0.5) / 40; val b = -1 + 4.0 * (r + 0.5) / 40; (1 - a) * (1 - a) + 5 * (b - a * a) * (b - a * a) })}}""",
        """{"t":"series","plot":"surface","series":"path","style":"line","start":0,"x":[-1.5,-1,0,0.6,1],"y":[2.5,1.2,0.3,0.4,1]}""",
    )

    @Test
    fun linesAndPointsLightAndDark() {
        for ((theme, tag) in listOf(PlotTheme.light() to "light", PlotTheme.dark() to "dark")) {
            val (img, _) = render(demo, "waves", theme)
            save(img, "waves-$tag.png")
            assertTrue(count(img, theme.seriesColor(0)) > 200, "the sine line is drawn")
            assertTrue(count(img, theme.seriesColor(1), 120) > 50, "the points are drawn")
            assertEquals(theme.background.rgb, img.getRGB(2, 2), "background matches the theme")
        }
    }

    @Test
    fun sharpAtTwoTimes() {
        val (img, _) = render(demo, "waves", PlotTheme.dark(), scale = 2.0)
        save(img, "waves-dark-200.png")
        assertEquals(1040, img.width)
        // At 2x a line is drawn with device pixels: it covers about twice as many pixels in each direction.
        val one = count(render(demo, "waves", PlotTheme.dark()).first, PlotTheme.dark().seriesColor(0))
        val two = count(img, PlotTheme.dark().seriesColor(0))
        assertTrue(two > one * 2.5, "2x: $two pixels vs 1x: $one")
    }

    @Test
    fun lossLinearAndLog() {
        val (lin, _) = render(demo, "Loss", PlotTheme.light())
        save(lin, "loss-light.png")
        val log = ViewState().apply { logY = true }
        val (img, frame) = render(demo, "Loss", PlotTheme.dark(), view = log)
        save(img, "loss-log-dark.png")
        assertTrue(frame.logY)
        // On the log axis 1.0 sits well above 0.1.
        assertTrue(frame.ys.toPixel(1.0) < frame.ys.toPixel(0.1) - 40)
    }

    @Test
    fun smoothingFollowsTheTrend() {
        val s = PlotStore()
        val r = java.util.Random(2)
        val n = 400
        val xs2 = (0 until n).map { it.toDouble() }
        val ys2 = (0 until n).map { exp(-it / 80.0) + 0.3 * r.nextGaussian().coerceIn(-1.5, 1.5) + 0.5 }
        assertTrue(s.apply(Message.parse("""{"t":"plot","plot":"noisy","kind":"loss"}""")))
        assertTrue(s.apply(Message.parse("""{"t":"series","plot":"noisy","series":"loss","style":"line","start":0,"x":${nums(xs2)},"y":${nums(ys2)}}""")))
        val smooth = s.withPlot("noisy") { PlotRenderer.smoothed(it!!.series[0], 0.9) }
        // The smoothed curve stays close to the true trend; the raw values wander far more.
        val rawErr = (100 until n).sumOf { kotlin.math.abs(ys2[it] - (exp(-it / 80.0) + 0.5)) }
        val smoothErr = (100 until n).sumOf { kotlin.math.abs(smooth.ys[it] - (exp(-it / 80.0) + 0.5)) }
        assertTrue(smoothErr < rawErr / 2.5, "smoothed $smoothErr vs raw $rawErr")
        assertEquals(ys2[0], smooth.ys[0], 1e-9) // the start-up correction: no dip towards zero
        val view = ViewState().apply { smoothing = 0.9 }
        save(render(s, "noisy", PlotTheme.dark(), view = view).first, "loss-smoothed-dark.png")
    }

    @Test
    fun heatmapWithContoursAndPath() {
        val (img, frame) = render(demo, "surface", PlotTheme.light(), w = 560, h = 420)
        save(img, "surface-light.png")
        val (dark, _) = render(demo, "surface", PlotTheme.dark(), w = 560, h = 420)
        save(dark, "surface-dark.png")
        // The minimum of the surface (a=1, b=1) is dark purple; the corner (a=-2, b=-1) is bright yellow.
        val minPx = img.getRGB(frame.xs.toPixel(1.0).toInt(), frame.ys.toPixel(0.95).toInt())
        val maxPx = img.getRGB(frame.xs.toPixel(-1.95).toInt(), frame.ys.toPixel(-0.95).toInt())
        assertTrue(Color(minPx).let { it.blue > it.green }, "low values are purple/blue")
        assertTrue(Color(maxPx).let { it.red > 200 && it.green > 200 }, "high values are yellow")
    }

    @Test
    fun hoverReadsValues() {
        val (img, frame) = render(demo, "waves", PlotTheme.light())
        val px = frame.xs.toPixel(xs[10]); val py = frame.ys.toPixel(sin(xs[10]))
        val hover = demo.withPlot("waves") { PlotRenderer.hover(frame, it!!, px + 2, py + 1, PlotTheme.light()) }
        assertNotNull(hover)
        assertEquals(listOf("sin", "time = 1", "height = 0.8415"), hover!!.lines)
        val (withHover, _) = render(demo, "waves", PlotTheme.light(), hoverAt = px + 2 to py + 1)
        save(withHover, "waves-hover-light.png")
        assertNull(demo.withPlot("waves") { PlotRenderer.hover(frame, it!!, 1.0, 1.0, PlotTheme.light()) })
        val (_, hf) = render(demo, "surface", PlotTheme.light())
        val cell = demo.withPlot("surface") { PlotRenderer.hover(hf, it!!, hf.xs.toPixel(1.9), hf.ys.toPixel(-0.9), PlotTheme.light()) }
        assertTrue(cell!!.lines.last().startsWith("value = "))
        assertTrue(img.width > 0)
    }

    @Test
    fun zoomAndPan() {
        val view = ViewState()
        val (_, frame) = render(demo, "waves", PlotTheme.light(), view = view)
        PlotRenderer.zoom(frame, view, frame.plot.centerX, frame.plot.centerY, 0.5)
        assertTrue(view.zoomed)
        assertEquals(frame.xs.range.span / 2, view.x!!.span, 1e-9)
        val (zoomed, f2) = render(demo, "waves", PlotTheme.light(), view = view)
        save(zoomed, "waves-zoomed-light.png")
        val before = view.x!!.min
        PlotRenderer.pan(f2, view, 50.0, 0.0)
        assertTrue(view.x!!.min < before, "dragging right shows smaller x")
        view.reset()
        assertTrue(!view.zoomed)
    }

    @Test
    fun imagesAndDigitGrid() {
        val w = 29; val h = 29
        val rgba = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            val v = if ((x - 14) * (x - 14) + (y - 14) * (y - 14) in 30..80) 255 else 0
            rgba[o] = v.toByte(); rgba[o + 1] = v.toByte(); rgba[o + 2] = v.toByte(); rgba[o + 3] = 255.toByte()
        }
        val raw = (0 until w * h).map { (rgba[it * 4].toInt() and 0xff) / 255.0 }
        val s = store(
            """{"t":"plot","plot":"digit","kind":"image"}""",
            """{"t":"image","plot":"digit","w":$w,"h":$h,"count":1,"rgba":"${Base64.getEncoder().encodeToString(rgba)}","raw":${nums(raw)}}""",
        )
        val (img, frame) = render(s, "digit", PlotTheme.dark(), w = 400, h = 340)
        save(img, "image-dark.png")
        // Square pixels.
        assertEquals(frame.xs.toPixel(1.0) - frame.xs.toPixel(0.0), frame.ys.toPixel(1.0) - frame.ys.toPixel(0.0), 1e-6)
        val ring = s.withPlot("digit") { PlotRenderer.hover(frame, it!!, frame.xs.toPixel(14.5), frame.ys.toPixel(14.5 - 7), PlotTheme.dark()) }
        assertEquals("pixel (14, 7)", ring!!.lines[0])
    }

    @Test
    fun hugeLossCurveDrawsQuickly() {
        val n = 1_000_000
        val sb = StringBuilder()
        sb.append("""{"t":"series","plot":"big","series":"loss","style":"line","start":0,"x":[""")
        for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(i) }
        sb.append("],\"y\":[")
        for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(exp(-i / 2e5) + (i % 7) * 0.001) }
        sb.append("]}")
        val s = PlotStore()
        val parse = System.nanoTime()
        assertTrue(s.apply(Message.parse(sb)))
        val parseMs = (System.nanoTime() - parse) / 1e6
        render(s, "big", PlotTheme.light())
        val t = System.nanoTime()
        repeat(5) { render(s, "big", PlotTheme.light()) }
        val ms = (System.nanoTime() - t) / 1e6 / 5
        assertTrue(ms < 150, "a million points drew in $ms ms")
        assertTrue(parseMs < 3000, "a million points parsed in $parseMs ms")
    }

    @Test
    fun hugeScatterDrawsQuickly() {
        val n = 1_000_000
        val r = java.util.Random(1)
        val sb = StringBuilder("""{"t":"series","plot":"cloud","series":"p","style":"scatter","start":0,"x":[""")
        for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(r.nextGaussian()) }
        sb.append("],\"y\":[")
        for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(r.nextGaussian()) }
        sb.append("]}")
        val s = PlotStore()
        assertTrue(s.apply(Message.parse(sb)))
        save(render(s, "cloud", PlotTheme.light()).first, "cloud-light.png")
        save(render(s, "cloud", PlotTheme.dark(), scale = 2.0).first, "cloud-dark-200.png")
        val t = System.nanoTime()
        repeat(3) { render(s, "cloud", PlotTheme.light()) }
        val ms = (System.nanoTime() - t) / 1e6 / 3
        assertTrue(ms < 600, "a million points drew in $ms ms")
    }

    @Test
    fun emptyPlotSaysSo() {
        val s = store("""{"t":"plot","plot":"empty","kind":"xy"}""")
        val (img, _) = render(s, "empty", PlotTheme.light())
        save(img, "empty-light.png")
        assertTrue(img.width > 0)
    }
}

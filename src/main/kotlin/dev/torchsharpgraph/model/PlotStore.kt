package dev.torchsharpgraph.model

import dev.torchsharpgraph.protocol.Message
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The plots of one project: the current (or last) run. Messages are applied on the connection's thread; the window
 * reads under the same lock while painting. Listeners are told after every change and must not block.
 */
class PlotStore {
    private val plots = LinkedHashMap<String, PlotData>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var run: RunInfo? = null; private set

    /** Bumped on every change, so the window can tell whether anything needs repainting. */
    @Volatile var version = 0L; private set

    /** Bumped when plots are added or removed (the window then rebuilds its tabs). */
    @Volatile var layoutVersion = 0L; private set

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun <T> read(block: (List<PlotData>) -> T): T = synchronized(this) { block(plots.values.toList()) }

    fun <T> withPlot(name: String, block: (PlotData?) -> T): T = synchronized(this) { block(plots[name]) }

    fun plotNames(): List<String> = synchronized(this) { plots.keys.toList() }

    /** A new program run started. [clearOld]: drop the previous run's plots (the default). */
    fun startRun(info: RunInfo, clearOld: Boolean) {
        synchronized(this) {
            run?.connected = false
            run = info
            if (clearOld && plots.isNotEmpty()) { plots.clear(); layoutVersion++ }
            version++
        }
        fire()
    }

    fun endRun(info: RunInfo) {
        synchronized(this) {
            info.connected = false
            if (info.endedAt == null) info.endedAt = System.currentTimeMillis()
            version++
        }
        fire()
    }

    fun clear() {
        synchronized(this) { plots.clear(); layoutVersion++; version++ }
        fire()
    }

    /** Applies one message from the program. Returns false if it was not understood (it is then ignored). */
    fun apply(m: Message): Boolean {
        val changed = synchronized(this) { applyLocked(m) }
        if (changed) fire()
        return changed
    }

    private fun applyLocked(m: Message): Boolean {
        when (m.type) {
            "clear" -> {
                val name = m.string("plot")
                if (name == null) plots.clear() else plots.remove(name)
                layoutVersion++
            }
            "plot" -> {
                val name = m.string("plot") ?: return false
                val kind = when (m.string("kind")) {
                    "loss" -> PlotKind.LOSS; "heatmap" -> PlotKind.HEATMAP; "image" -> PlotKind.IMAGE; else -> PlotKind.XY
                }
                val p = plot(name, kind) ?: return false
                p.kind = kind
                m.string("title")?.let { p.title = it }
                m.string("x")?.let { p.xLabel = it }
                m.string("y")?.let { p.yLabel = it }
                m.bool("logY")?.let { p.logY = it }
            }
            "series" -> {
                val name = m.string("plot") ?: return false
                val seriesName = m.string("series") ?: return false
                val x = m.doubles("x") ?: return false
                val y = m.doubles("y") ?: return false
                val start = m.int("start") ?: 0
                val style = if (m.string("style") == "scatter") SeriesStyle.SCATTER else SeriesStyle.LINE
                val p = plot(name, PlotKind.XY) ?: return false
                if (p.series.none { it.name == seriesName } && p.series.size >= Limits.MAX_SERIES_PER_PLOT) return false
                val s = p.series(seriesName, style)
                if (start.toLong() + x.size > Limits.MAX_POINTS_PER_SERIES) return false
                if (start <= 0) s.replace(x, y) else s.putAt(start, x, y)
            }
            "heatmap" -> {
                val name = m.string("plot") ?: return false
                val rows = m.int("rows") ?: return false
                val cols = m.int("cols") ?: return false
                val v = m.doubles("v") ?: return false
                if (rows <= 0 || cols <= 0 || rows.toLong() * cols > Limits.MAX_HEATMAP_CELLS || v.size != rows * cols) return false
                val x0 = m.double("x0") ?: 0.0; val x1 = m.double("x1") ?: cols.toDouble()
                val y0 = m.double("y0") ?: 0.0; val y1 = m.double("y1") ?: rows.toDouble()
                if (!(x1 > x0) || !(y1 > y0)) return false
                val p = plot(name, PlotKind.HEATMAP) ?: return false
                p.kind = PlotKind.HEATMAP
                p.heatmap = Heatmap(rows, cols, v, x0, x1, y0, y1, (m.int("contours") ?: 0).coerceIn(0, 50))
            }
            "image" -> {
                val name = m.string("plot") ?: return false
                val w = m.int("w") ?: return false
                val h = m.int("h") ?: return false
                val rgba = decodeRgba(m.string("rgba"), w, h) ?: return false
                val raw = m.doubles("raw")?.takeIf { it.size == w * h } ?: DoubleArray(w * h) { Double.NaN }
                val p = plot(name, PlotKind.IMAGE) ?: return false
                p.kind = PlotKind.IMAGE
                p.image = ImageData(w, h, rgba, raw, m.int("count") ?: 1)
            }
            else -> return false
        }
        version++
        return true
    }

    private fun plot(name: String, kind: PlotKind): PlotData? {
        plots[name]?.let { return it }
        if (plots.size >= Limits.MAX_PLOTS) return null
        layoutVersion++
        return PlotData(name, kind).also { plots[name] = it }
    }

    private fun fire() {
        for (l in listeners) l()
    }
}

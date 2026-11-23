package dev.torchsharpgraph.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.UIUtil
import dev.torchsharpgraph.model.PlotKind
import dev.torchsharpgraph.project.GraphsProjectService
import dev.torchsharpgraph.render.Frame
import dev.torchsharpgraph.render.Hover
import dev.torchsharpgraph.render.PlotRenderer
import dev.torchsharpgraph.render.PlotTheme
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JComponent
import kotlin.math.pow

/**
 * One plot. Mouse: hover reads values, wheel zooms (Shift: only x, Alt: only y), drag pans, double-click resets.
 * Right-click offers log scale, reset zoom and export. It never takes keyboard focus away from the editor.
 */
class PlotCanvas(
    private val project: Project,
    val plotName: String,
    private val showTitle: Boolean,
    private val onActivated: (PlotCanvas) -> Unit = {},
) : JComponent() {
    private val service = GraphsProjectService.get(project)
    private var frame: Frame? = null
    private var hover: Hover? = null
    private var dragFrom: Point? = null
    private var mouse: Point? = null

    init {
        isOpaque = true
        isFocusable = false
        minimumSize = Dimension(160, 120)
        preferredSize = Dimension(480, 320)
        val mouseHandler = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) { mouse = e.point; updateHover() }
            override fun mouseExited(e: MouseEvent) { mouse = null; hover = null; repaint() }
            override fun mousePressed(e: MouseEvent) {
                onActivated(this@PlotCanvas)
                if (UIUtil.isActionClick(e, MouseEvent.MOUSE_PRESSED)) dragFrom = e.point
            }
            override fun mouseReleased(e: MouseEvent) { dragFrom = null }
            override fun mouseDragged(e: MouseEvent) {
                val from = dragFrom ?: return
                val f = frame ?: return
                PlotRenderer.pan(f, service.view(plotName), (e.x - from.x).toDouble(), (e.y - from.y).toDouble())
                dragFrom = e.point
                hover = null
                repaint()
            }
            override fun mouseClicked(e: MouseEvent) {
                if (UIUtil.isActionClick(e, MouseEvent.MOUSE_CLICKED) && e.clickCount == 2) resetZoom()
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                val f = frame ?: return
                val factor = 1.15.pow(e.preciseWheelRotation)
                PlotRenderer.zoom(f, service.view(plotName), e.x.toDouble(), e.y.toDouble(), factor, xOnly = e.isShiftDown, yOnly = e.isAltDown)
                onActivated(this@PlotCanvas)
                repaint()
            }
        }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
        addMouseWheelListener(mouseHandler)
        PopupHandler.installPopupMenu(this, popupActions(), "TorchSharpGraphPlot")
    }

    private fun popupActions(): ActionGroup = DefaultActionGroup(
        LogScaleAction { this },
        DefaultActionGroup("Smoothing", true).apply {
            for ((label, w) in listOf("Off" to 0.0, "Light (0.6)" to 0.6, "Medium (0.9)" to 0.9, "Strong (0.99)" to 0.99))
                add(SmoothingAction(label, w) { this@PlotCanvas })
        },
        DumbAwareAction.create("Reset Zoom") { resetZoom() },
        DumbAwareAction.create("Export as PNG…") { exportPng() },
        DumbAwareAction.create("Copy as Image") { copyImage() },
    )

    val isLossOrXY: Boolean get() = service.store.withPlot(plotName) { it != null && it.kind != PlotKind.IMAGE }

    var logScale: Boolean
        get() = service.store.withPlot(plotName) { p -> p != null && PlotRenderer.logY(p, service.view(plotName)) }
        set(value) {
            val v = service.view(plotName)
            v.logY = value
            v.y = null
            repaint()
        }

    fun resetZoom() {
        service.view(plotName).reset()
        hover = null
        repaint()
    }

    private fun updateHover() {
        val f = frame ?: return
        val m = mouse ?: return
        val theme = IdeTheme.current()
        hover = service.store.withPlot(plotName) { p -> p?.let { PlotRenderer.hover(f, it, m.x.toDouble(), m.y.toDouble(), theme) } }
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            com.intellij.ide.ui.UISettings.setupAntialiasing(g2)
            val theme = IdeTheme.current()
            service.store.withPlot(plotName) { plot ->
                if (plot == null) {
                    g2.color = theme.background
                    g2.fillRect(0, 0, width, height)
                } else {
                    frame = PlotRenderer.paint(g2, width, height, plot, service.view(plotName), theme, hover,
                        if (showTitle) plot.title ?: plot.name else plot.title)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    /** Called when new data arrived: keeps the hover text current. */
    fun refresh() {
        if (mouse != null && hover != null) updateHover() else repaint()
    }

    /** The plot as a picture at twice the size on screen, so it stays sharp when printed or zoomed. */
    fun toImage(theme: PlotTheme = IdeTheme.current(), scale: Double = 2.0): BufferedImage {
        val w = maxOf(width, 480); val h = maxOf(height, 320)
        val img = BufferedImage((w * scale).toInt(), (h * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.scale(scale, scale)
        service.store.withPlot(plotName) { plot ->
            if (plot != null) PlotRenderer.paint(g, w, h, plot, service.view(plotName), theme, null, plot.title ?: plot.name, hints = false)
        }
        g.dispose()
        return img
    }

    /** Puts the plot on the clipboard as a picture (to paste into notes or a chat). */
    fun copyImage() {
        val image = toImage()
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor == java.awt.datatransfer.DataFlavor.imageFlavor
            override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
                if (flavor != java.awt.datatransfer.DataFlavor.imageFlavor) throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                return image
            }
        })
    }

    fun exportPng() {
        val descriptor = FileSaverDescriptor("Export Plot as PNG", "Save this plot as a PNG picture.", "png")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as java.nio.file.Path?, UiText.fileName(plotName) + ".png") ?: return
        val file: File = wrapper.file
        try {
            ImageIO.write(toImage(), "png", file)
        } catch (e: IOException) {
            Messages.showErrorDialog(project, "Couldn't save the picture: ${e.message ?: "unknown error"}", "Export as PNG")
        }
    }

    val isLoss: Boolean get() = service.store.withPlot(plotName) { it?.kind == PlotKind.LOSS }

    var smoothing: Double
        get() = service.view(plotName).smoothing
        set(value) { service.view(plotName).smoothing = value; repaint() }

    /** One smoothing choice (loss plots only). */
    class SmoothingAction(label: String, private val weight: Double, private val target: () -> PlotCanvas?) :
        ToggleAction(label, "Smooth a noisy loss curve; the raw values stay faintly visible", null) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = target()?.smoothing == weight
        override fun setSelected(e: AnActionEvent, state: Boolean) { if (state) target()?.smoothing = weight }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabledAndVisible = target()?.isLoss ?: false
        }
    }

    /** Toggles the log scale of the canvas that [target] returns. Shared by the toolbar and the right-click menu. */
    class LogScaleAction(private val target: () -> PlotCanvas?) : ToggleAction("Log Scale", "Show the y axis on a log scale", IdeTheme.logIcon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent) = target()?.logScale ?: false
        override fun setSelected(e: AnActionEvent, state: Boolean) { target()?.logScale = state }
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = target()?.isLossOrXY ?: false
        }
    }
}

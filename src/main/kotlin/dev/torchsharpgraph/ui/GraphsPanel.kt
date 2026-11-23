package dev.torchsharpgraph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.concurrency.EdtExecutorService
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.torchsharpgraph.project.GraphsProjectService
import java.awt.BorderLayout
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * The Graphs tool window: a toolbar, a status line, and every plot of the current (or last) run, either side by
 * side in a grid or one per tab. Updates arrive at most about 30 times a second however fast the program sends.
 */
class GraphsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val service = GraphsProjectService.get(project)
    private val store = service.store
    private val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(0, 8)
    }
    private val body = JPanel(BorderLayout())
    private val empty = JBPanelWithEmptyText().withEmptyText("No plots yet").apply {
        emptyText.appendSecondaryText("Call Plot.Scatter, Plot.Line or Plot.Loss from your program", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        emptyText.appendLine("Plots show here while it runs")
    }
    private var canvases = listOf<PlotCanvas>()
    private var tabs: JBTabbedPane? = null
    private var active: PlotCanvas? = null
    private var builtLayout = -1L
    private var builtTabs = false
    var useTabs = false
        private set
    private val pending = AtomicBoolean(false)
    private val removeListener: () -> Unit

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("TorchSharpGraphToolbar", toolbarActions(), true)
        toolbar.targetComponent = this
        val top = JPanel(BorderLayout())
        top.add(toolbar.component, BorderLayout.WEST)
        top.add(status, BorderLayout.CENTER)
        status.horizontalAlignment = SwingConstants.LEFT
        setToolbar(top)
        setContent(body)
        removeListener = store.addListener { scheduleRefresh() }
        refresh()
    }

    private fun toolbarActions() = DefaultActionGroup(
        object : DumbAwareAction("Clear Plots", "Remove all plots", AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) = service.clear()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = store.plotNames().isNotEmpty() }
        },
        PlotCanvas.LogScaleAction { current() },
        object : DumbAwareAction("Reset Zoom", "Show all the data of the selected plot", AllIcons.General.FitContent) {
            override fun actionPerformed(e: AnActionEvent) { current()?.resetZoom() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = current() != null }
        },
        object : DumbAwareAction("Export as PNG…", "Save the selected plot as a PNG picture", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) { current()?.exportPng() }
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = current() != null }
        },
        object : ToggleAction("Show as Tabs", "One tab per plot instead of all plots side by side", IdeTheme.gridIcon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = useTabs
            override fun setSelected(e: AnActionEvent, state: Boolean) { useTabs = state; builtLayout = -1; refresh() }
        },
    )

    /** The plot the toolbar acts on: the selected tab, or the last plot clicked in the grid (else the first). */
    fun current(): PlotCanvas? {
        if (useTabs) {
            val t = tabs ?: return null
            val i = t.selectedIndex
            return canvases.getOrNull(i)
        }
        return active?.takeIf { it in canvases } ?: canvases.firstOrNull()
    }

    private fun scheduleRefresh() {
        if (pending.compareAndSet(false, true)) {
            EdtExecutorService.getScheduledExecutorInstance().schedule({
                pending.set(false)
                if (!project.isDisposed) refresh()
            }, 33, TimeUnit.MILLISECONDS)
        }
    }

    /** Rebuilds the plot panels when plots came or went, else just repaints them. Runs on the UI thread. */
    fun refresh() {
        ApplicationManager.getApplication().assertIsDispatchThread()
        updateStatus()
        val names = store.plotNames()
        if (store.layoutVersion != builtLayout || builtTabs != useTabs || names != canvases.map { it.plotName }) {
            rebuild(names)
        } else {
            canvases.forEach { it.refresh() }
        }
    }

    private fun rebuild(names: List<String>) {
        val selected = current()?.plotName
        builtLayout = store.layoutVersion
        builtTabs = useTabs
        body.removeAll()
        tabs = null
        canvases = names.map { PlotCanvas(project, it, showTitle = !useTabs) { c -> active = c } }
        when {
            canvases.isEmpty() -> body.add(empty, BorderLayout.CENTER)
            useTabs -> {
                val t = JBTabbedPane()
                for (c in canvases) t.addTab(UiText.plain(c.plotName), c)
                canvases.indexOfFirst { it.plotName == selected }.takeIf { it >= 0 }?.let { t.selectedIndex = it }
                tabs = t
                body.add(t, BorderLayout.CENTER)
            }
            else -> {
                val n = canvases.size
                // Wide tool windows get more columns.
                val cols = if (n <= 1) 1 else if (body.width > 0 && body.height > 0 && body.width < body.height) 1.coerceAtLeast(ceil(sqrt(n / 2.0)).toInt())
                           else ceil(sqrt(n.toDouble())).toInt()
                val rows = ceil(n / cols.toDouble()).toInt()
                val grid = JPanel(GridLayout(rows, cols, JBUI.scale(1), JBUI.scale(1)))
                grid.background = JBUI.CurrentTheme.ToolWindow.borderColor()
                for (c in canvases) grid.add(c)
                body.add(grid, BorderLayout.CENTER)
                active = canvases.firstOrNull { it.plotName == selected }
            }
        }
        body.revalidate()
        body.repaint()
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss")

    private fun updateStatus() {
        val run = store.run
        status.text = when {
            run == null -> "Waiting for a program to send plots"
            run.connected && run.endedAt == null -> "Receiving from ${UiText.plain(run.program)}"
            else -> "${UiText.plain(run.program)} finished at ${timeFormat.format(Date(run.endedAt ?: System.currentTimeMillis()))}"
        }
        status.icon = if (run != null && run.connected && run.endedAt == null) AllIcons.Debugger.ThreadRunning else null
    }

    override fun dispose() {
        removeListener()
    }
}

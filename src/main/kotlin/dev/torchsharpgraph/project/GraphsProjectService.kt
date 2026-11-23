package dev.torchsharpgraph.project

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import dev.torchsharpgraph.model.PlotStore
import dev.torchsharpgraph.model.RunInfo
import dev.torchsharpgraph.render.ViewState
import dev.torchsharpgraph.settings.GraphsSettings
import java.util.concurrent.ConcurrentHashMap

/** The plots of one project and what the user did to them (zoom, log scale), kept while the project is open. */
@Service(Service.Level.PROJECT)
class GraphsProjectService(private val project: Project) : Disposable {
    val store = PlotStore()
    private val views = ConcurrentHashMap<String, ViewState>()

    /** True once the project is known to use the library, or plots arrived. */
    @Volatile var relevant = false; private set

    fun view(plot: String): ViewState = views.getOrPut(plot) { ViewState() }

    fun runStarted(info: RunInfo) {
        val clear = GraphsSettings.get().clearOnNewRun
        if (clear) views.clear()
        store.startRun(info, clear)
        relevant = true
        showToolWindow(GraphsSettings.get().openOnData)
    }

    fun runEnded(info: RunInfo) = store.endRun(info)

    fun clear() {
        views.clear()
        store.clear()
    }

    /** Makes the Graphs window available (its button appears), and shows it if [open], without taking keyboard focus. */
    fun showToolWindow(open: Boolean) {
        relevant = true
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return@invokeLater
            tw.isAvailable = true
            if (open && !tw.isVisible) tw.show()
        }, ModalityState.nonModal())
    }

    override fun dispose() {}

    companion object {
        const val TOOL_WINDOW_ID = "Graphs"
        fun get(project: Project): GraphsProjectService = project.getService(GraphsProjectService::class.java)
    }
}

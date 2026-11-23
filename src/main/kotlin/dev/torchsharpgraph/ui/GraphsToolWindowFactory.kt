package dev.torchsharpgraph.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * The Graphs tool window. It starts hidden in every project: it becomes available when the project uses the
 * TorchSharpGraph library (checked on open) or when a program actually sends plots.
 */
class GraphsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project) = false

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GraphsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        Disposer.register(toolWindow.disposable, panel)
    }
}

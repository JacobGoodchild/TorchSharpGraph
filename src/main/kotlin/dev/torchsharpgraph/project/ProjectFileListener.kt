package dev.torchsharpgraph.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.nio.file.Path

/** When a project file starts referencing TorchSharpGraph (package just added), offer the Graphs window at once. */
class ProjectFileListener : BulkFileListener {
    private val names = Regex(".*\\.(csproj|fsproj|vbproj)$|^Directory\\.(Packages|Build)\\.props$|^packages\\.config$", RegexOption.IGNORE_CASE)

    override fun after(events: List<VFileEvent>) {
        val paths = events.mapNotNull { e -> e.path.takeIf { names.matches(it.substringAfterLast('/')) } }
        if (paths.isEmpty()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            for (path in paths) {
                if (!LibraryUsage.mentionsLibrary(Path.of(path))) continue
                for (project in ProjectManager.getInstance().openProjects) {
                    val base = project.basePath ?: continue
                    if (project.isDisposed || !(path == base || path.startsWith("$base/"))) continue
                    val service = GraphsProjectService.get(project)
                    if (!service.relevant) service.showToolWindow(open = false)
                }
            }
        }
    }
}

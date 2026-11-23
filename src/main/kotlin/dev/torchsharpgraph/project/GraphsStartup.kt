package dev.torchsharpgraph.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import dev.torchsharpgraph.server.GraphsServer
import dev.torchsharpgraph.server.IdeFiles
import java.nio.file.Path

/** When a project opens: start listening (once per IDE) and offer the Graphs window if the project uses the library. */
class GraphsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val server = GraphsServer.get()
        server.ensureStarted()
        IdeFiles.write(server.port)
        val base = project.basePath ?: return
        if (LibraryUsage.usesLibrary(Path.of(base))) GraphsProjectService.get(project).showToolWindow(open = false)
    }
}

/** Keeps the list of open projects in the IDE file current. */
class GraphsProjectCloseListener : ProjectCloseListener {
    override fun projectClosed(project: Project) {
        val port = GraphsServer.get().port
        if (port > 0) IdeFiles.write(port)
    }
}

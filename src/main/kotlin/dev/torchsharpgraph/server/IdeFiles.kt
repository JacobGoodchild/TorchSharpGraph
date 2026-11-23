package dev.torchsharpgraph.server

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Tells programs where to find this IDE: ~/.torchsharpgraph/ides/<process id>.json holds the port and the open
 * project folders, so a program connects to the IDE that has its folder open. Removed when the IDE closes.
 */
object IdeFiles {
    private val LOG = logger<IdeFiles>()

    fun folder(home: Path = Path.of(System.getProperty("user.home"))): Path = home.resolve(".torchsharpgraph").resolve("ides")

    private val ownFile: Path get() = folder().resolve("${ProcessHandle.current().pid()}.json")

    /** The file's text: {"port":47811,"pid":123,"projects":["/home/me/app"]}. */
    fun content(port: Int, pid: Long, projects: List<String>): String =
        buildString {
            append("{\"port\":").append(port).append(",\"pid\":").append(pid).append(",\"projects\":[")
            projects.forEachIndexed { i, p -> if (i > 0) append(','); append(quote(p)) }
            append("]}")
        }

    internal fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append(String.format("\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    @Synchronized
    fun write(port: Int) {
        if (port <= 0) return
        try {
            val projects = ProjectManager.getInstance().openProjects.filter { !it.isDefault && !it.isDisposed }.mapNotNull { it.basePath }
            val dir = folder()
            Files.createDirectories(dir)
            val tmp = Files.createTempFile(dir, "ide", ".tmp")
            Files.writeString(tmp, content(port, ProcessHandle.current().pid(), projects))
            Files.move(tmp, ownFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            LOG.info("Graphs: couldn't write ${ownFile}: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            LOG.info("Graphs: couldn't write ${ownFile}: ${e.message}")
        }
    }

    @Synchronized
    fun delete() {
        try { Files.deleteIfExists(ownFile) } catch (_: IOException) {}
    }
}

package dev.torchsharpgraph.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import dev.torchsharpgraph.model.RunInfo
import dev.torchsharpgraph.project.GraphsProjectService
import dev.torchsharpgraph.protocol.Message
import dev.torchsharpgraph.settings.GraphsSettings
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

private val LOG = logger<GraphsServer>()

/**
 * Listens on 127.0.0.1 for programs that use the TorchSharpGraph library and hands their plots to the right
 * project. Nothing is ever sent anywhere else, and connections from other computers are impossible (the socket is
 * bound to the loopback address only).
 */
@Service(Service.Level.APP)
class GraphsServer : Disposable {
    @Volatile private var server: ServerSocket? = null
    private val connections = CopyOnWriteArrayList<Socket>()
    @Volatile private var disposed = false

    /** The port in use, or -1 when not listening. */
    val port: Int get() = server?.localPort ?: -1

    fun ensureStarted() {
        synchronized(this) {
            if (disposed || server != null || !GraphsSettings.get().listen) return
            val ss = bind(GraphsSettings.get().port) ?: return
            server = ss
            Thread({ acceptLoop(ss) }, "TorchSharpGraph listener").apply { isDaemon = true }.start()
            LOG.info("Graphs: listening on 127.0.0.1:${ss.localPort}")
        }
        IdeFiles.write(port)
    }

    fun restart() {
        stop()
        ensureStarted()
    }

    private fun stop() {
        synchronized(this) {
            try { server?.close() } catch (_: IOException) {}
            server = null
            for (c in connections) try { c.close() } catch (_: IOException) {}
            connections.clear()
        }
        IdeFiles.delete()
    }

    private fun bind(first: Int): ServerSocket? {
        val loopback = InetAddress.getLoopbackAddress()
        // The chosen port, then the next few (another IDE may already have it), then any free port.
        for (p in (first until first + 10).filter { it in 1..65535 } + 0) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = false
                ss.bind(InetSocketAddress(loopback, p), 16)
                return ss
            } catch (_: IOException) {
            }
        }
        LOG.warn("Graphs: no port to listen on")
        return null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try { ss.accept() } catch (_: IOException) { break }
            if (!socket.inetAddress.isLoopbackAddress || connections.size >= MAX_CONNECTIONS) {
                try { socket.close() } catch (_: IOException) {}
                continue
            }
            connections.add(socket)
            Thread({ serve(socket) }, "TorchSharpGraph connection").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 5000
            Session.run(socket.getInputStream(), socket.getOutputStream(), onWelcome = { socket.soTimeout = 0 }) { hello ->
                val project = projectFor(hello.string("dir"), hello.string("exe")) ?: return@run null
                val service = GraphsProjectService.get(project)
                object : Session.Sink {
                    override fun started(info: RunInfo) = service.runStarted(info)
                    override fun apply(m: Message) { service.store.apply(m) }
                    override fun ended(info: RunInfo) = service.runEnded(info)
                }
            }
        } catch (_: SocketException) {
        } catch (e: IOException) {
            LOG.debug("Graphs: connection closed", e)
        } catch (e: RuntimeException) {
            LOG.warn("Graphs: dropped a connection", e)
        } finally {
            connections.remove(socket)
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** The open project whose folder holds the program (the longest match), else the last active project. */
    private fun projectFor(dir: String?, exe: String?): Project? {
        val open = ProjectManager.getInstance().openProjects.filter { !it.isDisposed && !it.isDefault }
        val paths = listOfNotNull(dir, exe).map { normalise(it) }
        val best = open.mapNotNull { p ->
            val base = p.basePath?.let { normalise(it) } ?: return@mapNotNull null
            val score = paths.filter { it == base || it.startsWith("$base/") }.maxOfOrNull { base.length } ?: return@mapNotNull null
            p to score
        }.maxByOrNull { it.second }?.first
        if (best != null) return best
        val focused = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        return focused?.takeIf { !it.isDisposed } ?: open.firstOrNull()
    }

    override fun dispose() {
        disposed = true
        stop()
    }

    companion object {
        const val MAX_CONNECTIONS = 16

        fun get(): GraphsServer = ApplicationManager.getApplication().getService(GraphsServer::class.java)

        internal fun normalise(path: String): String = path.replace('\\', '/').trimEnd('/')
    }
}

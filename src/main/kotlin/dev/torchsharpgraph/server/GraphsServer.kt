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
import dev.torchsharpgraph.protocol.JsonException
import dev.torchsharpgraph.protocol.Message
import dev.torchsharpgraph.settings.GraphsSettings
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
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
        var run: RunInfo? = null
        var service: GraphsProjectService? = null
        try {
            socket.soTimeout = 5000
            val input = BufferedInputStream(socket.getInputStream(), 1 shl 16)
            val helloLine = readLine(input, 64 * 1024) ?: return
            val hello = Message.parse(helloLine)
            if (hello.type != "hello" || hello.int("protocol") != 1) return
            socket.getOutputStream().apply { write("{\"t\":\"welcome\",\"protocol\":1}\n".toByteArray(StandardCharsets.UTF_8)); flush() }
            socket.soTimeout = 0
            val project = projectFor(hello.string("dir"), hello.string("exe")) ?: return
            val s = GraphsProjectService.get(project)
            service = s
            val info = RunInfo(
                hello.string("run") ?: "", hello.string("program")?.take(200) ?: "program", hello.int("pid") ?: 0,
                hello.string("dir"), System.currentTimeMillis(),
            )
            run = info
            s.runStarted(info)
            while (!disposed) {
                val line = readLine(input, MAX_LINE) ?: break
                if (line.isBlank()) continue
                val m = try { Message.parse(line) } catch (e: JsonException) { LOG.debug("Graphs: unreadable message", e); continue }
                if (m.type == "end") { info.endedAt = System.currentTimeMillis(); continue }
                s.store.apply(m)
            }
        } catch (_: SocketException) {
        } catch (e: IOException) {
            LOG.debug("Graphs: connection closed", e)
        } catch (e: JsonException) {
            LOG.debug("Graphs: bad hello", e)
        } catch (e: RuntimeException) {
            LOG.warn("Graphs: dropped a connection", e)
        } finally {
            connections.remove(socket)
            try { socket.close() } catch (_: IOException) {}
            val r = run
            if (r != null) service?.runEnded(r)
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
        /** One message can carry a big tensor, but not more than this. */
        const val MAX_LINE = 256 * 1024 * 1024

        fun get(): GraphsServer = ApplicationManager.getApplication().getService(GraphsServer::class.java)

        internal fun normalise(path: String): String = path.replace('\\', '/').trimEnd('/')

        /** Reads one line of UTF-8 (without the newline), or null at the end. Longer lines than [max] end the connection. */
        internal fun readLine(input: InputStream, max: Int): String? {
            val out = ByteArrayOutputStream(256)
            while (true) {
                val b = input.read()
                if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.UTF_8)
                if (b == '\n'.code) return out.toString(StandardCharsets.UTF_8)
                if (out.size() >= max) throw IOException("Message too long")
                out.write(b)
            }
        }
    }
}

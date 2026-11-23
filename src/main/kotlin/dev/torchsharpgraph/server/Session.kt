package dev.torchsharpgraph.server

import com.intellij.openapi.diagnostic.logger
import dev.torchsharpgraph.model.RunInfo
import dev.torchsharpgraph.protocol.JsonException
import dev.torchsharpgraph.protocol.Message
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** One program's connection: hello, welcome, then plot messages until the program goes away. */
object Session {
    /** Where a connection's plots go. */
    interface Sink {
        fun started(info: RunInfo)
        fun apply(m: Message)
        fun ended(info: RunInfo)
    }

    /** One message can carry a big tensor, but not more than this. */
    const val MAX_LINE = 256 * 1024 * 1024
    const val PROTOCOL = 1

    private val LOG = logger<Session>()

    /**
     * Runs the connection until it ends. [route] picks where the plots go once the hello has arrived (null refuses
     * the connection). [onWelcome] runs after the welcome was sent (the server lifts its read timeout there).
     */
    fun run(input: InputStream, output: OutputStream, onWelcome: () -> Unit = {}, route: (Message) -> Sink?) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        val helloLine = readLine(buffered, 64 * 1024) ?: return
        val hello = try { Message.parse(helloLine) } catch (_: JsonException) { return }
        if (hello.type != "hello" || hello.int("protocol") != PROTOCOL) return
        val sink = route(hello) ?: return
        output.write("{\"t\":\"welcome\",\"protocol\":$PROTOCOL}\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
        onWelcome()
        val info = RunInfo(
            hello.string("run") ?: "", hello.string("program")?.take(200) ?: "program", hello.int("pid") ?: 0,
            hello.string("dir"), System.currentTimeMillis(),
        )
        sink.started(info)
        try {
            while (true) {
                val line = readLine(buffered, MAX_LINE) ?: break
                if (line.isBlank()) continue
                val m = try { Message.parse(line) } catch (e: JsonException) { LOG.debug("Graphs: unreadable message", e); continue }
                if (m.type == "end") { info.endedAt = System.currentTimeMillis(); continue }
                sink.apply(m)
            }
        } finally {
            sink.ended(info)
        }
    }

    /** Reads one line of UTF-8 (without the newline), or null at the end. A line longer than [max] ends the connection. */
    fun readLine(input: InputStream, max: Int): String? {
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

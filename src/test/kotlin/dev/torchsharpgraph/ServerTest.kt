package dev.torchsharpgraph

import dev.torchsharpgraph.model.PlotKind
import dev.torchsharpgraph.model.PlotStore
import dev.torchsharpgraph.model.RunInfo
import dev.torchsharpgraph.project.LibraryUsage
import dev.torchsharpgraph.protocol.Json
import dev.torchsharpgraph.protocol.JsonException
import dev.torchsharpgraph.protocol.Message
import dev.torchsharpgraph.server.IdeFiles
import dev.torchsharpgraph.server.Session
import dev.torchsharpgraph.ui.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class JsonTest {
    @Test
    fun parsesMessages() {
        val m = Message.parse("""{"t":"series","plot":"a \"b\" \u00e9","start":3,"x":[1,2.5,-3e2,null],"ok":true,"none":null,"list":["a",1]}""")
        assertEquals("series", m.type)
        assertEquals("a \"b\" é", m.string("plot"))
        assertEquals(3, m.int("start"))
        assertArrayEquals(doubleArrayOf(1.0, 2.5, -300.0, Double.NaN), m.doubles("x"))
        assertEquals(true, m.bool("ok"))
        assertNull(m.string("none"))
        assertEquals(listOf("a", 1.0), m.map["list"])
        assertEquals(0, (Json.parse("[]") as DoubleArray).size)
    }

    @Test
    fun rejectsBadInput() {
        for (bad in listOf("{", "{\"a\":}", "[1,]", "{\"a\":1}x", "\"\\q\"", "nul", "{\"a\" 1}", "[" .repeat(100) + "]".repeat(100)))
            assertThrows(JsonException::class.java, { Json.parse(bad) }, bad)
        assertThrows(JsonException::class.java) { Message.parse("[1]") }
    }
}

class StoreTest {
    @Test
    fun appliesAndLimits() {
        val s = PlotStore()
        var events = 0
        val remove = s.addListener { events++ }
        assertTrue(s.apply(Message.parse("""{"t":"plot","plot":"Loss","kind":"loss","x":"step","logY":true}""")))
        assertTrue(s.apply(Message.parse("""{"t":"series","plot":"Loss","series":"loss","style":"line","start":0,"x":[0,1],"y":[1,0.5]}""")))
        assertTrue(s.apply(Message.parse("""{"t":"series","plot":"Loss","series":"loss","style":"line","start":2,"x":[2],"y":[0.25]}""")))
        s.withPlot("Loss") { p ->
            assertEquals(PlotKind.LOSS, p!!.kind)
            assertEquals(true, p.logY)
            assertEquals(3, p.series[0].size)
            assertEquals(0.25, p.series[0].ys[2])
        }
        assertFalse(s.apply(Message.parse("""{"t":"mystery"}""")))
        assertFalse(s.apply(Message.parse("""{"t":"heatmap","plot":"h","rows":2,"cols":2,"v":[1,2,3]}""")), "size must match")
        assertFalse(s.apply(Message.parse("""{"t":"image","plot":"i","w":2,"h":1,"rgba":"not base64!"}""")))
        for (i in 0 until 70) s.apply(Message.parse("""{"t":"plot","plot":"p$i"}"""))
        assertEquals(64, s.plotNames().size, "at most 64 plots")
        s.apply(Message.parse("""{"t":"clear","plot":"Loss"}"""))
        assertFalse("Loss" in s.plotNames())
        s.apply(Message.parse("""{"t":"clear"}"""))
        assertTrue(s.plotNames().isEmpty())
        remove()
        assertEquals(68, events, "one event per accepted message")
    }

    @Test
    fun newRunClearsOldPlotsButEndKeepsThem() {
        val s = PlotStore()
        val first = RunInfo("1", "App", 1, "/x", 0)
        s.startRun(first, clearOld = true)
        s.apply(Message.parse("""{"t":"series","plot":"a","series":"s","style":"line","start":0,"x":[1],"y":[1]}"""))
        s.endRun(first)
        assertEquals(listOf("a"), s.plotNames(), "the last run stays visible")
        assertFalse(first.connected)
        s.startRun(RunInfo("2", "App", 2, "/x", 0), clearOld = true)
        assertTrue(s.plotNames().isEmpty(), "a new run starts afresh")
    }

    @Test
    fun unsortedSeriesAreNoticed() {
        val s = PlotStore()
        s.apply(Message.parse("""{"t":"series","plot":"a","series":"s","style":"scatter","start":0,"x":[3,1,2],"y":[1,1,1]}"""))
        s.withPlot("a") { assertFalse(it!!.series[0].sortedByX) }
    }
}

class SessionTest {
    private fun runSession(input: String, accept: Boolean = true): Triple<String, PlotStore, MutableList<String>> {
        val out = ByteArrayOutputStream()
        val store = PlotStore()
        val events = mutableListOf<String>()
        Session.run(ByteArrayInputStream(input.toByteArray()), out) { hello ->
            if (!accept) return@run null
            events.add("hello ${hello.string("program")}")
            object : Session.Sink {
                override fun started(info: RunInfo) { events.add("start ${info.runId}"); store.startRun(info, true) }
                override fun apply(m: Message) { store.apply(m) }
                override fun ended(info: RunInfo) { events.add("end ${info.endedAt != null}"); store.endRun(info) }
            }
        }
        return Triple(out.toString(Charsets.UTF_8), store, events)
    }

    @Test
    fun helloWelcomeAndPlots() {
        val (reply, store, events) = runSession(
            """{"t":"hello","protocol":1,"run":"r1","program":"Demo","dir":"/w","pid":5}
              |{"t":"series","plot":"a","series":"s","style":"line","start":0,"x":[1,2],"y":[3,4]}
              |not json at all
              |
              |{"t":"end"}
              |""".trimMargin())
        assertEquals("{\"t\":\"welcome\",\"protocol\":1}\n", reply)
        assertEquals(listOf("hello Demo", "start r1", "end true"), events)
        assertEquals(listOf("a"), store.plotNames())
    }

    @Test
    fun wrongProtocolOrNotHelloGetsNoWelcome() {
        assertEquals("", runSession("""{"t":"hello","protocol":2}""" + "\n").first)
        assertEquals("", runSession("GET / HTTP/1.1\r\nHost: x\r\n\r\n").first, "a web browser gets nothing")
        assertEquals("", runSession("""{"t":"hello","protocol":1}""" + "\n", accept = false).first)
    }

    @Test
    fun lineLimits() {
        assertEquals("abc", Session.readLine(ByteArrayInputStream("abc\ndef".toByteArray()), 10))
        assertNull(Session.readLine(ByteArrayInputStream(ByteArray(0)), 10))
        assertThrows(IOException::class.java) { Session.readLine(ByteArrayInputStream("x".repeat(100).toByteArray()), 10) }
        assertEquals("é", Session.readLine(ByteArrayInputStream("é\n".toByteArray()), 10))
    }
}

class DiscoveryAndDetectionTest {
    @Test
    fun ideFileContent() {
        assertEquals("""{"port":47811,"pid":12,"projects":["/a \"b\"","C:\\x"]}""", IdeFiles.content(47811, 12, listOf("/a \"b\"", "C:\\x")))
        val parsed = Message.parse(IdeFiles.content(5, 6, listOf("/p\n")))
        assertEquals(5, parsed.int("port"))
        assertEquals(listOf("/p\n"), parsed.map["projects"])
        assertTrue(IdeFiles.folder(Path.of("/home/u")).endsWith(".torchsharpgraph/ides"))
    }

    @Test
    fun detectsProjectsThatUseTheLibrary(@TempDir dir: Path) {
        val app = dir.resolve("src/App"); Files.createDirectories(app)
        Files.writeString(app.resolve("App.csproj"), """<Project><ItemGroup><PackageReference Include="TorchSharp" Version="0.107.0" /></ItemGroup></Project>""")
        assertFalse(LibraryUsage.usesLibrary(dir), "TorchSharp alone is not enough")
        Files.writeString(app.resolve("App.csproj"), """<Project><ItemGroup><PackageReference Include="TorchSharpGraph" Version="0.1.0" /></ItemGroup></Project>""")
        assertTrue(LibraryUsage.usesLibrary(dir))
        val central = dir.resolve("other"); Files.createDirectories(central)
        Files.writeString(central.resolve("Directory.Packages.props"), """<Project><ItemGroup><PackageVersion Include="TorchSharpGraph" Version="0.1.0" /></ItemGroup></Project>""")
        assertTrue(LibraryUsage.usesLibrary(central))
        // Build output folders are skipped, and a mention in a comment or other file isn't a reference.
        val bin = dir.resolve("plain/bin"); Files.createDirectories(bin)
        Files.writeString(bin.resolve("Copy.csproj"), """<PackageReference Include="TorchSharpGraph" />""")
        Files.writeString(dir.resolve("plain/Readme.md"), "TorchSharpGraph")
        Files.writeString(dir.resolve("plain/Plain.csproj"), "<Project><!-- TorchSharpGraph some day --></Project>")
        assertFalse(LibraryUsage.usesLibrary(dir.resolve("plain")))
        assertFalse(LibraryUsage.usesLibrary(dir.resolve("missing")))
    }

    @Test
    fun plainTextForSwing() {
        assertEquals("\u200B<html><b>x", UiText.plain("<html><b>x"))
        assertEquals("loss", UiText.plain("loss"))
        assertEquals("a_b_c", UiText.fileName("a/b:c"))
        assertEquals("plot", UiText.fileName("..."))
    }
}

/**
 * The real C# library talking to the plugin's session code over a loopback socket. Needs the built sample:
 * TORCHSHARPGRAPH_SAMPLE_DLL=/path/TorchSharpGraph.Samples.dll (CI sets it); skipped otherwise.
 */
class EndToEndTest {
    @Test
    fun sampleProgramSendsEveryPlot(@TempDir work: Path) {
        val dll = System.getenv("TORCHSHARPGRAPH_SAMPLE_DLL")?.takeIf { File(it).isFile }
        assumeTrue(dll != null, "TORCHSHARPGRAPH_SAMPLE_DLL not set")
        val server = ServerSocket()
        server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        val store = PlotStore()
        val events = mutableListOf<String>()
        val serverThread = Thread {
            server.accept().use { socket ->
                Session.run(socket.getInputStream(), socket.getOutputStream()) { hello ->
                    events.add(hello.string("program") ?: "?")
                    object : Session.Sink {
                        override fun started(info: RunInfo) = store.startRun(info, true)
                        override fun apply(m: Message) { store.apply(m) }
                        override fun ended(info: RunInfo) { events.add("ended ${info.endedAt != null}"); store.endRun(info) }
                    }
                }
            }
        }
        serverThread.start()
        val dotnet = System.getenv("DOTNET_EXE") ?: "dotnet"
        val process = ProcessBuilder(dotnet, dll, "quick").directory(work.toFile()).redirectErrorStream(true).apply {
            environment()["TORCHSHARPGRAPH_PORT"] = server.localPort.toString()
        }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        serverThread.join(10_000)
        server.close()
        assertTrue(output.contains("Plots sent to Rider."), output)
        assertEquals(listOf("TorchSharpGraph.Samples", "ended true"), events)
        assertEquals(setOf("points", "tensor cloud", "wave", "Loss", "surface", "rings", "colours"), store.plotNames().toSet())
        store.withPlot("Loss") { p ->
            assertEquals(PlotKind.LOSS, p!!.kind)
            assertEquals(601, p.series.first { it.name == "loss" }.size)
        }
        store.withPlot("rings") { assertEquals(16, it!!.image!!.count) }
        store.withPlot("surface") { p -> assertEquals(120, p!!.heatmap!!.rows); assertEquals(10, p.heatmap!!.contours) }
        assertFalse(Files.exists(work.resolve("plots")), "no PNG files when the viewer got everything")
    }
}

class TicksTest {
    @Test
    fun labelsShowTheStepExactly() {
        for ((range, max) in listOf(dev.torchsharpgraph.render.Range(-0.3, 6.1) to 27, dev.torchsharpgraph.render.Range(0.0, 1e-3) to 5,
                dev.torchsharpgraph.render.Range(-1.0, 1.0) to 4, dev.torchsharpgraph.render.Range(1000.0, 1003.0) to 6)) {
            val (ticks, step) = dev.torchsharpgraph.render.Ticks.linear(range, max)
            val labels = ticks.map { dev.torchsharpgraph.render.Ticks.label(it, step) }
            assertEquals(labels.size, labels.toSet().size, "labels are distinct: $labels")
            for ((t, l) in ticks.zip(labels)) assertEquals(t, l.toDouble(), step * 1e-6, "label $l for $t")
        }
        assertEquals(listOf(-2.0, -1.0, 0.0), dev.torchsharpgraph.render.Ticks.log(dev.torchsharpgraph.render.Range(-2.0, 0.0), 5))
        val oneDecade = dev.torchsharpgraph.render.Ticks.log(dev.torchsharpgraph.render.Range(-1.1, 0.05), 6).map { dev.torchsharpgraph.render.Ticks.logLabel(it) }
        assertEquals(listOf("0.1", "0.2", "0.5", "1"), oneDecade)
        assertEquals("5e-7", dev.torchsharpgraph.render.Ticks.logLabel(kotlin.math.log10(5e-7)))
        assertEquals("1e-6", dev.torchsharpgraph.render.Ticks.logLabel(-6.0))
        assertEquals("0.01", dev.torchsharpgraph.render.Ticks.logLabel(-2.0))
        assertEquals("1.235e7", dev.torchsharpgraph.render.Ticks.value(12345678.0))
        assertEquals("0.1235", dev.torchsharpgraph.render.Ticks.value(0.123456))
    }
}

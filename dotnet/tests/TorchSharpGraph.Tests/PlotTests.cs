using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using TorchSharp;
using Xunit;

namespace TorchSharpGraph.Tests;

/// <summary>Every test uses its own hub (no background thread) so they don't share plots.</summary>
internal sealed class HubFixture : IDisposable
{
    public Hub Hub { get; }
    public List<string> Sent { get; } = [];

    public HubFixture()
    {
        Hub = new Hub(new PlotSettings(), startThread: false)
        {
            TestSink = m => { lock (Sent) Sent.AddRange(m.Select(Protocol.Text)); return true; },
        };
        Plot.TestHub = Hub;
    }

    public List<JsonElement> Messages()
    {
        Hub.SendPending();
        lock (Sent) return Sent.Select(s => JsonDocument.Parse(s).RootElement.Clone()).ToList();
    }

    public void Dispose() => Plot.TestHub = null;
}

[Collection("Plot")]
public class ShapeTests
{
    [Fact]
    public void ScatterArraysAndLists()
    {
        using var f = new HubFixture();
        Plot.Scatter("a", new[] { 1.0, 2, 3 }, new List<float> { 4, 5, 6 });
        var m = f.Messages().Single(x => x.GetProperty("t").GetString() == "series");
        Assert.Equal("scatter", m.GetProperty("style").GetString());
        Assert.Equal([1.0, 2, 3], m.GetProperty("x").EnumerateArray().Select(e => e.GetDouble()));
        Assert.Equal([4.0, 5, 6], m.GetProperty("y").EnumerateArray().Select(e => e.GetDouble()));
    }

    [Fact]
    public void MismatchedLengthsExplainThemselves()
    {
        using var f = new HubFixture();
        var e = Assert.Throws<ArgumentException>(() => Plot.Scatter("a", new[] { 1.0, 2 }, new[] { 1.0 }));
        Assert.Contains("x has 2 values but y has 1", e.Message);
    }

    [Fact]
    public void TensorsOfManyShapes()
    {
        using var f = new HubFixture();
        using var x = torch.linspace(0, 1, 5);
        using var y = torch.ones(5, 1);
        Plot.Line("t", x, y);
        using var pairs = torch.rand(7, 2);
        Plot.Scatter("pairs", pairs);
        var series = f.Messages().Where(m => m.GetProperty("t").GetString() == "series").ToList();
        Assert.Equal(5, series[0].GetProperty("x").GetArrayLength());
        Assert.Equal(7, series[1].GetProperty("y").GetArrayLength());
    }

    [Fact]
    public void TensorWithGradientAndFloat16Works()
    {
        using var f = new HubFixture();
        using var w = torch.randn(10, requires_grad: true);
        using var h = torch.ones(10, dtype: torch.float16);
        Plot.Line("g", w);
        Plot.Line("h", h);
        Assert.Equal(2, f.Messages().Count(m => m.GetProperty("t").GetString() == "series"));
    }

    [Fact]
    public void WrongTensorShapeExplainsItself()
    {
        using var f = new HubFixture();
        using var t = torch.zeros(4, 3);
        var e = Assert.Throws<ArgumentException>(() => Plot.Scatter("bad", t, t));
        Assert.Contains("shape [4, 3]", e.Message);
        var e2 = Assert.Throws<ArgumentException>(() => Plot.Scatter("bad", t));
        Assert.Contains("[N, 2]", e2.Message);
    }

    [Fact]
    public void FunctionSamplesTheRange()
    {
        using var f = new HubFixture();
        Plot.Function("sin", Math.Sin, -Math.PI, Math.PI, points: 101);
        var m = f.Messages().Single(x => x.GetProperty("t").GetString() == "series");
        var xs = m.GetProperty("x").EnumerateArray().Select(e => e.GetDouble()).ToArray();
        Assert.Equal(101, xs.Length);
        Assert.Equal(-Math.PI, xs[0], 9);
        Assert.Equal(Math.PI, xs[^1], 9);
        Assert.Equal("line", m.GetProperty("style").GetString());
        Assert.Throws<ArgumentException>(() => Plot.Function("bad", Math.Sin, 1, 0));
    }

    [Fact]
    public void NonFiniteValuesBecomeGaps()
    {
        using var f = new HubFixture();
        Plot.Function("inv", x => 1 / x, -1, 1, points: 3);
        var m = f.Messages().Single(x => x.GetProperty("t").GetString() == "series");
        Assert.Equal(JsonValueKind.Null, m.GetProperty("y")[1].ValueKind);
    }

    [Fact]
    public void EmptyNamesAreRejected()
    {
        using var f = new HubFixture();
        Assert.Throws<ArgumentException>(() => Plot.Line(" ", new[] { 1.0 }));
    }
}

[Collection("Plot")]
public class LossTests
{
    [Fact]
    public void LossAppendsOnlyNewPoints()
    {
        using var f = new HubFixture();
        Plot.Loss(0, 1.0);
        Plot.Loss(1, 0.5);
        f.Hub.SendPending();
        Plot.Loss(2, 0.25);
        var series = f.Messages().Where(m => m.GetProperty("t").GetString() == "series").ToList();
        Assert.Equal(2, series.Count);
        Assert.Equal(0, series[0].GetProperty("start").GetInt32());
        Assert.Equal(2, series[0].GetProperty("x").GetArrayLength());
        Assert.Equal(2, series[1].GetProperty("start").GetInt32());
        Assert.Equal(0.25, series[1].GetProperty("y")[0].GetDouble());
        var header = f.Messages().First(m => m.GetProperty("t").GetString() == "plot");
        Assert.Equal("loss", header.GetProperty("kind").GetString());
        Assert.Equal("Loss", header.GetProperty("plot").GetString());
    }

    [Fact]
    public void LossFromScalarTensor()
    {
        using var f = new HubFixture();
        using var loss = torch.tensor(0.75);
        Plot.Loss(3, loss);
        var s = f.Messages().Single(m => m.GetProperty("t").GetString() == "series");
        Assert.Equal(0.75, s.GetProperty("y")[0].GetDouble(), 6);
        using var batch = torch.ones(32);
        var e = Assert.Throws<ArgumentException>(() => Plot.Loss(4, batch));
        Assert.Contains(".mean()", e.Message);
    }

    [Fact]
    public void ManyQuickCallsBecomeOneMessage()
    {
        using var f = new HubFixture();
        for (int i = 0; i < 1000; i++) Plot.Function("moving", x => x * i, 0, 1);
        for (int i = 0; i < 1000; i++) Plot.Loss(i, 1.0 / (i + 1));
        var series = f.Messages().Where(m => m.GetProperty("t").GetString() == "series").ToList();
        Assert.Equal(2, series.Count);
        Assert.Equal(999.0, series[0].GetProperty("y")[series[0].GetProperty("y").GetArrayLength() - 1].GetDouble(), 6);
        Assert.Equal(1000, series[1].GetProperty("x").GetArrayLength());
    }

    [Fact]
    public void CallsAreFast()
    {
        using var f = new HubFixture();
        var sw = Stopwatch.StartNew();
        for (int i = 0; i < 100_000; i++) Plot.Loss(i, Math.Exp(-i / 1000.0));
        sw.Stop();
        Assert.True(sw.ElapsedMilliseconds < 2000, $"100,000 loss calls took {sw.ElapsedMilliseconds} ms");
    }
}

[Collection("Plot")]
public class HeatmapAndImageTests
{
    [Fact]
    public void HeatmapFromFunction()
    {
        using var f = new HubFixture();
        Plot.Heatmap("surface", (a, b) => a * a + b * b, -1, 1, -2, 2, resolution: 10, contours: 5);
        var m = f.Messages().Single(x => x.GetProperty("t").GetString() == "heatmap");
        Assert.Equal(10, m.GetProperty("rows").GetInt32());
        Assert.Equal(100, m.GetProperty("v").GetArrayLength());
        Assert.Equal(-2, m.GetProperty("y0").GetDouble());
        Assert.Equal(5, m.GetProperty("contours").GetInt32());
    }

    [Fact]
    public void HeatmapFromTensorAndArray()
    {
        using var f = new HubFixture();
        using var t = torch.rand(1, 20, 30);
        Plot.Heatmap("t", t);
        Plot.Heatmap("a", new double[,] { { 1, 2 }, { 3, 4 } });
        var maps = f.Messages().Where(x => x.GetProperty("t").GetString() == "heatmap").ToList();
        Assert.Equal(30, maps[0].GetProperty("cols").GetInt32());
        Assert.Equal(2, maps[1].GetProperty("x1").GetDouble());
        using var bad = torch.rand(3, 4, 5);
        Assert.Throws<ArgumentException>(() => Plot.Heatmap("bad", bad));
    }

    [Fact]
    public void PointsCanGoOnTopOfAHeatmap()
    {
        using var f = new HubFixture();
        Plot.Heatmap("surface", (a, b) => a + b, 0, 1, 0, 1, resolution: 4);
        Plot.Scatter("surface", new[] { 0.5 }, new[] { 0.5 }, series: "here");
        var headers = f.Messages().Where(x => x.GetProperty("t").GetString() == "plot").ToList();
        Assert.Equal("heatmap", headers.Last().GetProperty("kind").GetString());
        Assert.Single(f.Messages(), x => x.GetProperty("t").GetString() == "heatmap");
    }

    [Theory]
    [InlineData(new long[] { 28, 28 }, 28, 28, 1)]
    [InlineData(new long[] { 1, 28, 28 }, 28, 28, 1)]
    [InlineData(new long[] { 3, 16, 8 }, 8, 16, 1)]
    [InlineData(new long[] { 16, 8, 3 }, 8, 16, 1)]
    [InlineData(new long[] { 4, 10, 10 }, 21, 21, 4)]
    [InlineData(new long[] { 5, 1, 10, 10 }, 32, 21, 5)]
    public void ImageShapes(long[] shape, int w, int h, int count)
    {
        using var t = torch.rand(shape);
        var img = Images.ToImage(t);
        Assert.Equal(w, img.Width);
        Assert.Equal(h, img.Height);
        Assert.Equal(count, img.Count);
        Assert.Equal(w * h * 4, img.Rgba.Length);
    }

    [Fact]
    public void ImageRanges()
    {
        Assert.Equal((255.0, 0.0), Images.Range([0, 0.5, 1]));
        Assert.Equal((1.0, 0.0), Images.Range([0, 200, 255]));
        var (scale, offset) = Images.Range([-1, 1]);
        Assert.Equal(0, -1 * scale + offset, 6);
        Assert.Equal(255, 1 * scale + offset, 6);
        var img = Images.ToImage(new double[,] { { -1, 1 } });
        Assert.Equal(0, img.Rgba[0]);
        Assert.Equal(255, img.Rgba[4]);
    }

    [Fact]
    public void BadImageShape()
    {
        using var t = torch.rand(new long[] { 2, 2, 2, 2, 2 });
        var e = Assert.Throws<ArgumentException>(() => Images.ToImage(t));
        Assert.Contains("[2, 2, 2, 2, 2]", e.Message);
    }
}

[Collection("Plot")]
public class ClearAndLabelTests
{
    [Fact]
    public void ClearOneAndAll()
    {
        using var f = new HubFixture();
        Plot.Line("a", new[] { 1.0, 2 });
        Plot.Line("b", new[] { 1.0, 2 });
        f.Hub.SendPending();
        Plot.Clear("a");
        Plot.Clear();
        var msgs = f.Messages().Skip(4).ToList();
        Assert.Contains(msgs, m => m.GetProperty("t").GetString() == "clear" && !m.TryGetProperty("plot", out _));
        Assert.Empty(f.Hub.Snapshot());
    }

    [Fact]
    public void LabelsAndLogScale()
    {
        using var f = new HubFixture();
        Plot.Loss(0, 1);
        Plot.Labels("Loss", title: "Training", y: "error");
        Plot.LogScale("Loss");
        var header = f.Messages().Last(m => m.GetProperty("t").GetString() == "plot");
        Assert.Equal("Training", header.GetProperty("title").GetString());
        Assert.Equal("error", header.GetProperty("y").GetString());
        Assert.Equal("step", header.GetProperty("x").GetString());
        Assert.True(header.GetProperty("logY").GetBoolean());
        Assert.Equal("loss", header.GetProperty("kind").GetString());
    }

    [Fact]
    public void ReconnectSendsEverythingAgain()
    {
        using var f = new HubFixture();
        Plot.Loss(0, 1); Plot.Loss(1, 0.5);
        f.Hub.SendPending();
        f.Hub.MarkEverythingUnsent();
        var again = f.Messages().Skip(2).ToList();
        Assert.Equal("clear", again[0].GetProperty("t").GetString());
        Assert.Equal(0, again.Single(m => m.GetProperty("t").GetString() == "series").GetProperty("start").GetInt32());
    }
}

[Collection("Plot")]
public class PngTests
{
    [Fact]
    public void SavesEveryKindAsPng()
    {
        using var f = new HubFixture();
        var dir = Path.Combine(Path.GetTempPath(), "tsg-png-" + Guid.NewGuid().ToString("N"));
        try
        {
            Plot.Scatter("points/one", new[] { 1.0, 2, 3 }, new[] { 3.0, 1, 2 });
            Plot.Function("points/one", x => x, 0, 4, series: "y = x");
            for (int i = 0; i < 50; i++) Plot.Loss(i, Math.Exp(-i / 10.0));
            Plot.LogScale("Loss");
            Plot.Heatmap("surface", (a, b) => Math.Sin(a) * Math.Cos(b), -3, 3, -3, 3, resolution: 20);
            using var digits = torch.rand(4, 1, 28, 28);
            Plot.Image("digits", digits);
            var files = Plot.SaveAllPng(dir, 400, 300);
            Assert.Equal(4, files.Count);
            foreach (var file in files)
            {
                var bytes = File.ReadAllBytes(file);
                Assert.True(bytes.Length > 1000, file);
                Assert.Equal(0x89, bytes[0]);
                Assert.Equal((byte)'P', bytes[1]);
            }
            Assert.Contains(files, x => Path.GetFileName(x) == "points_one.png");
            var shots = Environment.GetEnvironmentVariable("TORCHSHARPGRAPH_SHOT_DIR");
            if (!string.IsNullOrEmpty(shots))
            {
                Directory.CreateDirectory(shots);
                foreach (var file in files) File.Copy(file, Path.Combine(shots, "png-" + Path.GetFileName(file)), true);
            }
        }
        finally
        {
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void SavePngOfMissingPlotExplains()
    {
        using var f = new HubFixture();
        var e = Assert.Throws<ArgumentException>(() => Plot.SavePng("nope", "x.png"));
        Assert.Contains("no plot called", e.Message);
    }
}

[Collection("Plot")]
public class ViewerTests
{
    [Fact]
    public void DiscoveryPrefersTheIdeWithThisFolderOpen()
    {
        var home = Path.Combine(Path.GetTempPath(), "tsg-home-" + Guid.NewGuid().ToString("N"));
        var ides = ViewerDiscovery.IdeFolder(home);
        Directory.CreateDirectory(ides);
        try
        {
            var pid = Environment.ProcessId;
            File.WriteAllText(Path.Combine(ides, "a.json"), $"{{\"port\":5001,\"pid\":{pid},\"projects\":[\"/other\"]}}");
            File.WriteAllText(Path.Combine(ides, "b.json"), $"{{\"port\":5002,\"pid\":{pid},\"projects\":[\"/work/proj\"]}}");
            File.WriteAllText(Path.Combine(ides, "dead.json"), "{\"port\":5003,\"pid\":999999999,\"projects\":[\"/work/proj\"]}");
            File.WriteAllText(Path.Combine(ides, "junk.json"), "{not json");
            var ports = ViewerDiscovery.CandidatePorts(null, "/work/proj/bin", null, home);
            Assert.Equal([5002, 5001, ViewerDiscovery.DefaultPort], ports);
            Assert.Equal([1234], ViewerDiscovery.CandidatePorts(1234, "/", null, home));
        }
        finally
        {
            Directory.Delete(home, true);
        }
    }

    [Fact]
    public void NothingListeningDoesNotHang()
    {
        var sw = Stopwatch.StartNew();
        // A port that is closed: refused straight away.
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        var c = ViewerConnection.TryOpen(port, Protocol.Hello("r", "p", "/", null, 1), TimeSpan.FromMilliseconds(400));
        Assert.Null(c);
        Assert.True(sw.ElapsedMilliseconds < 1500);
    }

    [Fact]
    public void SomethingElseOnThePortGetsNoData()
    {
        // A server that accepts but never says welcome (some other program).
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var accept = listener.AcceptTcpClientAsync();
        var sw = Stopwatch.StartNew();
        var c = ViewerConnection.TryOpen(port, Protocol.Hello("r", "p", "/", null, 1), TimeSpan.FromMilliseconds(400));
        Assert.Null(c);
        Assert.True(sw.ElapsedMilliseconds < 1500);
        accept.Result.Dispose();
        listener.Stop();
    }

    [Fact]
    public void TalksToAViewer()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        int port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var lines = new List<string>();
        var server = Task.Run(() =>
        {
            using var client = listener.AcceptTcpClient();
            using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8);
            lines.Add(reader.ReadLine()!);
            var welcome = Encoding.UTF8.GetBytes("{\"t\":\"welcome\",\"protocol\":1}\n");
            stream.Write(welcome);
            lines.Add(reader.ReadLine()!);
        });
        var c = ViewerConnection.TryOpen(port, Protocol.Hello("run1", "Sample", "/work", null, 42), TimeSpan.FromSeconds(2));
        Assert.NotNull(c);
        Assert.True(c!.Send([Protocol.End()]));
        Assert.True(server.Wait(5000));
        c.Dispose();
        listener.Stop();
        var hello = JsonDocument.Parse(lines[0]).RootElement;
        Assert.Equal("hello", hello.GetProperty("t").GetString());
        Assert.Equal("run1", hello.GetProperty("run").GetString());
        Assert.Equal(1, hello.GetProperty("protocol").GetInt32());
        Assert.Equal("{\"t\":\"end\"}", lines[1]);
    }
}

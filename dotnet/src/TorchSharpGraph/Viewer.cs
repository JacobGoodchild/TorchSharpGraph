using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace TorchSharpGraph;

/// <summary>
/// Finds and talks to the viewer (the Rider tool window) over the loopback network interface only.
/// </summary>
internal static class ViewerDiscovery
{
    public const int DefaultPort = 47811;

    /// <summary>The ports to try, best first.</summary>
    public static List<int> CandidatePorts(int? configured, string workingDir, string? entryPath, string? homeOverride = null)
    {
        var result = new List<int>();
        if (configured is > 0 and < 65536) { result.Add(configured.Value); return result; }
        var env = Environment.GetEnvironmentVariable("TORCHSHARPGRAPH_PORT");
        if (int.TryParse(env, out var envPort) && envPort is > 0 and < 65536) { result.Add(envPort); return result; }

        // Prefer the IDE that has this program's folder open, then the most recently started one.
        var ides = ReadIdeFiles(homeOverride)
            .OrderByDescending(f => f.MatchScore(workingDir, entryPath))
            .ThenByDescending(f => f.Written);
        foreach (var ide in ides)
            if (!result.Contains(ide.Port)) result.Add(ide.Port);
        if (!result.Contains(DefaultPort)) result.Add(DefaultPort);
        return result;
    }

    internal sealed record IdeFile(int Port, int Pid, string[] Projects, DateTime Written)
    {
        public int MatchScore(string workingDir, string? entryPath)
        {
            int best = 0;
            foreach (var project in Projects)
            {
                if (string.IsNullOrEmpty(project)) continue;
                var root = Path.GetFullPath(project).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                foreach (var path in new[] { workingDir, entryPath })
                {
                    if (path == null) continue;
                    var full = Path.GetFullPath(path) + Path.DirectorySeparatorChar;
                    if (full.StartsWith(root, StringComparison.Ordinal)) best = Math.Max(best, root.Length);
                }
            }
            return best;
        }
    }

    /// <summary>The folder where each running IDE with the plugin writes its port: ~/.torchsharpgraph/ides.</summary>
    public static string IdeFolder(string? homeOverride = null) =>
        Path.Combine(homeOverride ?? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".torchsharpgraph", "ides");

    public static List<IdeFile> ReadIdeFiles(string? homeOverride = null)
    {
        var list = new List<IdeFile>();
        try
        {
            var dir = IdeFolder(homeOverride);
            if (!Directory.Exists(dir)) return list;
            foreach (var file in Directory.EnumerateFiles(dir, "*.json"))
            {
                try
                {
                    var info = new FileInfo(file);
                    if (info.Length > 1_000_000) continue;
                    using var doc = JsonDocument.Parse(File.ReadAllText(file));
                    var root = doc.RootElement;
                    var port = root.GetProperty("port").GetInt32();
                    var pid = root.TryGetProperty("pid", out var p) ? p.GetInt32() : 0;
                    if (pid > 0 && !IsRunning(pid)) continue;
                    var projects = root.TryGetProperty("projects", out var pr) && pr.ValueKind == JsonValueKind.Array
                        ? pr.EnumerateArray().Select(e => e.GetString() ?? "").ToArray()
                        : [];
                    list.Add(new IdeFile(port, pid, projects, info.LastWriteTimeUtc));
                }
                catch (Exception e) when (e is IOException or JsonException or KeyNotFoundException or InvalidOperationException or UnauthorizedAccessException or FormatException)
                {
                    // A half-written or foreign file: ignore it.
                }
            }
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
        }
        return list.OrderByDescending(f => f.Written).ToList();
    }

    private static bool IsRunning(int pid)
    {
        try
        {
            using var process = Process.GetProcessById(pid);
            return !process.HasExited;
        }
        catch (Exception)
        {
            return false;
        }
    }
}

/// <summary>A connection to the viewer. All calls happen on the sender thread.</summary>
internal sealed class ViewerConnection : IDisposable
{
    private readonly TcpClient _client;
    private readonly NetworkStream _stream;

    private ViewerConnection(TcpClient client)
    {
        _client = client;
        _stream = client.GetStream();
    }

    public int Port { get; private init; }

    /// <summary>Connects to 127.0.0.1:port and checks that a TorchSharpGraph viewer answers. Never throws.</summary>
    public static ViewerConnection? TryOpen(int port, byte[] hello, TimeSpan timeout)
    {
        TcpClient? client = null;
        try
        {
            client = new TcpClient(AddressFamily.InterNetwork) { NoDelay = true };
            using (var cts = new CancellationTokenSource(timeout))
            {
                client.ConnectAsync(IPAddress.Loopback, port, cts.Token).AsTask().Wait(cts.Token);
            }
            client.SendTimeout = 3000;
            client.ReceiveTimeout = (int)timeout.TotalMilliseconds;
            var stream = client.GetStream();
            stream.Write(hello);
            stream.Flush();
            // Wait for the welcome line, so data only ever goes to the viewer and never to some other program.
            var line = ReadLine(stream, 4096);
            if (line == null || !Protocol.IsWelcome(line)) { client.Dispose(); return null; }
            client.ReceiveTimeout = 0;
            return new ViewerConnection(client) { Port = port };
        }
        catch (Exception)
        {
            client?.Dispose();
            return null;
        }
    }

    private static string? ReadLine(NetworkStream stream, int max)
    {
        var bytes = new List<byte>();
        while (bytes.Count < max)
        {
            int b = stream.ReadByte();
            if (b < 0) return null;
            if (b == '\n') return Encoding.UTF8.GetString(bytes.ToArray());
            bytes.Add((byte)b);
        }
        return null;
    }

    /// <summary>Sends the messages. Returns false if the viewer went away.</summary>
    public bool Send(IReadOnlyList<byte[]> messages)
    {
        try
        {
            foreach (var m in messages) _stream.Write(m);
            _stream.Flush();
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }

    public void Dispose()
    {
        try { _client.Dispose(); } catch (Exception) { }
    }
}

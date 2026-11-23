using System.Diagnostics;
using System.Reflection;

namespace TorchSharpGraph;

/// <summary>
/// Holds every plot of this program and sends changes to the viewer from one background thread, at most
/// <see cref="PlotSettings.MaxUpdatesPerSecond"/> times a second. Plot calls only copy numbers and return.
/// </summary>
internal sealed class Hub
{
    private static readonly Lazy<Hub> LazyShared = new(() => new Hub(Plot.Settings, startThread: true));
    public static Hub Shared => LazyShared.Value;

    private readonly object _lock = new();
    private readonly List<PlotState> _plots = [];
    private readonly List<string> _removed = [];
    private bool _clearAll;
    private readonly PlotSettings _settings;
    private readonly string _runId = Guid.NewGuid().ToString("N");
    private readonly AutoResetEvent _wake = new(false);
    private readonly ManualResetEventSlim _firstAttemptDone = new(false);
    private ViewerConnection? _connection;
    private volatile bool _stopping;
    private DateTime _nextConnectAttempt = DateTime.MinValue;
    private Thread? _thread;
    private int _exitHandled;

    /// <summary>For tests: messages are handed here instead of to a socket when set.</summary>
    internal Func<IReadOnlyList<byte[]>, bool>? TestSink { get; set; }

    internal Hub(PlotSettings settings, bool startThread)
    {
        _settings = settings;
        if (startThread)
        {
            AppDomain.CurrentDomain.ProcessExit += (_, _) => OnExit();
            _thread = new Thread(Run) { IsBackground = true, Name = "TorchSharpGraph sender" };
            _thread.Start();
        }
    }

    public bool Connected => _connection != null;

    // ---- Changes, made on the caller's thread --------------------------------------------------------------

    public void Change(string plot, PlotKind kind, Action<PlotState> change)
    {
        lock (_lock)
        {
            var p = _plots.Find(x => x.Name == plot);
            if (p == null)
            {
                if (_plots.Count >= PlotSettings.MaxPlots)
                    throw new InvalidOperationException($"There are already {PlotSettings.MaxPlots} plots. Reuse a plot name, or call Plot.Clear() first.");
                p = new PlotState(plot, kind);
                _plots.Add(p);
                _removed.Remove(plot);
            }
            else if (p.Kind != kind && !(IsXYLike(p.Kind) && IsXYLike(kind)))
            {
                // A different kind of plot under the same name starts again from empty.
                p.Series.Clear(); p.Heatmap = null; p.Image = null;
                p.Kind = kind;
                p.MarkAllDirty();
            }
            else if (kind == PlotKind.Heatmap && p.Kind != PlotKind.Heatmap)
            {
                p.Kind = PlotKind.Heatmap; p.HeaderDirty = true;
            }
            change(p);
        }
        _wake.Set();
    }

    // A heatmap plot can also show points and lines on top (for example a path over a surface).
    private static bool IsXYLike(PlotKind k) => k is PlotKind.XY or PlotKind.Loss or PlotKind.Heatmap;

    public void Remove(string? plot)
    {
        lock (_lock)
        {
            if (plot == null) { _plots.Clear(); _removed.Clear(); _clearAll = true; }
            else if (_plots.RemoveAll(p => p.Name == plot) > 0) _removed.Add(plot);
        }
        _wake.Set();
    }

    public PlotState[] Snapshot()
    {
        lock (_lock) return _plots.ToArray();
    }

    public T Read<T>(Func<IReadOnlyList<PlotState>, T> read)
    {
        lock (_lock) return read(_plots);
    }

    // ---- Sending, on the sender thread --------------------------------------------------------------------

    /// <summary>Collects every change since the last call as messages, and marks them sent.</summary>
    internal List<byte[]> TakeMessages()
    {
        var messages = new List<byte[]>();
        lock (_lock)
        {
            if (_clearAll) { messages.Add(Protocol.ClearAll()); _clearAll = false; }
            foreach (var name in _removed) messages.Add(Protocol.Remove(name));
            _removed.Clear();
            foreach (var p in _plots)
            {
                if (p.HeaderDirty) { messages.Add(Protocol.Header(p)); p.HeaderDirty = false; }
                foreach (var s in p.Series)
                {
                    if (!s.Dirty) continue;
                    int start = s.Replaced ? 0 : s.SentCount;
                    messages.Add(Protocol.Series(p.Name, s, start));
                    s.Replaced = false;
                    s.SentCount = s.X.Count;
                }
                if (p.HeatmapDirty && p.Heatmap != null) messages.Add(Protocol.Heatmap(p.Name, p.Heatmap));
                p.HeatmapDirty = false;
                if (p.ImageDirty && p.Image != null) messages.Add(Protocol.Image(p.Name, p.Image));
                p.ImageDirty = false;
            }
        }
        return messages;
    }

    /// <summary>After a (re)connect the viewer gets everything again.</summary>
    internal void MarkEverythingUnsent()
    {
        lock (_lock)
        {
            _clearAll = true;
            _removed.Clear();
            foreach (var p in _plots) p.MarkAllDirty();
        }
    }

    internal bool HasUnsent()
    {
        lock (_lock) return _clearAll || _removed.Count > 0 || _plots.Any(p => p.Dirty);
    }

    private void Run()
    {
        while (!_stopping)
        {
            try
            {
                if (_connection == null && TestSink == null) TryConnect();
                _firstAttemptDone.Set();
                if (_connection != null || TestSink != null) SendPending();
            }
            catch (Exception e)
            {
                // Plotting must never take the program down.
                Debug.WriteLine("TorchSharpGraph: " + e.Message);
            }
            var interval = TimeSpan.FromSeconds(1.0 / Math.Clamp(_settings.MaxUpdatesPerSecond, 1, 120));
            _wake.WaitOne(TimeSpan.FromMilliseconds(500));
            // Let several quick changes collect into one update.
            Thread.Sleep(interval);
        }
    }

    internal void SendPending()
    {
        var messages = TakeMessages();
        if (messages.Count == 0) return;
        bool ok = TestSink?.Invoke(messages) ?? _connection?.Send(messages) ?? false;
        if (!ok && TestSink == null)
        {
            _connection?.Dispose();
            _connection = null;
            MarkEverythingUnsent();
        }
    }

    private void TryConnect()
    {
        if (!_settings.SendToViewer || DateTime.UtcNow < _nextConnectAttempt) return;
        _nextConnectAttempt = DateTime.UtcNow + TimeSpan.FromSeconds(2);
        var dir = Environment.CurrentDirectory;
        var exe = Assembly.GetEntryAssembly()?.Location;
        if (string.IsNullOrEmpty(exe)) exe = null;
        var program = Assembly.GetEntryAssembly()?.GetName().Name ?? Process.GetCurrentProcess().ProcessName;
        var hello = Protocol.Hello(_runId, program, dir, exe, Environment.ProcessId);
        foreach (var port in ViewerDiscovery.CandidatePorts(_settings.Port, dir, exe))
        {
            var c = ViewerConnection.TryOpen(port, hello, TimeSpan.FromMilliseconds(400));
            if (c == null) continue;
            _connection = c;
            MarkEverythingUnsent();
            return;
        }
    }

    /// <summary>Waits (up to the timeout) until everything has reached the viewer. False if it didn't.</summary>
    public bool Flush(TimeSpan timeout)
    {
        var until = DateTime.UtcNow + timeout;
        _firstAttemptDone.Wait(timeout);
        while (DateTime.UtcNow < until)
        {
            if (_connection == null && TestSink == null) return false;
            if (!HasUnsent()) return true;
            _wake.Set();
            Thread.Sleep(10);
        }
        return !HasUnsent();
    }

    private void OnExit()
    {
        if (Interlocked.Exchange(ref _exitHandled, 1) == 1) return;
        try
        {
            bool delivered = Flush(TimeSpan.FromSeconds(2));
            _stopping = true;
            if (delivered) _connection?.Send([Protocol.End()]);
            _connection?.Dispose();
            if (!delivered && _settings.SaveWhenNoViewer && Snapshot().Length > 0)
            {
                var folder = Path.GetFullPath(_settings.SaveFolder);
                var files = Read(list => PngWriter.SaveAll(list, folder, _settings.PngWidth, _settings.PngHeight));
                if (files.Count > 0)
                    Console.Error.WriteLine($"TorchSharpGraph: no plot viewer was listening, so {files.Count} plot(s) were saved to {folder}");
            }
        }
        catch (Exception e)
        {
            Console.Error.WriteLine("TorchSharpGraph: couldn't save the plots: " + e.Message);
        }
    }
}

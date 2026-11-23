using TorchSharp;
using static TorchSharp.torch;

namespace TorchSharpGraph;

/// <summary>
/// Live plots for your program. Each plot has a name; calling a method again with the same name updates that plot.
/// With the Rider plugin installed the plots show in Rider's Graphs window while the program runs. Without it they
/// are saved as PNG files when the program ends (see <see cref="Settings"/>).
/// </summary>
/// <example>
/// <code>
/// Plot.Scatter("data", xs, ys);
/// Plot.Function("data", x => Math.Sin(x), -3, 3, series: "sin");
/// for (int step = 0; step &lt; 100; step++) Plot.Loss(step, Math.Exp(-step / 20.0));
/// </code>
/// </example>
public static class Plot
{
    /// <summary>Settings for where plots go. Change them before the first plot call.</summary>
    public static PlotSettings Settings { get; } = new();

    internal static Hub Hub => TestHub ?? Hub.Shared;
    internal static Hub? TestHub { get; set; }

    // ---- Points and lines ------------------------------------------------------------------------------------

    /// <summary>Draws points at (x[i], y[i]). Calling it again with the same plot and series replaces the points.</summary>
    /// <param name="plot">The plot's name. Plots with different names are shown separately.</param>
    /// <param name="x">The x values: an array, a list or a 1-D tensor.</param>
    /// <param name="y">The y values, as many as there are x values.</param>
    /// <param name="series">Name of this set of points, shown in the legend when a plot has more than one.</param>
    public static void Scatter(string plot, Values x, Values y, string series = "points") =>
        SetXY(nameof(Scatter), plot, x, y, series, SeriesStyle.Scatter);

    /// <summary>Draws points from one argument: a list of y values (x is 0, 1, 2, ...) or an [N, 2] tensor of (x, y) pairs.</summary>
    public static void Scatter(string plot, Values points, string series = "points")
    {
        var (x, y) = Shapes.SplitPoints(nameof(Scatter), points);
        SetXY(nameof(Scatter), plot, x, y, series, SeriesStyle.Scatter);
    }

    /// <summary>Draws a line through (x[i], y[i]) in order. Calling it again with the same plot and series replaces the line.</summary>
    public static void Line(string plot, Values x, Values y, string series = "line") =>
        SetXY(nameof(Line), plot, x, y, series, SeriesStyle.Line);

    /// <summary>Draws a line from one argument: a list of y values (x is 0, 1, 2, ...) or an [N, 2] tensor of (x, y) pairs.</summary>
    public static void Line(string plot, Values points, string series = "line")
    {
        var (x, y) = Shapes.SplitPoints(nameof(Line), points);
        SetXY(nameof(Line), plot, x, y, series, SeriesStyle.Line);
    }

    /// <summary>
    /// Draws y = f(x) for x from <paramref name="from"/> to <paramref name="to"/>. Call it again (for example with a
    /// changed parameter inside f) to move the curve.
    /// </summary>
    /// <param name="plot">The plot's name.</param>
    /// <param name="f">The function, for example <c>x => a * x + b</c>.</param>
    /// <param name="from">Smallest x.</param>
    /// <param name="to">Largest x.</param>
    /// <param name="points">How many x values to work out f at (2 to 100,000).</param>
    /// <param name="series">Name of this curve in the legend.</param>
    public static void Function(string plot, Func<double, double> f, double from, double to, int points = 200, string series = "f(x)")
    {
        ArgumentNullException.ThrowIfNull(f);
        CheckRange(nameof(Function), "from", "to", from, to);
        if (points is < 2 or > 100_000)
            throw new ArgumentOutOfRangeException(nameof(points), points, "Plot.Function: points must be between 2 and 100,000.");
        var xs = new double[points];
        var ys = new double[points];
        for (int i = 0; i < points; i++)
        {
            xs[i] = from + (to - from) * i / (points - 1);
            ys[i] = f(xs[i]);
        }
        SetXY(nameof(Function), plot, new Values(xs, [points]), new Values(ys, [points]), series, SeriesStyle.Line);
    }

    // ---- Loss -----------------------------------------------------------------------------------------------

    /// <summary>Adds one point to the "Loss" plot: the loss at a training step. The plot has a log-scale switch.</summary>
    public static void Loss(long step, double value) => Loss("Loss", step, value);

    /// <summary>Adds one point to the "Loss" plot from a tensor holding a single number (for example the loss tensor).</summary>
    public static void Loss(long step, Tensor value) => Loss("Loss", step, value);

    /// <summary>Adds one point to a loss plot. Use a different <paramref name="series"/> for each curve (for example "train" and "test").</summary>
    public static void Loss(string plot, long step, double value, string series = "loss")
    {
        CheckName(plot, nameof(plot));
        CheckName(series, nameof(series));
        Hub.Change(plot, PlotKind.Loss, p =>
        {
            if (p.XLabel == null && p.Series.Count == 0) { p.XLabel = "step"; p.YLabel = "loss"; p.HeaderDirty = true; }
            var s = p.GetSeries(series, SeriesStyle.Line);
            s.Append(step, value);
        });
    }

    /// <summary>Adds one point to a loss plot from a tensor holding a single number.</summary>
    public static void Loss(string plot, long step, Tensor value, string series = "loss")
    {
        ArgumentNullException.ThrowIfNull(value);
        var v = Values.FromTensor(value);
        if (v.Count != 1)
            throw new ArgumentException($"Plot.Loss expects a single number, but the tensor has shape {v.ShapeText}. Use .mean() if you meant the average.", nameof(value));
        Loss(plot, step, v.Data[0], series);
    }

    // ---- Heatmaps -------------------------------------------------------------------------------------------

    /// <summary>
    /// Draws a grid of numbers as colours, for example a loss surface over two parameters.
    /// <c>values[row, column]</c>: columns go along x, rows go along y (the first row at the bottom).
    /// </summary>
    /// <param name="plot">The plot's name.</param>
    /// <param name="values">A 2-D array or a 2-D tensor.</param>
    /// <param name="xFrom">x at the left edge (default 0).</param>
    /// <param name="xTo">x at the right edge (default: the number of columns).</param>
    /// <param name="yFrom">y at the bottom edge (default 0).</param>
    /// <param name="yTo">y at the top edge (default: the number of rows).</param>
    /// <param name="contours">How many contour lines to draw over the colours (0 for none).</param>
    public static void Heatmap(string plot, Values values, double? xFrom = null, double? xTo = null,
        double? yFrom = null, double? yTo = null, int contours = 0)
    {
        CheckName(plot, nameof(plot));
        var (rows, cols) = Shapes.Grid(nameof(Heatmap), values);
        double x0 = xFrom ?? 0, x1 = xTo ?? cols, y0 = yFrom ?? 0, y1 = yTo ?? rows;
        CheckRange(nameof(Heatmap), "xFrom", "xTo", x0, x1);
        CheckRange(nameof(Heatmap), "yFrom", "yTo", y0, y1);
        if (contours is < 0 or > 50)
            throw new ArgumentOutOfRangeException(nameof(contours), contours, "Plot.Heatmap: contours must be between 0 and 50.");
        var state = new HeatmapState
        {
            Rows = rows, Cols = cols, Values = (double[])values.Data.Clone(),
            X0 = x0, X1 = x1, Y0 = y0, Y1 = y1, Contours = contours,
        };
        Hub.Change(plot, PlotKind.Heatmap, p => { p.Heatmap = state; p.HeatmapDirty = true; });
    }

    /// <summary>
    /// Draws f(x, y) as colours over a rectangle, for example <c>(a, b) => lossAt(a, b)</c>.
    /// </summary>
    /// <param name="plot">The plot's name.</param>
    /// <param name="f">The function of two numbers.</param>
    /// <param name="xFrom">Smallest x.</param>
    /// <param name="xTo">Largest x.</param>
    /// <param name="yFrom">Smallest y.</param>
    /// <param name="yTo">Largest y.</param>
    /// <param name="resolution">How many cells along each side (2 to 1,000).</param>
    /// <param name="contours">How many contour lines to draw over the colours (0 for none).</param>
    public static void Heatmap(string plot, Func<double, double, double> f, double xFrom, double xTo,
        double yFrom, double yTo, int resolution = 80, int contours = 0)
    {
        ArgumentNullException.ThrowIfNull(f);
        CheckRange(nameof(Heatmap), "xFrom", "xTo", xFrom, xTo);
        CheckRange(nameof(Heatmap), "yFrom", "yTo", yFrom, yTo);
        if (resolution is < 2 or > 1000)
            throw new ArgumentOutOfRangeException(nameof(resolution), resolution, "Plot.Heatmap: resolution must be between 2 and 1,000.");
        int n = resolution;
        var data = new double[n * n];
        for (int r = 0; r < n; r++)
        {
            // Each value is taken at the centre of its cell.
            double y = yFrom + (yTo - yFrom) * (r + 0.5) / n;
            for (int c = 0; c < n; c++)
            {
                double x = xFrom + (xTo - xFrom) * (c + 0.5) / n;
                data[r * n + c] = f(x, y);
            }
        }
        Heatmap(plot, new Values(data, [n, n]), xFrom, xTo, yFrom, yTo, contours);
    }

    // ---- Images ---------------------------------------------------------------------------------------------

    /// <summary>
    /// Shows a tensor or array as a picture. Shapes: [H, W] (grey), [C, H, W] or [H, W, C] with 1 or 3 channels,
    /// and [N, H, W] or [N, C, H, W] for a grid of N pictures (for example a batch of digits).
    /// Numbers from 0 to 1 (or 0 to 255) are used as they are; any other range is stretched to black..white.
    /// </summary>
    public static void Image(string plot, Values image)
    {
        CheckName(plot, nameof(plot));
        var state = Images.ToImage(image);
        Hub.Change(plot, PlotKind.Image, p => { p.Image = state; p.ImageDirty = true; });
    }

    // ---- Labels and options ---------------------------------------------------------------------------------

    /// <summary>Sets a plot's title and axis labels. Leave an argument out (null) to keep what it was.</summary>
    public static void Labels(string plot, string? title = null, string? x = null, string? y = null)
    {
        CheckName(plot, nameof(plot));
        Hub.Change(plot, KindOf(plot), p =>
        {
            if (title != null) p.Title = title;
            if (x != null) p.XLabel = x;
            if (y != null) p.YLabel = y;
            p.HeaderDirty = true;
        });
    }

    /// <summary>Starts a plot with its y axis on a log scale (it can still be switched in Rider).</summary>
    public static void LogScale(string plot, bool logY = true)
    {
        CheckName(plot, nameof(plot));
        Hub.Change(plot, KindOf(plot), p => { p.LogY = logY; p.HeaderDirty = true; });
    }

    /// <summary>Removes one plot, or every plot when <paramref name="plot"/> is null.</summary>
    public static void Clear(string? plot = null) => Hub.Remove(plot);

    /// <summary>Saves one plot as a PNG file now. Works with or without Rider.</summary>
    public static void SavePng(string plot, string path, int width = 800, int height = 600)
    {
        CheckName(plot, nameof(plot));
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        bool found = Hub.Read(list =>
        {
            var state = list.FirstOrDefault(p => p.Name == plot);
            if (state != null) PngWriter.Save(state, path, width, height);
            return state != null;
        });
        if (!found) throw new ArgumentException($"There is no plot called \"{plot}\".", nameof(plot));
    }

    /// <summary>Saves every plot as a PNG file in a folder (created if needed). Returns the files written.</summary>
    public static IReadOnlyList<string> SaveAllPng(string folder, int width = 800, int height = 600)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(folder);
        return Hub.Read(list => PngWriter.SaveAll(list, folder, width, height));
    }

    /// <summary>
    /// Waits until every plot has reached Rider, at most <paramref name="timeout"/> (default 2 seconds).
    /// Returns false if no viewer is connected. You don't normally need this: it also happens when the program ends.
    /// </summary>
    public static bool Flush(TimeSpan? timeout = null) => Hub.Flush(timeout ?? TimeSpan.FromSeconds(2));

    /// <summary>True while connected to Rider's Graphs window.</summary>
    public static bool ViewerConnected => Hub.Connected;

    // ---- Helpers ------------------------------------------------------------------------------------------

    private static PlotKind KindOf(string plot) =>
        Hub.Read(list => list.FirstOrDefault(p => p.Name == plot)?.Kind ?? PlotKind.XY);

    private static void SetXY(string method, string plot, Values x, Values y, string series, SeriesStyle style)
    {
        CheckName(plot, nameof(plot));
        CheckName(series, nameof(series));
        var xs = Shapes.Vector(method, "x", x);
        var ys = Shapes.Vector(method, "y", y);
        if (xs.Length != ys.Length)
            throw new ArgumentException($"Plot.{method}: x has {xs.Length} values but y has {ys.Length}. They must have the same number.");
        if (xs.Length > PlotSettings.MaxPointsPerSeries)
            throw new ArgumentException($"Plot.{method}: {xs.Length:N0} points is more than the {PlotSettings.MaxPointsPerSeries:N0} a series can hold.");
        Hub.Change(plot, PlotKind.XY, p => p.GetSeries(series, style).Replace(xs, ys));
    }

    internal static void CheckName(string name, string parameter)
    {
        if (string.IsNullOrWhiteSpace(name))
            throw new ArgumentException("A plot or series name can't be empty.", parameter);
        if (name.Length > 200)
            throw new ArgumentException("A plot or series name can be at most 200 characters.", parameter);
    }

    private static void CheckRange(string method, string fromName, string toName, double from, double to)
    {
        if (!double.IsFinite(from) || !double.IsFinite(to))
            throw new ArgumentException($"Plot.{method}: {fromName} and {toName} must be ordinary numbers (not NaN or infinity).");
        if (from >= to)
            throw new ArgumentException($"Plot.{method}: {fromName} ({from}) must be smaller than {toName} ({to}).");
    }
}

/// <summary>Where plots go. Change these before the first plot call.</summary>
public sealed class PlotSettings
{
    internal const int MaxPlots = 64;
    internal const int MaxPointsPerSeries = 5_000_000;

    /// <summary>Send plots to Rider's Graphs window (default true). Set false to only save PNG files.</summary>
    public bool SendToViewer { get; set; } = true;

    /// <summary>When the program ends and no viewer got the plots, save them as PNG files (default true).</summary>
    public bool SaveWhenNoViewer { get; set; } = true;

    /// <summary>The folder for those PNG files (default "plots", next to where the program runs).</summary>
    public string SaveFolder { get; set; } = "plots";

    /// <summary>Size of saved PNG files in pixels.</summary>
    public int PngWidth { get; set; } = 800;

    /// <summary>Size of saved PNG files in pixels.</summary>
    public int PngHeight { get; set; } = 600;

    /// <summary>
    /// The port Rider listens on. Leave null to find it automatically (the TORCHSHARPGRAPH_PORT environment
    /// variable, then the running IDEs, then 47811). Only the local loopback address 127.0.0.1 is ever used.
    /// </summary>
    public int? Port { get; set; }

    /// <summary>Most updates sent to the viewer per second (default 30). Calls in between are combined.</summary>
    public int MaxUpdatesPerSecond { get; set; } = 30;
}

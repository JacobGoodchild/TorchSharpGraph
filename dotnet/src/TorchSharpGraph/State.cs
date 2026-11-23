namespace TorchSharpGraph;

internal enum PlotKind { XY, Loss, Heatmap, Image }

internal enum SeriesStyle { Scatter, Line }

/// <summary>One named set of points inside a plot (for example "data" or "model").</summary>
internal sealed class SeriesState(string name, SeriesStyle style)
{
    public string Name { get; } = name;
    public SeriesStyle Style { get; set; } = style;
    public List<double> X { get; } = [];
    public List<double> Y { get; } = [];

    /// <summary>How many points the viewer already has. Points after this are sent as an append.</summary>
    public int SentCount { get; set; }

    /// <summary>True when the points were replaced since the last send (so the viewer must replace, not append).</summary>
    public bool Replaced { get; set; } = true;

    public bool Dirty => Replaced || SentCount < X.Count;

    public void Replace(double[] x, double[] y)
    {
        X.Clear(); Y.Clear();
        X.AddRange(x); Y.AddRange(y);
        Replaced = true;
    }

    public void Append(double x, double y)
    {
        X.Add(x); Y.Add(y);
    }
}

internal sealed class HeatmapState
{
    public required int Rows { get; init; }
    public required int Cols { get; init; }
    /// <summary>Row-major values; row 0 is at the bottom (y = Y0).</summary>
    public required double[] Values { get; init; }
    public required double X0 { get; init; }
    public required double X1 { get; init; }
    public required double Y0 { get; init; }
    public required double Y1 { get; init; }
    public required int Contours { get; init; }
}

internal sealed class ImageState
{
    public required int Width { get; init; }
    public required int Height { get; init; }
    /// <summary>RGBA bytes, row 0 at the top.</summary>
    public required byte[] Rgba { get; init; }
    /// <summary>Each pixel's original number (the first channel for colour images), for reading values on hover.</summary>
    public required double[] Raw { get; init; }
    public required int Count { get; init; }
}

internal sealed class PlotState(string name, PlotKind kind)
{
    public string Name { get; } = name;
    public PlotKind Kind { get; set; } = kind;
    public string? Title { get; set; }
    public string? XLabel { get; set; }
    public string? YLabel { get; set; }
    public bool? LogY { get; set; }
    public List<SeriesState> Series { get; } = [];
    public HeatmapState? Heatmap { get; set; }
    public ImageState? Image { get; set; }

    public bool HeaderDirty { get; set; } = true;
    public bool HeatmapDirty { get; set; }
    public bool ImageDirty { get; set; }

    public SeriesState GetSeries(string name, SeriesStyle style)
    {
        foreach (var s in Series)
            if (s.Name == name) { s.Style = style; return s; }
        var created = new SeriesState(name, style);
        Series.Add(created);
        return created;
    }

    public void MarkAllDirty()
    {
        HeaderDirty = true;
        HeatmapDirty = Heatmap != null;
        ImageDirty = Image != null;
        foreach (var s in Series) { s.Replaced = true; s.SentCount = 0; }
    }

    public bool Dirty => HeaderDirty || HeatmapDirty || ImageDirty || Series.Any(s => s.Dirty);
}

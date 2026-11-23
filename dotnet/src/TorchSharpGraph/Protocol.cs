using System.Text;
using System.Text.Json;

namespace TorchSharpGraph;

/// <summary>
/// The messages sent to the viewer: one JSON object per line (UTF-8). See docs/development/PROTOCOL.md.
/// </summary>
internal static class Protocol
{
    public const int Version = 1;

    public static byte[] Hello(string runId, string program, string directory, string? entryPath, int pid) =>
        Write(w =>
        {
            w.WriteString("t", "hello");
            w.WriteNumber("protocol", Version);
            w.WriteString("run", runId);
            w.WriteString("program", program);
            w.WriteString("dir", directory);
            if (entryPath != null) w.WriteString("exe", entryPath);
            w.WriteNumber("pid", pid);
        });

    public static byte[] ClearAll() => Write(w => w.WriteString("t", "clear"));

    public static byte[] Remove(string plot) => Write(w =>
    {
        w.WriteString("t", "clear");
        w.WriteString("plot", plot);
    });

    public static byte[] End() => Write(w => w.WriteString("t", "end"));

    public static byte[] Header(PlotState p) => Write(w =>
    {
        w.WriteString("t", "plot");
        w.WriteString("plot", p.Name);
        w.WriteString("kind", p.Kind switch
        {
            PlotKind.Loss => "loss",
            PlotKind.Heatmap => "heatmap",
            PlotKind.Image => "image",
            _ => "xy",
        });
        if (p.Title != null) w.WriteString("title", p.Title);
        if (p.XLabel != null) w.WriteString("x", p.XLabel);
        if (p.YLabel != null) w.WriteString("y", p.YLabel);
        if (p.LogY != null) w.WriteBoolean("logY", p.LogY.Value);
    });

    /// <summary>Points from <paramref name="start"/> on. A start of 0 replaces the series in the viewer.</summary>
    public static byte[] Series(string plot, SeriesState s, int start) => Write(w =>
    {
        w.WriteString("t", "series");
        w.WriteString("plot", plot);
        w.WriteString("series", s.Name);
        w.WriteString("style", s.Style == SeriesStyle.Scatter ? "scatter" : "line");
        w.WriteNumber("start", start);
        WriteNumbers(w, "x", s.X, start);
        WriteNumbers(w, "y", s.Y, start);
    });

    public static byte[] Heatmap(string plot, HeatmapState h) => Write(w =>
    {
        w.WriteString("t", "heatmap");
        w.WriteString("plot", plot);
        w.WriteNumber("rows", h.Rows);
        w.WriteNumber("cols", h.Cols);
        WriteNumber(w, "x0", h.X0);
        WriteNumber(w, "x1", h.X1);
        WriteNumber(w, "y0", h.Y0);
        WriteNumber(w, "y1", h.Y1);
        w.WriteNumber("contours", h.Contours);
        WriteNumbers(w, "v", h.Values, 0);
    });

    public static byte[] Image(string plot, ImageState i) => Write(w =>
    {
        w.WriteString("t", "image");
        w.WriteString("plot", plot);
        w.WriteNumber("w", i.Width);
        w.WriteNumber("h", i.Height);
        w.WriteNumber("count", i.Count);
        w.WriteString("rgba", Convert.ToBase64String(i.Rgba));
        WriteNumbers(w, "raw", i.Raw, 0);
    });

    private static void WriteNumber(Utf8JsonWriter w, string name, double v)
    {
        if (double.IsFinite(v)) w.WriteNumber(name, v); else w.WriteNull(name);
    }

    private static void WriteNumbers(Utf8JsonWriter w, string name, IReadOnlyList<double> values, int start)
    {
        w.WriteStartArray(name);
        for (int i = start; i < values.Count; i++)
        {
            var v = values[i];
            // JSON has no NaN or infinity: a missing value is null, and shows as a gap.
            if (double.IsFinite(v)) w.WriteNumberValue(v); else w.WriteNullValue();
        }
        w.WriteEndArray();
    }

    private static readonly JsonWriterOptions Options = new() { Indented = false, SkipValidation = true };

    private static byte[] Write(Action<Utf8JsonWriter> body)
    {
        var buffer = new MemoryStream();
        using (var w = new Utf8JsonWriter(buffer, Options))
        {
            w.WriteStartObject();
            body(w);
            w.WriteEndObject();
        }
        buffer.WriteByte((byte)'\n');
        return buffer.ToArray();
    }

    /// <summary>The viewer's reply to hello: <c>{"t":"welcome","protocol":1}</c>.</summary>
    public static bool IsWelcome(string line)
    {
        try
        {
            using var doc = JsonDocument.Parse(line);
            return doc.RootElement.TryGetProperty("t", out var t) && t.GetString() == "welcome";
        }
        catch (JsonException)
        {
            return false;
        }
    }

    internal static string Text(byte[] message) => Encoding.UTF8.GetString(message).TrimEnd('\n');
}

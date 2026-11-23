using System.Runtime.InteropServices;
using SkiaSharp;

namespace TorchSharpGraph;

/// <summary>Saves plots as PNG files (used when no viewer is listening). Drawing is done by ScottPlot (MIT).</summary>
internal static class PngWriter
{
    public static List<string> SaveAll(IEnumerable<PlotState> plots, string folder, int width, int height)
    {
        Directory.CreateDirectory(folder);
        var written = new List<string>();
        var used = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var p in plots)
        {
            var name = SafeFileName(p.Name);
            var unique = name;
            for (int i = 2; !used.Add(unique); i++) unique = $"{name}-{i}";
            var path = Path.Combine(folder, unique + ".png");
            Save(p, path, width, height);
            written.Add(path);
        }
        return written;
    }

    internal static string SafeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars().Concat(['/', '\\', ':', '*', '?', '"', '<', '>', '|']).ToHashSet();
        var chars = name.Trim().Select(c => invalid.Contains(c) || char.IsControl(c) ? '_' : c).ToArray();
        var s = new string(chars).Trim('.', ' ');
        if (s.Length == 0) s = "plot";
        return s.Length > 80 ? s[..80] : s;
    }

    public static void Save(PlotState p, string path, int width, int height)
    {
        width = Math.Clamp(width, 50, 8000);
        height = Math.Clamp(height, 50, 8000);
        var dir = Path.GetDirectoryName(Path.GetFullPath(path));
        if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
        if (p.Kind == PlotKind.Image && p.Image != null) SaveImage(p, p.Image, path, width, height);
        else SaveChart(p, path, width, height);
    }

    private static void SaveChart(PlotState p, string path, int width, int height)
    {
        var plt = new ScottPlot.Plot();
        bool logY = p.LogY ?? false;
        if (p.Heatmap is { } h)
        {
            // ScottPlot draws the first row at the top; ours is at the bottom.
            var grid = new double[h.Rows, h.Cols];
            for (int r = 0; r < h.Rows; r++)
                for (int c = 0; c < h.Cols; c++)
                    grid[h.Rows - 1 - r, c] = h.Values[r * h.Cols + c];
            var hm = plt.Add.Heatmap(grid);
            hm.Colormap = new ScottPlot.Colormaps.Viridis();
            hm.Extent = new ScottPlot.CoordinateRect(h.X0, h.X1, h.Y0, h.Y1);
            plt.Add.ColorBar(hm);
        }
        var palette = new ScottPlot.Palettes.Category10();
        int index = 0;
        foreach (var s in p.Series)
        {
            var xs = new List<double>(s.X.Count);
            var ys = new List<double>(s.Y.Count);
            for (int i = 0; i < s.X.Count; i++)
            {
                double x = s.X[i], y = s.Y[i];
                if (logY) y = y > 0 ? Math.Log10(y) : double.NaN;
                if (!double.IsFinite(x) || !double.IsFinite(y)) continue;
                xs.Add(x); ys.Add(y);
            }
            var color = palette.GetColor(index++);
            if (xs.Count == 0) continue;
            if (s.Style == SeriesStyle.Scatter)
            {
                var sp = plt.Add.ScatterPoints(xs.ToArray(), ys.ToArray(), color);
                sp.MarkerSize = 6;
                if (p.Series.Count > 1) sp.LegendText = s.Name;
            }
            else
            {
                var sl = plt.Add.ScatterLine(xs.ToArray(), ys.ToArray(), p.Heatmap != null ? ScottPlot.Colors.Red : color);
                sl.LineWidth = 2;
                if (p.Series.Count > 1) sl.LegendText = s.Name;
            }
        }
        if (logY)
        {
            var ticks = new ScottPlot.TickGenerators.NumericAutomatic
            {
                MinorTickGenerator = new ScottPlot.TickGenerators.LogMinorTickGenerator(),
                IntegerTicksOnly = true,
                LabelFormatter = y => Math.Pow(10, y).ToString("g3", System.Globalization.CultureInfo.InvariantCulture),
            };
            plt.Axes.Left.TickGenerator = ticks;
        }
        plt.Title(p.Title ?? p.Name);
        if (p.XLabel != null) plt.XLabel(p.XLabel);
        if (p.YLabel != null) plt.YLabel(logY ? p.YLabel + " (log scale)" : p.YLabel);
        if (p.Series.Count > 1) plt.ShowLegend();
        plt.SavePng(path, width, height);
    }

    private static void SaveImage(PlotState p, ImageState img, string path, int width, int height)
    {
        using var source = new SKBitmap(new SKImageInfo(img.Width, img.Height, SKColorType.Rgba8888, SKAlphaType.Unpremul));
        Marshal.Copy(img.Rgba, 0, source.GetPixels(), img.Rgba.Length);
        using var sourceImage = SKImage.FromBitmap(source);

        using var surface = SKSurface.Create(new SKImageInfo(width, height, SKColorType.Rgba8888, SKAlphaType.Premul));
        var canvas = surface.Canvas;
        canvas.Clear(SKColors.White);
        const float titleSpace = 36, margin = 12;
        using (var font = new SKFont(SKTypeface.Default, 18))
        using (var paint = new SKPaint { Color = SKColors.Black, IsAntialias = true })
        {
            canvas.DrawText(p.Title ?? p.Name, width / 2f, 24, SKTextAlign.Center, font, paint);
        }
        // Largest whole-pixel-sharp fit: each image pixel becomes a square block.
        float availW = width - 2 * margin, availH = height - titleSpace - margin;
        float scale = Math.Min(availW / img.Width, availH / img.Height);
        if (scale >= 1) scale = MathF.Floor(scale);
        float w = img.Width * scale, h = img.Height * scale;
        var dest = SKRect.Create((width - w) / 2, titleSpace + (availH - h) / 2, w, h);
        canvas.DrawImage(sourceImage, dest, new SKSamplingOptions(SKFilterMode.Nearest, SKMipmapMode.None));
        using var snapshot = surface.Snapshot();
        using var data = snapshot.Encode(SKEncodedImageFormat.Png, 100);
        using var file = File.Create(path);
        data.SaveTo(file);
    }
}

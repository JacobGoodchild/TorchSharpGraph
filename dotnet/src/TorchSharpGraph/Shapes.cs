namespace TorchSharpGraph;

/// <summary>Checks the shapes of what is passed in and explains clearly when one is wrong.</summary>
internal static class Shapes
{
    /// <summary>The sizes that aren't 1 (so [100, 1] and [1, 100] both count as a list of 100).</summary>
    private static long[] Squeezed(Values v) => v.Shape.Where(d => d != 1).ToArray();

    public static double[] Vector(string method, string name, Values v)
    {
        if (v.IsNull) throw new ArgumentNullException(name, $"Plot.{method}: {name} is null.");
        if (Squeezed(v).Length > 1)
            throw new ArgumentException(
                $"Plot.{method}: {name} should be a list of numbers, but it has shape {v.ShapeText}. " +
                $"Pass a single row or column, for example {name}[.., 0] or {name}.flatten().", name);
        return v.Data;
    }

    /// <summary>One argument: y values (x = 0, 1, 2, ...) or [N, 2] pairs.</summary>
    public static (Values X, Values Y) SplitPoints(string method, Values points)
    {
        if (points.IsNull) throw new ArgumentNullException(nameof(points), $"Plot.{method}: points is null.");
        var squeezed = Squeezed(points);
        if (squeezed.Length <= 1)
        {
            var n = points.Data.Length;
            var xs = new double[n];
            for (int i = 0; i < n; i++) xs[i] = i;
            return (new Values(xs, [n]), points);
        }
        if (points.Shape.Length == 2 && points.Shape[1] == 2)
        {
            var n = (int)points.Shape[0];
            var xs = new double[n];
            var ys = new double[n];
            for (int i = 0; i < n; i++) { xs[i] = points.Data[2 * i]; ys[i] = points.Data[2 * i + 1]; }
            return (new Values(xs, [n]), new Values(ys, [n]));
        }
        throw new ArgumentException(
            $"Plot.{method}: with one argument, pass a list of y values or an [N, 2] tensor of (x, y) pairs. " +
            $"This one has shape {points.ShapeText}. To pass x and y separately use Plot.{method}(name, x, y).", nameof(points));
    }

    /// <summary>A 2-D grid: [rows, cols], ignoring extra sizes of 1 (so [1, 50, 50] works).</summary>
    public static (int Rows, int Cols) Grid(string method, Values v)
    {
        if (v.IsNull) throw new ArgumentNullException(nameof(v), $"Plot.{method}: values is null.");
        var dims = v.Shape.Length == 2 ? v.Shape : Squeezed(v);
        if (dims.Length != 2)
            throw new ArgumentException(
                $"Plot.{method}: values should be a 2-D grid like [rows, columns], but it has shape {v.ShapeText}.", "values");
        if (dims[0] < 1 || dims[1] < 1)
            throw new ArgumentException($"Plot.{method}: the grid is empty (shape {v.ShapeText}).", "values");
        if (dims[0] * dims[1] > 4_000_000)
            throw new ArgumentException($"Plot.{method}: a grid of {dims[0]} x {dims[1]} is too big (the limit is 4 million cells).", "values");
        return ((int)dims[0], (int)dims[1]);
    }
}

/// <summary>Turns numbers into RGBA pictures.</summary>
internal static class Images
{
    private const int MaxPixels = 16_000_000;

    public static ImageState ToImage(Values v)
    {
        if (v.IsNull) throw new ArgumentNullException(nameof(v), "Plot.Image: image is null.");
        var s = v.Shape;
        // (count, channels, height, width, channels-last)
        (int n, int c, int h, int w, bool last) layout = s.Length switch
        {
            2 => (1, 1, (int)s[0], (int)s[1], false),
            3 when s[0] is 1 or 3 => (1, (int)s[0], (int)s[1], (int)s[2], false),
            3 when s[2] is 1 or 3 or 4 => (1, (int)s[2], (int)s[0], (int)s[1], true),
            3 => ((int)s[0], 1, (int)s[1], (int)s[2], false),
            4 when s[1] is 1 or 3 => ((int)s[0], (int)s[1], (int)s[2], (int)s[3], false),
            4 when s[3] is 1 or 3 or 4 => ((int)s[0], (int)s[3], (int)s[1], (int)s[2], true),
            _ => throw new ArgumentException(
                $"Plot.Image: can't show shape {v.ShapeText} as a picture. Use [H, W], [C, H, W] or [H, W, C] with 1 or 3 " +
                "channels, or [N, H, W] / [N, C, H, W] for a grid of pictures.", "image"),
        };
        var (count, channels, height, width, channelsLast) = layout;
        if (count < 1 || height < 1 || width < 1)
            throw new ArgumentException($"Plot.Image: the picture is empty (shape {v.ShapeText}).", "image");
        if ((long)count * height * width > MaxPixels)
            throw new ArgumentException($"Plot.Image: shape {v.ShapeText} has too many pixels (the limit is 16 million).", "image");

        // Lay N pictures out in a grid with a 1-pixel gap between them.
        int cols = (int)Math.Ceiling(Math.Sqrt(count));
        int rows = (count + cols - 1) / cols;
        int gap = count > 1 ? 1 : 0;
        int outW = cols * width + (cols - 1) * gap;
        int outH = rows * height + (rows - 1) * gap;

        var (scale, offset) = Range(v.Data);
        var rgba = new byte[outW * outH * 4];
        var raw = new double[outW * outH];
        Array.Fill(raw, double.NaN);
        int plane = height * width;
        for (int k = 0; k < count; k++)
        {
            int ox = (k % cols) * (width + gap), oy = (k / cols) * (height + gap);
            for (int y = 0; y < height; y++)
                for (int x = 0; x < width; x++)
                {
                    double Channel(int ch) => channelsLast
                        ? v.Data[((k * height + y) * width + x) * channels + ch]
                        : v.Data[(k * channels + ch) * plane + y * width + x];
                    double r = Channel(0);
                    double g = channels >= 3 ? Channel(1) : r;
                    double b = channels >= 3 ? Channel(2) : r;
                    double a = channels == 4 ? Channel(3) : double.NaN;
                    int o = (oy + y) * outW + ox + x;
                    rgba[o * 4] = ToByte(r, scale, offset);
                    rgba[o * 4 + 1] = ToByte(g, scale, offset);
                    rgba[o * 4 + 2] = ToByte(b, scale, offset);
                    rgba[o * 4 + 3] = double.IsNaN(a) ? (byte)255 : ToByte(a, scale, offset);
                    raw[o] = r;
                }
        }
        return new ImageState { Width = outW, Height = outH, Rgba = rgba, Raw = raw, Count = count };
    }

    /// <summary>0..1 and 0..255 are used as they are; anything else is stretched from its smallest to largest value.</summary>
    internal static (double Scale, double Offset) Range(double[] data)
    {
        double min = double.PositiveInfinity, max = double.NegativeInfinity;
        foreach (var d in data)
        {
            if (!double.IsFinite(d)) continue;
            if (d < min) min = d;
            if (d > max) max = d;
        }
        if (min > max) return (255, 0);
        if (min >= 0 && max <= 1) return (255, 0);
        if (min >= 0 && max <= 255) return (1, 0);
        if (max - min < 1e-300) return (0, 128);
        return (255 / (max - min), -min * 255 / (max - min));
    }

    private static byte ToByte(double value, double scale, double offset)
    {
        if (!double.IsFinite(value)) return 0;
        var v = value * scale + offset;
        return (byte)Math.Clamp(Math.Round(v), 0, 255);
    }
}

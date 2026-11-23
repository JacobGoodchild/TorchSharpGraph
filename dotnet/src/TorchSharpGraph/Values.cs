using TorchSharp;
using static TorchSharp.torch;

namespace TorchSharpGraph;

/// <summary>
/// A list of numbers with a shape. You never create one yourself: arrays, lists and TorchSharp tensors turn into
/// <see cref="Values"/> automatically when you pass them to a <see cref="Plot"/> method.
/// </summary>
public readonly struct Values
{
    internal double[] Data { get; }
    internal long[] Shape { get; }

    internal Values(double[] data, long[] shape)
    {
        Data = data;
        Shape = shape;
    }

    /// <summary>The number of values.</summary>
    public int Count => Data?.Length ?? 0;

    /// <summary>Numbers from any sequence of doubles (for example a LINQ query).</summary>
    public static Values From(IEnumerable<double> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var data = values.ToArray();
        return new Values(data, [data.Length]);
    }

    /// <summary>Numbers from any sequence of floats.</summary>
    public static Values From(IEnumerable<float> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var data = values.Select(v => (double)v).ToArray();
        return new Values(data, [data.Length]);
    }

    /// <summary>Numbers from any sequence of integers.</summary>
    public static Values From(IEnumerable<int> values)
    {
        ArgumentNullException.ThrowIfNull(values);
        var data = values.Select(v => (double)v).ToArray();
        return new Values(data, [data.Length]);
    }

#pragma warning disable CS1591 // The conversions are self-explanatory.
    public static implicit operator Values(double[] values) => From(values);
    public static implicit operator Values(float[] values) => From(values);
    public static implicit operator Values(int[] values) => From(values);
    public static implicit operator Values(long[] values) => From((values ?? throw new ArgumentNullException(nameof(values))).Select(v => (double)v));
    public static implicit operator Values(List<double> values) => From(values);
    public static implicit operator Values(List<float> values) => From(values);
    public static implicit operator Values(List<int> values) => From(values);
    public static implicit operator Values(double[,] values) => FromGrid(values);
    public static implicit operator Values(float[,] values) => FromGrid(values);
    public static implicit operator Values(Tensor tensor) => FromTensor(tensor);
#pragma warning restore CS1591

    internal static Values FromGrid(double[,] values)
    {
        ArgumentNullException.ThrowIfNull(values);
        int rows = values.GetLength(0), cols = values.GetLength(1);
        var data = new double[rows * cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                data[r * cols + c] = values[r, c];
        return new Values(data, [rows, cols]);
    }

    internal static Values FromGrid(float[,] values)
    {
        ArgumentNullException.ThrowIfNull(values);
        int rows = values.GetLength(0), cols = values.GetLength(1);
        var data = new double[rows * cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                data[r * cols + c] = values[r, c];
        return new Values(data, [rows, cols]);
    }

    internal static Values FromTensor(Tensor tensor)
    {
        ArgumentNullException.ThrowIfNull(tensor);
        if (tensor.IsInvalid)
            throw new ArgumentException("This tensor has already been disposed, so it can't be plotted.", nameof(tensor));
        if (tensor.is_complex())
            throw new ArgumentException("Complex tensors can't be plotted. Plot the real part (tensor.real) or the size (tensor.abs()) instead.", nameof(tensor));
        var shape = tensor.shape.ToArray();
        // Copy to plain doubles on the CPU without touching autograd. The temporaries are freed straight away.
        using var noGrad = torch.no_grad();
        using var detached = tensor.detach();
        using var cpu = detached.cpu();
        using var asDouble = cpu.to_type(ScalarType.Float64);
        using var contiguous = asDouble.contiguous();
        var data = contiguous.data<double>().ToArray();
        return new Values(data, shape);
    }

    /// <summary>Shape as text, like "[3, 28, 28]".</summary>
    internal string ShapeText => "[" + string.Join(", ", Shape) + "]";

    internal bool IsNull => Data is null;
}

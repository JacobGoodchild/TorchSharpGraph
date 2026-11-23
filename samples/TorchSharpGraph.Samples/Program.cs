// Shows every kind of plot TorchSharpGraph can draw, using made-up data (no model, no training).
// Run it from Rider with the Graphs for TorchSharp plugin installed and watch the Graphs window,
// or run it anywhere else and look in the "plots" folder for PNG files when it ends.
//
//   dotnet run                 all demos, animated (about 20 seconds)
//   dotnet run -- quick        all demos at once, no waiting
//   dotnet run -- points | wave | loss | surface | images

using TorchSharp;
using TorchSharpGraph;
using static TorchSharp.torch;

var demo = args.FirstOrDefault()?.ToLowerInvariant() ?? "all";
bool quick = demo == "quick";
bool Run(string name) => demo is "all" or "quick" || demo == name;
void Pause(int ms) { if (!quick) Thread.Sleep(ms); }

var random = new Random(1);

if (Run("points"))
{
    // A random cloud of points around a gentle curve, from plain arrays.
    var xs = Enumerable.Range(0, 80).Select(_ => random.NextDouble() * 10).ToArray();
    var ys = xs.Select(x => 0.5 * x + Math.Sin(x) + (random.NextDouble() - 0.5) * 1.5).ToArray();
    Plot.Scatter("points", xs, ys, series: "data");
    Plot.Function("points", x => 0.5 * x + Math.Sin(x), 0, 10, series: "curve");
    Plot.Labels("points", title: "Points and a curve", x: "x", y: "y");

    // The same kind of plot from tensors: a 2-D cloud as an [N, 2] tensor.
    using var cloud = torch.randn(300, 2) * torch.tensor(new[] { 1.0f, 0.4f });
    Plot.Scatter("tensor cloud", cloud, series: "randn");
}

if (Run("wave"))
{
    // A function whose parameter changes over time: the curve slides along.
    for (int frame = 0; frame <= (quick ? 0 : 120); frame++)
    {
        double phase = frame * 0.05;
        Plot.Function("wave", x => Math.Sin(x - phase), 0, 4 * Math.PI, series: "sin(x - t)");
        Plot.Function("wave", x => 0.5 * Math.Cos(2 * x + phase), 0, 4 * Math.PI, series: "0.5 cos(2x + t)");
        Pause(30);
    }
    Plot.Labels("wave", title: "A moving wave", x: "x", y: "height");
}

if (Run("loss"))
{
    // A made-up curve that goes down with some noise, fed one step at a time like a loss.
    double value = 2.0;
    for (int step = 0; step <= 600; step++)
    {
        value = 0.05 + 1.95 * Math.Exp(-step / 90.0) * (1 + 0.15 * (random.NextDouble() - 0.5));
        Plot.Loss(step, value);
        if (step % 20 == 0) Plot.Loss("Loss", step, value * 1.15 + 0.02, series: "check");
        if (step % 10 == 0) Pause(15);
    }
}

if (Run("surface"))
{
    // A surface over two numbers, drawn as colours with contour lines, and a dot circling over it.
    static double Surface(double a, double b) => Math.Sin(a) * Math.Cos(b) + 0.05 * (a * a + b * b);
    Plot.Heatmap("surface", Surface, -4, 4, -4, 4, resolution: 120, contours: 10);
    Plot.Labels("surface", title: "A surface", x: "a", y: "b");
    var trail = new List<(double A, double B)>();
    for (int i = 0; i <= (quick ? 60 : 180); i++)
    {
        double t = i * 0.035;
        trail.Add((2.5 * Math.Cos(t), 2.5 * Math.Sin(t) * Math.Cos(t / 2)));
        Plot.Line("surface", trail.Select(p => p.A).ToArray(), trail.Select(p => p.B).ToArray(), series: "trail");
        Pause(30);
    }
}

if (Run("images"))
{
    // Tensors as pictures: 16 rings of different sizes as a [16, 1, 28, 28] batch (like a batch of digits)...
    using var coords = torch.linspace(-1, 1, 28);
    var grid = torch.meshgrid(new[] { coords, coords }, indexing: "ij");
    using var r = torch.sqrt(grid[0] * grid[0] + grid[1] * grid[1]);
    var rings = new List<Tensor>();
    for (int k = 0; k < 16; k++)
    {
        float radius = 0.25f + 0.04f * k;
        rings.Add(torch.exp(-(r - radius).pow(2) / 0.01).unsqueeze(0));
    }
    using var batch = torch.stack(rings);
    Plot.Image("rings", batch);

    // ...and one colour picture as a [3, H, W] tensor.
    using var u = torch.linspace(0, 1, 64);
    var uv = torch.meshgrid(new[] { u, u }, indexing: "ij");
    using var colour = torch.stack(new[] { uv[1], uv[0], 1 - uv[1] });
    Plot.Image("colours", colour);
    foreach (var t in rings) t.Dispose();
}

Console.WriteLine(Plot.Flush() ? "Plots sent to Rider." : "Rider isn't listening: the plots will be saved as PNG files.");

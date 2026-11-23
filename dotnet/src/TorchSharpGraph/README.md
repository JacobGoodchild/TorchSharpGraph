# TorchSharpGraph

Live plots for C# machine learning. Call it while your program runs; with the "Graphs for TorchSharp" plugin the
plots appear in Rider's Graphs window, and without it they are saved as PNG files in `./plots` when the program ends.

```csharp
using TorchSharpGraph;

Plot.Scatter("data", xs, ys);                                   // arrays, lists or tensors
Plot.Function("data", x => a * x + b, -3, 3, series: "model");  // call again to move the curve
Plot.Loss(step, loss);                                          // one point per step; log-scale switch in Rider
Plot.Heatmap("surface", (a, b) => f(a, b), -2, 2, -2, 2, contours: 10);
Plot.Image("digits", batch);                                    // [H, W], [C, H, W], [N, C, H, W] ...
Plot.SaveAllPng("plots");                                       // PNG files at any time
```

Calls return straight away: the numbers are copied and sent from a background thread, at most 30 times a second,
over 127.0.0.1 only. Wrong shapes give a clear message saying what was expected.

MIT licence. Source and the Rider plugin: https://github.com/JacobGoodchild/TorchSharpGraph

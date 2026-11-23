# Third-party notices

TorchSharpGraph is MIT-licensed (see `LICENSE`).

## The Rider plugin

The plugin is written from scratch in Kotlin on the IntelliJ Platform and bundles no third-party libraries.
It contains one piece of third-party data:

| Component | Used for | Licence | Copyright |
|---|---|---|---|
| The viridis colour map (16 colour stops) | Heatmap colours | CC0 1.0 (public domain dedication) | Stéfan van der Walt, Nathaniel Smith and Eric Firing, from matplotlib |

## The NuGet library

The `TorchSharpGraph` NuGet package contains only its own code. It depends on these packages, which NuGet
downloads separately with their own licence files:

| Package | Used for | Licence | Copyright |
|---|---|---|---|
| TorchSharp 0.107.0 | Accepting tensors | MIT | © .NET Foundation and Contributors |
| ScottPlot 5.1.58 | Drawing the PNG files saved when Rider isn't listening | MIT | © Scott Harden and contributors |
| SkiaSharp 3.119.0, SkiaSharp.HarfBuzz, HarfBuzzSharp (used by ScottPlot) | Drawing | MIT; Skia: BSD-3-Clause; HarfBuzz: Old MIT | © Microsoft Corporation; Skia © Google LLC; HarfBuzz © its authors |

The samples and tests also use TorchSharp-cpu 0.107.0 (MIT), which includes LibTorch (BSD-3-Clause,
© Facebook, Inc. and its affiliates), and xUnit (Apache 2.0, © .NET Foundation and Contributors).

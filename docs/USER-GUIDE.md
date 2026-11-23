# User guide

## The library

Add `using TorchSharpGraph;`. Every call takes a plot name; calling again with the same name updates that plot.

| Call | Draws |
|---|---|
| `Plot.Scatter(name, x, y, series: "points")` | Points. `x` and `y`: arrays, lists or 1-D tensors of the same length |
| `Plot.Scatter(name, points)` | `points`: y values (x = 0, 1, 2, …) or an `[N, 2]` tensor of pairs |
| `Plot.Line(name, x, y, series: "line")` / `Plot.Line(name, points)` | A line through the points in order |
| `Plot.Function(name, x => ..., from, to, points: 200, series: "f(x)")` | A curve from a C# lambda |
| `Plot.Loss(step, value)` | Adds a point to the "Loss" plot (`value`: a number or one-number tensor) |
| `Plot.Loss(name, step, value, series: "test")` | Several loss curves in one plot |
| `Plot.Heatmap(name, values, xFrom, xTo, yFrom, yTo, contours: 0)` | A 2-D array or tensor as colours; `values[row, col]`, row 0 at the bottom |
| `Plot.Heatmap(name, (x, y) => ..., xFrom, xTo, yFrom, yTo, resolution: 80, contours: 0)` | A function of two numbers as colours |
| `Plot.Image(name, tensor)` | `[H, W]`, `[C, H, W]`, `[H, W, C]` (1 or 3 channels), or a grid from `[N, H, W]` / `[N, C, H, W]` |
| `Plot.Labels(name, title:, x:, y:)` | Title and axis labels |
| `Plot.LogScale(name)` | Starts the y axis on a log scale |
| `Plot.Clear(name)` / `Plot.Clear()` | Removes one plot / all plots |
| `Plot.SavePng(name, path)` / `Plot.SaveAllPng(folder)` | PNG files, with or without Rider |

Several series in one plot: give each its own `series:` name, for example data points as a scatter and a model's
curve as a function. Points and lines can also go on top of a heatmap.

Pictures: numbers from 0 to 1, or 0 to 255, are used as they are; any other range is stretched to black..white.

Settings (before the first call): `Plot.Settings.SendToViewer`, `SaveWhenNoViewer`, `SaveFolder`, `PngWidth`,
`PngHeight`, `Port`, `MaxUpdatesPerSecond`.

## The Graphs window

It appears in projects that reference TorchSharpGraph, and in any project as soon as a program sends plots.

- **Hover** over a point, cell or pixel to read its value.
- **Scroll** to zoom (hold Shift for x only, Alt for y only), **drag** to move, **double-click** to show everything.
- **Right-click** a plot, or use the toolbar: log scale, reset zoom, export as PNG, copy as image.
- Loss plots: right-click → **Smoothing** draws a smoothed curve over the faint raw values (as TensorBoard does).
- Toolbar: clear all plots, and **Show as Tabs** to see one plot at a time instead of all side by side.
- The status line says which program is sending, or when it finished. The last run's plots stay until the next run.

Settings → Tools → Graphs for TorchSharp: the port, whether the window opens by itself, and whether a new run
clears the old plots.

## Privacy

Programs connect to the plugin over 127.0.0.1. The plugin accepts nothing from other computers, makes no network
connections of its own, and writes one small file, `~/.torchsharpgraph/ides/<process id>.json` (its port and open
project folders, so programs find it), which it removes when the IDE closes.

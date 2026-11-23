# CLAUDE.md — TorchSharpGraph developer notes

Read this file and `git log` first. Never redo committed work. Keep "Status" current and commit after each step.

## Time budget (this build session)
- Session start (UTC): 2026-11-23 20:10:26
- Hard stop: 2026-11-24 03:10 UTC (7 hours)
- Switch to latexComments (github.com/JacobGoodchild/latexComments) by 2026-11-24 00:40 UTC at the latest (aim 00:10)
- Wake-ups: a session cron every 30 min plus an hourly scheduled task "Plugin build wake-up (hourly backup)"
  (trig_01JbczZyNH7ec7C6ttpwEaUg). Cancel both when the session ends.

## What this is
Live plots for C# machine learning inside Rider. Two parts:
- **Library** (`dotnet/src/TorchSharpGraph`, NuGet-ready, not published, net8.0): `Plot.Scatter / Line / Function /
  Loss / Heatmap / Image / Labels / LogScale / Clear / SavePng / SaveAllPng / Flush`. Accepts arrays, lists and
  TorchSharp tensors through the `Values` struct (implicit conversions). Keeps every plot in memory (`Hub`), sends
  changes from one background thread at most 30 times a second over 127.0.0.1 (`Protocol`, JSON lines). If no viewer
  got the plots when the program ends, saves PNGs to `./plots` with ScottPlot.
- **Plugin** (Kotlin, `src/main`): `GraphsServer` (app service, loopback-only ServerSocket, default port 47811 then
  the next free one), `IdeFiles` (`~/.torchsharpgraph/ides/<pid>.json`: port + open project folders, so programs
  connect to the IDE that has their folder open), `PlotStore` (per project), `PlotRenderer` (pure Java2D, testable
  headless), `PlotCanvas` / `GraphsPanel` / `GraphsToolWindowFactory` (tool window "Graphs"), `LibraryUsage`
  (project-file scan deciding if the window is offered), `GraphsSettings` (Settings > Tools > Graphs for TorchSharp).
- Protocol: `docs/development/PROTOCOL.md`.
- Sample: `samples/TorchSharpGraph.Samples` (every plot type, neutral data only — no training code, on purpose).

## Building and testing
- `./gradlew buildPlugin` → `build/distributions/torchsharpgraph-<version>.zip`. Built against IntelliJ IDEA 2026.1.5.1
  (oldest supported platform, since-build 261). `-PriderOnly=true` adds the Rider dependency (Marketplace build).
- `./gradlew test`: Kotlin tests (JSON, store, renderer pixels, hover, zoom, 1M-point speed). Pictures:
  `TORCHSHARPGRAPH_SHOT_DIR=/some/dir ./gradlew test`.
- `cd dotnet && dotnet test`: C# tests (shapes, tensors, coalescing, protocol, discovery, no-hang, PNG fallback).
  .NET 8 SDK: on this machine from Ubuntu's `dotnet-sdk-8.0` package (`/usr/lib/dotnet`).
- UI checks in the free IntelliJ IDEA (sandbox Rider needs a login): `tools/run-idea.sh <folder> <log>` on Xvfb :99,
  `tools/shot.sh out.png [scale%] [crop]`, `tools/theme.sh light|dark` before starting, `UISCALE=2` for 200 %.
  Then run the sample from a terminal: `cd samples/TorchSharpGraph.Samples && dotnet run`.

## Decisions (and why)
- **Display name "Graphs for TorchSharp"**, id `dev.torchsharpgraph`, repo/library/namespace `TorchSharpGraph`.
  Marketplace guidelines (v1.2) bar third-party trademarks in names without permission; "X for TorchSharp" names
  what it works with, as many plugins do. If JetBrains objects, fall back to "Live Training Graphs". The description
  says it is independent of JetBrains, Microsoft and the .NET Foundation.
- **Drawing in the plugin is our own Java2D code**, not JCEF + a JS chart library and not a JVM chart library:
  native look, theme colours, automatic HiDPI sharpness, no browser process, and the renderer is unit-testable
  headless (pixel checks + pictures). Research: PyCharm's plots panel shows static matplotlib images; TensorBoard is
  a browser app with smoothing and log toggles; matplotlib interactive mode redraws a window. We take live updates,
  hover readout, zoom/pan and log toggle from these.
- **PNG fallback uses ScottPlot 5.1.58 (MIT, checked in the package's nuspec and Notices/LICENSE.txt)**. OxyPlot (MIT)
  needs a separate image exporter package per platform; Plotly.NET (MIT) needs a browser/Kaleido to make PNGs. ScottPlot
  draws with SkiaSharp (MIT) and works headless on Linux.
- **Library depends on managed `TorchSharp` 0.107.0 only**; the program picks its own backend (cpu/cuda).
- **Transport**: JSON lines over TCP on 127.0.0.1. Handshake: library sends `hello`, waits for `welcome` (so data only
  goes to our viewer); library never blocks the caller; reconnects every 2 s; replays full state after reconnect.
- **Coalescing**: library keeps state and sends only the latest version of each series (loss curves send only new
  points); the plugin repaints at most ~30 times a second. Line plots with far more points than pixels are drawn
  per pixel column (first/min/max/last), so a million-point loss curve draws in a few ms.
- **Tool window availability**: `shouldBeAvailable=false`; `GraphsStartup` makes it available when a project file
  references TorchSharpGraph; `GraphsProjectService.runStarted` makes it available (and shows it without focus) when
  data arrives. New run clears the old plots (setting). Last run stays visible after the program ends.
- **Routing**: a program goes to the open project whose folder contains its working directory or executable, else the
  last focused project.
- **Viridis** colours for heatmaps (CC0); points/lines over a heatmap use red/white/orange/cyan for contrast.
- Text from programs is plain text (`UiText.plain` stops Swing's HTML rendering).

## Status
Working (tested + screenshots looked at):
- C# library: 32 xUnit tests pass. PNG fallback for every plot kind (looked at).
- Plugin renderer: 9 JUnit tests pass (pixels, hover, zoom/pan, log, heatmap orientation, image square pixels,
  2x sharpness, 1M points < 150 ms).
- Live end to end in IntelliJ IDEA 2026.1.5.1 (dark): sample streams every plot type; window opens by itself without
  taking focus; status line; last run stays. Screenshots `docs/screenshots/*-in-idea-2026.1.png`.

Not done yet / next steps:
1. Kotlin tests for server protocol (readLine, hello/welcome), LibraryUsage, IdeFiles; cross-language end-to-end test.
2. Screenshots: light theme, 200 % scale, hover + zoom in IDE, tabs mode, unrelated project (no Graphs button).
3. Test the built zip installed into a clean IDE (not runIdea).
4. CI workflow (tests + zip + dotnet tests), signed build job (secrets from GitHub), plugin verifier vs Rider
   2026.1/2026.2/2026.3.
5. README, docs/development/PROTOCOL.md, docs/USER-GUIDE.md, change notes.
6. Polish: smarter legend placement, x tick density on narrow plots.

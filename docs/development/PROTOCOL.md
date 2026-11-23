# The plot protocol

Programs (the C# library) and the plugin talk over TCP on the loopback address 127.0.0.1 only. Each message is one
JSON object on one line (UTF-8, ending in `\n`). Numbers that aren't finite (NaN, infinity) are sent as `null` and
drawn as gaps.

## Finding the IDE

1. `PlotSettings.Port` if set, else the `TORCHSHARPGRAPH_PORT` environment variable (only that port is tried).
2. Files in `~/.torchsharpgraph/ides/*.json`, one per running IDE with the plugin:
   `{"port":47811,"pid":1234,"projects":["/home/me/app"]}`. Files of IDEs that are no longer running are ignored.
   The IDE whose project folder contains the program's working directory or executable is tried first.
3. Port 47811.

The plugin binds 127.0.0.1:47811 (the port is a setting), or the next free one of the following nine, or any free port.

## Handshake

Program → IDE: `{"t":"hello","protocol":1,"run":"<id>","program":"<name>","dir":"<working dir>","exe":"<path>","pid":123}`

IDE → program: `{"t":"welcome","protocol":1}`

The program sends nothing else until the welcome arrives (within 400 ms), so data never goes to some other program
that happens to use the port. The IDE sends nothing else, ever. The IDE picks the project by the longest open
project folder that contains `dir` or `exe`, else the last focused project.

## Messages (program → IDE)

| `t` | Fields | Meaning |
|---|---|---|
| `plot` | `plot`, `kind` (`xy`, `loss`, `heatmap`, `image`), optional `title`, `x`, `y`, `logY` | Creates a plot or changes its labels |
| `series` | `plot`, `series`, `style` (`scatter`, `line`), `start`, `x`, `y` | Points from index `start` on; `start` 0 replaces the series |
| `heatmap` | `plot`, `rows`, `cols`, `x0`, `x1`, `y0`, `y1`, `contours`, `v` (row-major, row 0 at the bottom) | Sets the plot's grid |
| `image` | `plot`, `w`, `h`, `count`, `rgba` (base64, row 0 at the top), `raw` (each pixel's number) | Sets the plot's picture |
| `clear` | optional `plot` | Removes one plot, or all of them |
| `end` | | The program is ending normally |

After a reconnect the program sends `clear` and then everything again.

## Limits (the IDE ignores anything bigger)

64 plots, 32 series per plot, 5 million points per series, 4 million heatmap cells, 16 million image pixels,
256 MB per message, 16 connections at once.

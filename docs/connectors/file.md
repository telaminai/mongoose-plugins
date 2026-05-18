# connector-file

<span class="plugin-tags">
  <span class="plugin-tag source">source</span>
  <span class="plugin-tag sink">sink</span>
  <span class="plugin-tag replay">replayable</span>
</span>

File tail (`FileEventSource`) + append-only sink (`FileMessageSink`) with size and time-based rotation. The pragmatic default — works on any host, replayable from disk, no broker needed.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-file</artifactId>
    <version>{{ plugin_version }}</version>
</dependency>
```

## When to use

- Single-process pipelines where the input arrives as JSONL or text on disk.
- Replay-capture sinks (every dispatched event lands in a file you can re-feed later).
- Local development and integration tests — no broker, no host-bind, no port collisions.

## Event source

```yaml
eventFeeds:
  - name: trades
    instance: !!com.telamin.mongoose.plugin.connector.file.FileEventSource
      filename: ./data-in/trades.jsonl
      readStrategy: COMMITED        # or EARLIEST
    broadcast: true
    valueMapper: !!com.telamin.mongoose.plugin.lib.json.TypeSerialiser {}
    agentName: file-source-agent
    idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}
```

The source tails the file, emitting each line as a discrete event. `readStrategy: COMMITED` keeps a durable offset file alongside the input so restarts resume from where the last commit landed; `EARLIEST` always replays from offset 0.

## Message sink

```yaml
eventSinks:
  - name: enriched-out
    instance: !!com.telamin.mongoose.plugin.connector.file.FileMessageSink
      filename: ./data-out/enriched.jsonl
      rotateOnSizeBytes: 10485760     # 10 MB
      rotateOnIntervalMillis: 86400000 # 24 h
      maxBackupFiles: 7
      firstLineSupplier: null
```

The sink appends one event per line. With rotation enabled:

- **Size trigger** — when the active file exceeds `rotateOnSizeBytes`, it rolls to `<filename>.<yyyyMMdd-HHmmss>` and a fresh file is opened.
- **Time trigger** — same rollover after `rotateOnIntervalMillis` since the active file was opened.
- **Pruning** — backups beyond `maxBackupFiles` are deleted oldest-first.

Set both triggers to `0` (default) for an unbounded append-only file.

## Configuration reference

### FileEventSource

| Property        | Default | Notes                                          |
|-----------------|---------|------------------------------------------------|
| `filename`      | _required_ | Path to the input file                       |
| `readStrategy`  | `COMMITED` | `COMMITED` (durable offset) or `EARLIEST` (always replay) |
| `agentName`     | `file-source-agent` | Mongoose agent name |

### FileMessageSink

| Property                | Default | Notes                                |
|-------------------------|---------|--------------------------------------|
| `filename`              | _required_ | Path to the output file              |
| `firstLineSupplier`     | `null`  | Optional `Supplier<Object>` for header line; only invoked when the file is newly created |
| `rotateOnSizeBytes`     | `0`     | `0` disables size-based rotation     |
| `rotateOnIntervalMillis`| `0`     | `0` disables time-based rotation     |
| `maxBackupFiles`        | `0`     | `0` keeps all backups                |

## Operational notes

- Parent directories are auto-created via `mkdirs()` before the file is opened.
- Bare basenames (no `/`) are handled — no `getParentFile()` NPE.
- `start()` throws `IllegalStateException` on negative rotate settings, empty/null filename.
- `stop()` is idempotent.
- The sink is single-writer — the Mongoose dispatcher serialises `sendToSink` calls.

## Examples

Runnable end-to-end demos in [telaminai/mongoose-examples](https://github.com/telaminai/mongoose-examples):

- **[five-minute-yaml-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/five-minute-yaml-tutorial)** — single-feed `FileEventSource` driven from `appConfig.yml`. Shortest path to a real file-tailed pipeline.
- **[app-integration-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/app-integration-tutorial)** — multi-server PnL calculator + data generator with both `FileEventSource` and `FileMessageSink`. YAML + Java variants side by side.
- **[how-to/replay](https://github.com/telaminai/mongoose-examples/tree/main/how-to/replay)** — replay from a captured file feed for deterministic re-runs.

## Source

[`mongoose-plugins/connector/connector-file`](https://github.com/telaminai/mongoose-plugins/tree/main/connector/connector-file)

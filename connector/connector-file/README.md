# connector-file

File-tailing event source and append-only file message sink for Mongoose.

Two components:

| Class | Role |
|---|---|
| [`FileEventSource`](src/main/java/com/fluxtion/dataflow/serverplugin/connector/file/FileEventSource.java) | Tails a line-oriented file (typically JSONL), publishes each new line as a `String` event. Supports `COMMITED` (durable read pointer), `EARLIEST`, `LATEST`, `ONCE_EARLIEST`, `ONCE_LATEST` read strategies. |
| [`FileMessageSink`](src/main/java/com/fluxtion/dataflow/serverplugin/connector/file/FileMessageSink.java) | Appends each delivered message as a line to a file. Auto-creates parent directories. Optional `firstLineSupplier` to seed CSV headers when the file is created. |

## When to use

- Replay scenarios where you tail a `.jsonl` file produced by another process.
- Cold-start a stream from disk and then keep reading new lines (`COMMITED` strategy persists the read offset in `<filename>.readPointer`).
- Sinks that need durable, append-only output (logs, audit trails, replay capture).

For non-durable transport, prefer `connector-multicast` or `connector-kafka`.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-file</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — Java config

```java
// source
FileEventSource source = new FileEventSource();
source.setFilename("./data-in/trades.jsonl");
source.setReadStrategy(ReadStrategy.COMMITED);  // resumable

EventFeedConfig<String> tradeFeed = EventFeedConfig.<String>builder()
        .instance(source)
        .valueMapper(new TypeSerialiser())       // optional, lib-jsonserialiser
        .name("trade-feed")
        .agent("file-source-agent", new SleepingMillisIdleStrategy())
        .build();

// sink
FileMessageSink sink = new FileMessageSink();
sink.setFilename("./data-out/pnl.jsonl");

EventSinkConfig<MessageSink<?>> pnlSink = EventSinkConfig.<MessageSink<?>>builder()
        .instance(sink)
        .valueMapper(o -> objectMapper.writeValueAsString(o))
        .name("pnl-sink")
        .build();
```

## Usage — YAML config

```yaml
eventFeeds:
  - name: trade-feed
    instance: !!com.fluxtion.dataflow.serverplugin.connector.file.FileEventSource
      filename: ./data-in/trades.jsonl
      readStrategy: COMMITED
    broadcast: true
    agentName: file-source-agent
    idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}

eventSinks:
  - name: pnl-sink
    instance: !!com.fluxtion.dataflow.serverplugin.connector.file.FileMessageSink
      filename: ./data-out/pnl.jsonl
```

> Use `SleepingMillisIdleStrategy` not `BackoffIdleStrategy` for file agents unless you've measured the per-call allocation. See the mongoose [DeadWheelScheduler fix](https://github.com/telaminai/mongoose/blob/develop/mongoose/src/main/java/com/telamin/mongoose/service/scheduler/DeadWheelScheduler.java) and its accompanying regression test for the rationale.

## Read strategies

| Strategy | Tailing | Replays existing content | Persists offset |
|---|---|---|---|
| `COMMITED` | yes | yes (from saved offset) | yes (`<filename>.readPointer`) |
| `EARLIEST` | yes | yes (from offset 0) | no |
| `LATEST` | yes | no (starts at EOF) | no |
| `ONCE_EARLIEST` | no | yes | no |
| `ONCE_LATEST` | no | only last line | no |

## Operational notes / gotchas

- **Empty / null filename throws.** `start()` raises `IllegalStateException` rather than silently skipping. Configure `filename` before starting.
- **Bare-basename sinks work.** `FileMessageSink` no longer NPEs when `filename` has no parent path (e.g. `"out.jsonl"`).
- **Parent directories auto-created.** Both source `.readPointer` and sink output files will `mkdirs()` their parents at start.
- **Idle file polling is graceful.** If the data file doesn't exist yet, the source logs a debug message and re-tries on each `doWork()` cycle. No crash.

## Tests

- [`FileEventSourceTest`](src/test/java/com/fluxtion/dataflow/serverplugin/connector/file/FileEventSourceTest.java) — parent-dir creation, null/empty-filename guard.
- [`FileMessageSinkTest`](src/test/java/com/fluxtion/dataflow/serverplugin/connector/file/FileMessageSinkTest.java) — basic write, bare-basename, null/empty-filename guard.

## Related

- [`lib-jsonserialiser`](../../library/lib-jsonserialiser/) — Type-discriminated JSONL deserialisation for use as the source `valueMapper`.

# connector-chronicle

[Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue) + [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map) backed event source and sink for Mongoose.

| Class | Role |
|---|---|
| [`ChronicleEventSource`](src/main/java/com/fluxtion/dataflow/serverplugin/connector/chronicle/ChronicleEventSource.java) | Tails a Chronicle Queue (binary, memory-mapped, persistent). Read pointer is durable in a sibling Chronicle Map so restarts resume where they left off when `ReadStrategy.COMMITED` is selected. |
| [`ChronicleMessageSink`](src/main/java/com/fluxtion/dataflow/serverplugin/connector/chronicle/ChronicleMessageSink.java) | Appends each delivered event into a Chronicle Queue via a `MessageSink` method writer. |

Built against `chronicle-bom` 2.27ea5 (`chronicle-queue` + `chronicle-map`).

## When to use

- Inter-process / inter-JVM messaging on a single host with strict ordering, replay, and minimal allocation.
- Microsecond-latency persistent log on disk rather than over the network.
- Replay scenarios where you want a binary, indexed log instead of plain-text JSONL.

For network-distributed messaging prefer `connector-kafka`. For human-readable replayable logs prefer `connector-file` (JSONL).

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-chronicle</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Layout on disk

A single `chroniclePath` is used; two sibling directories are created underneath:

```
<chroniclePath>/
├── chronicle-queue/       ← rolling binary queue files
└── chronicle-map/
    └── readPointer.dat    ← persistent read offset
```

Both are auto-created on init.

## Usage — Java config

```java
ChronicleEventSource source = new ChronicleEventSource();
source.setChroniclePath("./data-in/trade-log");
source.setReadStrategy(ReadStrategy.COMMITED);

EventFeedConfig<?> tradeFeed = EventFeedConfig.builder()
        .instance(source)
        .name("trade-feed")
        .agent("chronicle-source-agent", new SleepingMillisIdleStrategy())
        .build();

ChronicleMessageSink sink = new ChronicleMessageSink();
sink.setChroniclePath("./data-out/pnl-log");

EventSinkConfig<MessageSink<?>> pnlSink = EventSinkConfig.<MessageSink<?>>builder()
        .instance(sink)
        .name("pnl-sink")
        .build();
```

## Read strategies

| Strategy | Behaviour |
|---|---|
| `COMMITED` | Resume from the last persisted index in the Chronicle Map (default). |
| `LATEST`   | Skip to the tail of the queue. |
| `EARLIEST` | Replay from the head of the queue. |

## Operational notes / gotchas

- **Empty / null `chroniclePath` throws.** Both `onStart()` (source) and `init()` (sink) raise `IllegalStateException` with a clear message rather than `NullPointerException` on string concat.
- **Sibling directories auto-created.** Configure a path that doesn't exist yet; the queue and map subdirs will be `mkdirs()`'d at start.
- **Single-writer per queue.** Chronicle queue files are append-only with one writer at a time. Don't point two `ChronicleMessageSink` instances at the same `chroniclePath`.
- **Cross-platform note.** Chronicle uses memory-mapped files and `Unsafe`; ensure your launch flags include `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` on JDK 21+.

## Tests

- [`ChronicleMessageSinkTest`](src/test/java/com/fluxtion/dataflow/serverplugin/connector/chronicle/ChronicleMessageSinkTest.java) — missing-parent-dir creation, null/empty chroniclePath guards.

## Related

- [`connector-file`](../connector-file/) — JSONL alternative with COMMITED replay semantics.
- [`connector-kafka`](../connector-kafka/) — network-distributed alternative.

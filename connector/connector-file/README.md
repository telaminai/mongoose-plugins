# connector-file

File-tailing event source, append-only file message sinks and a table-driven line mapper for Mongoose.

Components:

| Class | Role |
|---|---|
| [`FileEventSource`](src/main/java/com/telamin/mongoose/plugin/connector/file/FileEventSource.java) | Tails a line-oriented file (typically JSONL), publishes each new line as a `String` event. Supports `COMMITED` (durable read pointer), `EARLIEST`, `LATEST`, `ONCE_EARLIEST`, `ONCE_LATEST` read strategies. |
| [`FileMessageSink`](src/main/java/com/telamin/mongoose/plugin/connector/file/FileMessageSink.java) | Appends each delivered message as a line to a file. Auto-creates parent directories. Optional `firstLineSupplier` to seed CSV headers when the file is created. |
| [`JsonLineSink`](src/main/java/com/telamin/mongoose/plugin/connector/file/JsonLineSink.java) | A `FileMessageSink` writing a String as it is and any other value (record, map, list) as one line of JSON (`java.time` as ISO strings). |
| [`LineMapper`](src/main/java/com/telamin/mongoose/plugin/connector/file/LineMapper.java) | A feed `valueMapper` driven by a table: space-separated text lines by record type and field count, JSON lines by a type field. A line it cannot map becomes [`Rejected`](src/main/java/com/telamin/mongoose/plugin/connector/file/Rejected.java)`(line)`. |

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
    <version>${mongoose-plugins.version}</version>   <!-- 1.1.2 or later for LineMapper and JsonLineSink -->
</dependency>
```

Gradle: `implementation "com.telamin:connector-file:$mongoosePluginsVersion"`. jbang: `//DEPS com.telamin:connector-file:<version>`.
connector-file brings `lib-jsonserialiser` (for `StateJson`) and Jackson.

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
    instance: !!com.telamin.mongoose.plugin.connector.file.FileEventSource
      filename: ./data-in/trades.jsonl
      readStrategy: COMMITED
    broadcast: true
    agentName: file-source-agent
    idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}

eventSinks:
  - name: pnl-sink
    instance: !!com.telamin.mongoose.plugin.connector.file.FileMessageSink
      filename: ./data-out/pnl.jsonl
```

> Use `SleepingMillisIdleStrategy` not `BackoffIdleStrategy` for file agents unless you've measured the per-call allocation. See the mongoose [DeadWheelScheduler fix](https://github.com/telaminai/mongoose/blob/develop/mongoose/src/main/java/com/telamin/mongoose/service/scheduler/DeadWheelScheduler.java) and its accompanying regression test for the rationale.

## LineMapper and JsonLineSink

```java
public class AppLineMapper extends LineMapper {
    public AppLineMapper() {
        record("ITEM", 4, f -> new ItemAdded(f[1], f[2], at(f[3])));   // ITEM sku-1 Widget 2026-01-05T09:00
        json("PICKUP", PickupRequest.class);                          // {"type":"PICKUP","id":"p1",...}
    }
}
```

Fields are split on single spaces and `f[0]` is the record type. An unknown type, a wrong field count, an empty field,
an exception or a `null` result gives `Rejected(line)`, an event a node can handle. A line starting with `{` is JSON:
`json(type, cls)` picks the class by the `typeField()` (`"type"` by default), `json(cls)` is for lines with no type
field; fields fill the record's components by name, unknown fields are ignored. Parsers: `at` (LocalDateTime), `date`,
`i`, `l`, `d`. Non-text input passes through unchanged. A trailing `\r` (a CRLF file) is dropped before mapping. With
both `json(cls)` and typed `json(type, cls)` registered, a line with no type field goes to `json(cls)` and a line whose
type is not registered is rejected. A JSON line holds one value: malformed JSON, or anything but white space after
the first value (two objects on one line, trailing garbage), is rejected too.

```yaml
eventFeeds:
  - name: input
    instance: !!com.telamin.mongoose.plugin.connector.file.FileEventSource   # or any line source
      filename: data/input.txt
      readStrategy: COMMITED
    valueMapper: !!com.example.AppLineMapper {}
eventSinks:
  - name: output
    instance: !!com.telamin.mongoose.plugin.connector.file.JsonLineSink
      filename: data/run/output.txt
```

`JsonLineSink.format(value)` is the line a value is written as; mongoose-test-support's `FixtureRunner` formats sink
values the same way, so in-process and server runs compare alike. `JsonLineSink` takes the rotation options of
`FileMessageSink`. It is indexed for the config schema as `connector-file-jsonline:sink`.

## Migration from `com.telamin:fluxtion-app-support`

| Old | New (artifact `com.telamin:connector-file`) |
|---|---|
| `com.telamin.support.LineMapper` | `com.telamin.mongoose.plugin.connector.file.LineMapper` |
| `com.telamin.support.Rejected` | `com.telamin.mongoose.plugin.connector.file.Rejected` |
| `com.telamin.support.JsonLineSink` | `com.telamin.mongoose.plugin.connector.file.JsonLineSink` |

The API is unchanged. `JsonLineSink` now extends this module's `FileMessageSink` (same `filename` key, plus rotation)
instead of Mongoose core's.

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

- [`FileEventSourceTest`](src/test/java/com/telamin/mongoose/plugin/connector/file/FileEventSourceTest.java) — parent-dir creation, null/empty-filename guard.
- [`FileMessageSinkTest`](src/test/java/com/telamin/mongoose/plugin/connector/file/FileMessageSinkTest.java) — basic write, bare-basename, null/empty-filename guard.
- [`LineMapperTest`](src/test/java/com/telamin/mongoose/plugin/connector/file/LineMapperTest.java) — text and JSON lines, rejections, pass-through.
- [`JsonLineSinkTest`](src/test/java/com/telamin/mongoose/plugin/connector/file/JsonLineSinkTest.java) — Strings as is, records as one JSON line.

## Related

- [`lib-jsonserialiser`](../../library/lib-jsonserialiser/) — Type-discriminated JSONL deserialisation for use as the source `valueMapper`.

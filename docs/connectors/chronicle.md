# connector-chronicle

<span class="plugin-tags">
  <span class="plugin-tag source">source</span>
  <span class="plugin-tag sink">sink</span>
  <span class="plugin-tag persist">persistent</span>
  <span class="plugin-tag lowlat">low-latency</span>
</span>

[Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue) + [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map) bindings: microsecond-latency persistent log and off-heap, memory-mapped key/value store. The step up from `connector-file` when you outgrow line-oriented JSONL but aren't yet at "needs Kafka" scale.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-chronicle</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## When to use

- High-throughput single-host event pipeline (10⁵–10⁶ events/sec).
- You need replay-from-index out of the box.
- You want binary, memory-mapped persistence — no JSON parsing overhead.

## Components

| Class                       | Role          |
|-----------------------------|---------------|
| `ChronicleEventSource`      | Queue source — reads from a Chronicle Queue. |
| `ChronicleMessageSink`      | Queue sink — appends events to a Chronicle Queue. |
| `ChronicleMapBuilderCache`  | Service wrapping `ChronicleMap` as a `Cache`. |

## Sample

```yaml
eventFeeds:
  - name: trades
    instance: !!com.telamin.mongoose.plugin.connector.chronicle.ChronicleEventSource
      basePath: ./data/trades
      rollCycle: HOURLY

eventSinks:
  - name: trades-out
    instance: !!com.telamin.mongoose.plugin.connector.chronicle.ChronicleMessageSink
      basePath: ./data/trades-out
      rollCycle: HOURLY

services:
  - name: reference-data
    instance: !!com.telamin.mongoose.plugin.connector.chronicle.ChronicleMapBuilderCache
      filePath: ./data/refdata.map
      entries: 1000000
      averageValueSize: 256
```

## Operational notes

- Memory-mapped files; expect your VM's RSS to mirror the queue/map size.
- `tearDown()` flushes the Chronicle Queue cleanly; pair with a shutdown hook in your launcher for abrupt VM exits.
- Schema for queue payloads is your responsibility — choose a wire-format (binary Chronicle, JSON, Protobuf) and stick to it.

## Examples

- **[plugins/event-source-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-example)** + **[plugins/message-sink-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/message-sink-example)** — source/sink templates; `ChronicleEventSource` / `ChronicleMessageSink` follow the same shape.

A dedicated Chronicle round-trip example is on the [Examples](../examples.md) roadmap.

## Source

[`mongoose-plugins/connector/connector-chronicle`](https://github.com/telaminai/mongoose-plugins/tree/main/connector/connector-chronicle)

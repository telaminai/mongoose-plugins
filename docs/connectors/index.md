# Connectors

Event sources, message sinks, and bridges to the outside world. Each connector ships both halves where it makes sense — drop in source-only, sink-only, or pair them for a round-trip.

<div class="grid cards" markdown>

-   :material-flash:{ .lg .middle } __[connector-aeron](aeron.md)__

    Sub-microsecond IPC + UDP. Live subscribe or archive replay. Single-host or LAN.

    <span class="plugin-tags">
      <span class="plugin-tag source">source</span>
      <span class="plugin-tag sink">sink</span>
      <span class="plugin-tag lowlat">low-latency</span>
    </span>

-   :material-file-document-outline:{ .lg .middle } __[connector-file](file.md)__

    File tail + append-only sink with size/time-based rotation.

    <span class="plugin-tags">
      <span class="plugin-tag source">source</span>
      <span class="plugin-tag sink">sink</span>
      <span class="plugin-tag replay">replayable</span>
    </span>

-   :material-database-clock-outline:{ .lg .middle } __[connector-chronicle](chronicle.md)__

    Chronicle Queue + Map: persistent, memory-mapped, microsecond-latency.

    <span class="plugin-tags">
      <span class="plugin-tag source">source</span>
      <span class="plugin-tag sink">sink</span>
      <span class="plugin-tag persist">persistent</span>
    </span>

-   :material-arrow-decision-outline:{ .lg .middle } __[connector-kafka](kafka.md)__

    Producer + consumer for cross-process / cross-host messaging.

    <span class="plugin-tags">
      <span class="plugin-tag source">source</span>
      <span class="plugin-tag sink">sink</span>
      <span class="plugin-tag broker">broker</span>
    </span>

-   :material-radio-tower:{ .lg .middle } __[connector-multicast](multicast.md)__

    UDP multicast for LAN-scoped pubsub without a broker.

    <span class="plugin-tags">
      <span class="plugin-tag source">source</span>
      <span class="plugin-tag sink">sink</span>
    </span>

</div>

## Choosing a connector

The fast rule:

| Need                                     | Connector                |
|------------------------------------------|--------------------------|
| Tail a JSON or text file, replay later   | **connector-file**       |
| High-throughput persistent log on disk   | **connector-chronicle**  |
| Cross-process messaging via Kafka broker | **connector-kafka**      |
| LAN broadcast / discovery / heartbeats   | **connector-multicast**  |
| Sub-microsecond IPC, cold-start replay   | **connector-aeron**      |

If you outgrow one tier, swap to the next without changing your processor — connector-file → chronicle → kafka is a common progression as throughput rises.

## Operational defaults shared across connectors

- All file paths auto-create their parent directories.
- All `tearDown()` calls are idempotent.
- Source-side `doWork()` is configurable via an idle strategy on the agent runner.
- Sink-side `sendToSink()` is single-writer (the dispatcher serialises calls).

See [Operational guide](../operations.md) for the full production checklist.

# connector-aeron

<span class="plugin-tags">
  <span class="plugin-tag source">source</span>
  <span class="plugin-tag sink">sink</span>
  <span class="plugin-tag lowlat">low-latency</span>
  <span class="plugin-tag replay">archive replay</span>
</span>

[Aeron](https://github.com/real-logic/aeron) IPC + UDP transport for Mongoose. One channel/stream as an event source (live or archive replay), one as an outbound sink.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-aeron</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

Transitive deps: `io.aeron:aeron-client`, `aeron-driver`, `aeron-archive` 1.48.0.

## What's in the box

| Class                                | Role         |
|--------------------------------------|--------------|
| `AeronArchiveEventSource`            | Event source — `LIVE` or `ARCHIVE` mode. |
| `AeronMessageSink`                   | Message sink — `String` and `byte[]` payloads. |

Both share the same media-driver wiring. Either bring your own `MediaDriver` (production) or set `launchEmbeddedDriver=true` (dev / tests).

## Event source

=== "Live subscribe"

    ```yaml
    eventFeeds:
      - name: trades
        instance: !!com.fluxtion.server.plugin.connector.aeron.AeronArchiveEventSource
          mode: LIVE
          channel: aeron:udp?endpoint=224.0.1.1:40456
          streamId: 10
          aeronDirectoryName: /var/run/aeron
          fragmentLimit: 50
        broadcast: true
        idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy { sleepPeriodMs: 1 }
    ```

=== "Archive replay (cold start)"

    ```yaml
    eventFeeds:
      - name: trades-replay
        instance: !!com.fluxtion.server.plugin.connector.aeron.AeronArchiveEventSource
          mode: ARCHIVE
          channel: aeron:udp?endpoint=224.0.1.1:40456
          streamId: 10
          replayChannel: aeron:ipc
          aeronDirectoryName: /var/run/aeron
    ```

    Replay finds the latest recording for the (channel, streamId) tuple via `listRecordingsForUri` and replays from start to end.

=== "Binary mode"

    ```yaml
    instance: !!com.fluxtion.server.plugin.connector.aeron.AeronArchiveEventSource
      channel: aeron:ipc
      streamId: 11
      binaryMode: true   # publish raw byte[]; otherwise UTF-8 String
    ```

## Message sink

```yaml
eventSinks:
  - name: outbound
    instance: !!com.fluxtion.server.plugin.connector.aeron.AeronMessageSink
      channel: aeron:ipc
      streamId: 10
      initialBufferCapacity: 65536       # default 4096
      offerTimeoutNanos: 2000000000      # 2 s
```

### `Publication.offer` result policy

| Return code              | Behaviour                                            |
|--------------------------|------------------------------------------------------|
| `> 0` (success)          | `publishedCount` incremented                         |
| `BACK_PRESSURED`         | Retry with `Thread.yield()` until `offerTimeoutNanos` |
| `ADMIN_ACTION`           | Retry until `offerTimeoutNanos`                      |
| `NOT_CONNECTED`          | Retry; `notConnectedRetryCount` incremented          |
| `MAX_POSITION_EXCEEDED`  | Drop + warn; `droppedCount` incremented              |
| `CLOSED`                 | Drop + warn; `droppedCount` incremented              |
| timeout                  | Drop + warn; `droppedCount` incremented              |

### Metrics

- `getPublishedCount()` — fragments accepted by the publication
- `getDroppedCount()` — payloads dropped
- `getNotConnectedRetryCount()` — offer attempts while subscriber was absent

## Configuration reference

### AeronArchiveEventSource

| Property              | Default                  | Notes                                              |
|-----------------------|--------------------------|----------------------------------------------------|
| `mode`                | `LIVE`                   | `LIVE` or `ARCHIVE`                                |
| `channel`             | `aeron:ipc`              | Aeron channel URI                                  |
| `streamId`            | `10`                     | Must be > 0                                        |
| `replayChannel`       | `aeron:ipc`              | Only used in `ARCHIVE` mode                        |
| `aeronDirectoryName`  | _embedded driver default_| CnC directory                                      |
| `launchEmbeddedDriver`| `false`                  | Launches an embedded `MediaDriver` on start        |
| `cacheEventLog`       | `false`                  | Cache pre-`startComplete` events for dispatch replay |
| `binaryMode`          | `false`                  | Publish raw `byte[]` instead of UTF-8 String       |
| `fragmentLimit`       | `50`                     | Max fragments per `doWork` poll                    |

### AeronMessageSink

| Property               | Default               | Notes                                |
|------------------------|-----------------------|--------------------------------------|
| `channel`              | `aeron:ipc`           | Channel URI                          |
| `streamId`             | `10`                  | Must be > 0                          |
| `aeronDirectoryName`   | _embedded driver default_ | CnC directory                    |
| `launchEmbeddedDriver` | `false`               | Embedded driver on start             |
| `initialBufferCapacity`| `4096`                | Max payload size in bytes            |
| `offerTimeoutNanos`    | `2 000 000 000` (2 s) | Max wait per offer                   |

## JDK / agrona compatibility

`io.aeron:aeron-client:1.48.0` pulls `org.agrona:agrona` 2.x, which needs JDK 21+ and the `--add-opens` flag on JDK 25:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

The plugin parent POM already wires this.

## Source

[`mongoose-plugins/connector/connector-aeron`](https://github.com/telaminai/mongoose-plugins/tree/main/connector/connector-aeron)

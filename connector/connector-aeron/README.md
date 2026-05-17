# connector-aeron

[Aeron](https://github.com/real-logic/aeron) connector for the Telamin Mongoose server.
Provides a single Aeron channel/stream as an event source (live or archive replay) and
an outbound message sink.

## Modules

| Class                                | Role         | Notes                                                                  |
|--------------------------------------|--------------|------------------------------------------------------------------------|
| `AeronArchiveEventSource`            | Event source | `LIVE` or `ARCHIVE` mode; UTF-8 String or `byte[]` events.             |
| `AeronMessageSink`                   | Message sink | Sends `String` / `byte[]` payloads verbatim; everything else `toString`. |

Both share the same Aeron media driver and directory wiring.

## Maven

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-aeron</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

Transitive: `io.aeron:aeron-client`, `aeron-driver`, `aeron-archive` 1.48.0
(pulls `org.agrona:agrona` 2.x — JDK 21+ on the runtime classpath).

## Event source — live subscribe

```java
AeronArchiveEventSource source = new AeronArchiveEventSource("trades-feed");
source.setMode(AeronArchiveEventSource.Mode.LIVE);
source.setChannel("aeron:udp?endpoint=224.0.1.1:40456");
source.setStreamId(10);
source.setLaunchEmbeddedDriver(true); // dev / test
// production: bring your own MediaDriver and set aeronDirectoryName
```

## Event source — archive replay (cold start)

```java
AeronArchiveEventSource source = new AeronArchiveEventSource("trades-replay");
source.setMode(AeronArchiveEventSource.Mode.ARCHIVE);
source.setChannel("aeron:udp?endpoint=224.0.1.1:40456");
source.setStreamId(10);
source.setReplayChannel("aeron:ipc");       // replay locally
source.setAeronDirectoryName(driverDir);
```

Replay finds the **latest** recording for the (channel, streamId) tuple via
`AeronArchive.listRecordingsForUri` and replays from start to end of the recording.

## Message sink

```java
AeronMessageSink sink = new AeronMessageSink();
sink.setChannel("aeron:ipc");
sink.setStreamId(10);
sink.setInitialBufferCapacity(64 * 1024);   // default 4096
sink.setOfferTimeoutNanos(TimeUnit.SECONDS.toNanos(2));
sink.init();
```

The sink uses an `UnsafeBuffer` of `initialBufferCapacity` bytes — payloads larger
than that are dropped and counted (see metrics below).

### Result-code policy

`Publication.offer` return codes are handled as follows:

| Return code              | Behaviour                                            |
|--------------------------|------------------------------------------------------|
| `> 0` (success)          | `publishedCount` incremented                          |
| `BACK_PRESSURED`         | Retry with `Thread.yield()` until `offerTimeoutNanos` |
| `ADMIN_ACTION`           | Retry until `offerTimeoutNanos`                       |
| `NOT_CONNECTED`          | Retry; `notConnectedRetryCount` incremented           |
| `MAX_POSITION_EXCEEDED`  | Drop + warn; `droppedCount` incremented               |
| `CLOSED`                 | Drop + warn; `droppedCount` incremented               |
| timeout reached          | Drop + warn; `droppedCount` incremented               |

### Metrics

The sink exposes three counters:

- `getPublishedCount()` — fragments accepted by the publication
- `getDroppedCount()` — payloads dropped (oversize / CLOSED / timeout)
- `getNotConnectedRetryCount()` — offer attempts while subscriber was absent

## Configuration reference

### `AeronArchiveEventSource`

| Property              | Default                      | Description                                              |
|-----------------------|------------------------------|----------------------------------------------------------|
| `mode`                | `LIVE`                       | `LIVE` or `ARCHIVE`                                       |
| `channel`             | `aeron:ipc`                  | Aeron channel URI                                         |
| `streamId`            | `10`                         | Aeron stream id (must be > 0)                             |
| `replayChannel`       | `aeron:ipc`                  | Channel to replay to (ARCHIVE mode)                       |
| `aeronDirectoryName`  | _embedded driver default_    | CnC directory                                             |
| `launchEmbeddedDriver`| `false`                      | If true, launches an embedded `MediaDriver` on start      |
| `cacheEventLog`       | `false`                      | Cache pre-startComplete events for replay on dispatch     |
| `binaryMode`          | `false`                      | If true, publish raw `byte[]`; otherwise UTF-8 `String`   |
| `fragmentLimit`       | `50`                         | Max fragments per `doWork` poll                           |

### `AeronMessageSink`

| Property               | Default                       | Description                              |
|------------------------|-------------------------------|------------------------------------------|
| `channel`              | `aeron:ipc`                   | Aeron channel URI                         |
| `streamId`             | `10`                          | Aeron stream id (must be > 0)             |
| `aeronDirectoryName`   | _embedded driver default_     | CnC directory                             |
| `launchEmbeddedDriver` | `false`                       | If true, launches an embedded driver      |
| `initialBufferCapacity`| `4096`                        | Max payload size in bytes                 |
| `offerTimeoutNanos`    | `2 000 000 000` (2s)          | Max wait for a single offer to succeed    |

## Threading

The sink is **single-writer**. Calls to `sendToSink` must be serialised — the
Mongoose dispatcher already guarantees this on the sink's dispatch thread.

The source's `doWork` is invoked by Mongoose's agent runner and is single-threaded
per service.

## JDK / agrona compatibility

`io.aeron:aeron-client:1.48.0` pulls `org.agrona:agrona` 2.x, which needs JDK 21+.
On JDK 25+ the test runner needs:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

The mongoose-plugins parent already wires this for Surefire.

## Tests

```bash
mvn -pl connector/connector-aeron test
```

Round-trip tests use `MediaDriver.launchEmbedded(...)` over `aeron:ipc` —
no Docker, no extra setup.

# Connector & service config reference

!!! note "Generated"
    This page is generated from the plugin config classes by
    `tooling/config-schema-gen` — do not edit by hand. The same data,
    machine-readable, is at [`schema.json`](../schema/schema.json)
    (consumed by the Fluxtion project starter).

Plugins release **`1.0.31-SNAPSHOT`**. Feeds bind under `eventFeeds:` / sinks under `eventSinks:` (key `instance:`); services under `services:` (key `service:`).

## Connectors

### FileEventSource  <small>`mongoose` · source</small>

Built-in (mongoose-core): tail a file as an event source.

- **Class**: `com.telamin.mongoose.connector.file.FileEventSource`
- **YAML**: `eventFeeds:` → `instance:`
- **From**: mongoose-core `1.0.20`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `cacheEventLog` | boolean |  | `false` | Retain published lines in memory for replay. |
| `filename` | string | ✔ |  | _format: path_<br>Path to the file to tail. |
| `readStrategy` | enum |  | `COMMITED` | `COMMITED` \| `EARLIEST` \| `LATEST` \| `ONCE_EARLIEST` \| `ONCE_LATEST`<br>How much of the file to replay on start. |

### FileMessageSink  <small>`mongoose` · sink</small>

Built-in (mongoose-core): append messages to a file.

- **Class**: `com.telamin.mongoose.connector.file.FileMessageSink`
- **YAML**: `eventSinks:` → `instance:`
- **From**: mongoose-core `1.0.20`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `filename` | string | ✔ |  | _format: path_<br>Path of the output file. |

### InMemoryEventSource  <small>`mongoose` · source</small>

Built-in (mongoose-core): in-memory event source (tests / in-process).

- **Class**: `com.telamin.mongoose.connector.memory.InMemoryEventSource`
- **YAML**: `eventFeeds:` → `instance:`
- **From**: mongoose-core `1.0.20`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `cacheEventLog` | boolean |  | `false` | Retain published events in memory for replay. |

### InMemoryMessageSink  <small>`mongoose` · sink</small>

Built-in (mongoose-core): in-memory message sink.

- **Class**: `com.telamin.mongoose.connector.memory.InMemoryMessageSink`
- **YAML**: `eventSinks:` → `instance:`
- **From**: mongoose-core `1.0.20`

_No configurable fields._

### FileEventSource  <small>`connector-file` · source</small>

Tail a file as an event source.

- **Class**: `com.telamin.mongoose.plugin.connector.file.FileEventSource`
- **YAML**: `eventFeeds:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `cacheEventLog` | boolean |  | `false` | Retain published lines in memory for replay. |
| `filename` | string | ✔ |  | _format: path_<br>Path to the file to tail. |
| `readStrategy` | enum |  | `COMMITED` | `COMMITED` \| `EARLIEST` \| `LATEST` \| `ONCE_EARLIEST` \| `ONCE_LATEST`<br>How much of the file to replay on start. |

### FileMessageSink  <small>`connector-file` · sink</small>

Append messages to a file.

- **Class**: `com.telamin.mongoose.plugin.connector.file.FileMessageSink`
- **YAML**: `eventSinks:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `filename` | string | ✔ |  | _format: path_<br>Path of the output file. |
| `maxBackupFiles` | int |  | `0` | Number of rotated backups to retain. |
| `rotateOnIntervalMillis` | long |  | `0` | _format: millis_<br>Rotate on this interval; 0 = never. |
| `rotateOnSizeBytes` | long |  | `0` | Rotate when the file exceeds this many bytes; 0 = never. |

### KafkaMessageConsumer  <small>`connector-kafka` · source</small>

Consume from a Kafka topic as an event source.

- **Class**: `com.telamin.mongoose.plugin.connector.kafka.KafkaMessageConsumer`
- **YAML**: `eventFeeds:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `pollTimeoutMs` | long |  | `100` | _format: millis_<br>Consumer poll timeout. |
| `properties` | map&lt;string,string&gt; | ✔ |  | Kafka consumer properties (bootstrap.servers, group.id, deserializers, …). |
| `topics` | list&lt;string&gt; | ✔ |  | Kafka topics to subscribe to. |
| `wakeupOnTearDown` | boolean |  | `true` |  |

### KafkaMessagePublisher  <small>`connector-kafka` · sink</small>

Publish messages to a Kafka topic.

- **Class**: `com.telamin.mongoose.plugin.connector.kafka.KafkaMessagePublisher`
- **YAML**: `eventSinks:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `closeTimeoutMs` | long |  | `5000` |  |
| `flushEveryMessage` | boolean |  | `true` |  |
| `properties` | map&lt;string,string&gt; | ✔ |  | Kafka producer properties (bootstrap.servers, serializers, …). |
| `registerShutdownHook` | boolean |  | `true` |  |
| `topic` | string | ✔ |  | Destination Kafka topic. |

### AeronArchiveEventSource  <small>`connector-aeron` · source</small>

Subscribe to an Aeron stream / replay from an Aeron Archive.

- **Class**: `com.telamin.mongoose.plugin.connector.aeron.AeronArchiveEventSource`
- **YAML**: `eventFeeds:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `aeronDirectoryName` | string |  |  | _format: path_<br>Aeron media-driver directory; blank = embedded/default. |
| `binaryMode` | boolean |  | `false` |  |
| `cacheEventLog` | boolean |  | `false` |  |
| `channel` | string |  | `aeron:ipc` | Aeron channel URI (e.g. aeron:ipc or aeron:udp?endpoint=host:port). |
| `fragmentLimit` | int |  | `50` |  |
| `launchEmbeddedDriver` | boolean |  | `false` |  |
| `mode` | enum |  | `LIVE` | `LIVE` \| `ARCHIVE` |
| `replayChannel` | string |  | `aeron:ipc` |  |
| `streamId` | int |  | `10` | Aeron stream id. |

### AeronMessageSink  <small>`connector-aeron` · sink</small>

Publish messages to an Aeron stream.

- **Class**: `com.telamin.mongoose.plugin.connector.aeron.AeronMessageSink`
- **YAML**: `eventSinks:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `aeronDirectoryName` | string |  |  | _format: path_<br>Aeron media-driver directory; blank = embedded/default. |
| `channel` | string |  | `aeron:ipc` | Aeron channel URI. |
| `initialBufferCapacity` | int |  | `4096` |  |
| `launchEmbeddedDriver` | boolean |  | `false` |  |
| `offerTimeoutNanos` | long |  | `2000000000` |  |
| `streamId` | int |  | `10` | Aeron stream id. |

### ChronicleEventSource  <small>`connector-chronicle` · source</small>

Read events from a Chronicle Queue.

- **Class**: `com.telamin.mongoose.plugin.connector.chronicle.ChronicleEventSource`
- **YAML**: `eventFeeds:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `cacheEventLog` | boolean |  | `false` |  |
| `chroniclePath` | string | ✔ |  | _format: path_<br>Chronicle Queue directory to read from. |
| `readStrategy` | enum |  | `COMMITED` | `COMMITED` \| `EARLIEST` \| `LATEST`<br>How much of the queue to replay on start. |

### ChronicleMessageSink  <small>`connector-chronicle` · sink</small>

Append messages to a Chronicle Queue.

- **Class**: `com.telamin.mongoose.plugin.connector.chronicle.ChronicleMessageSink`
- **YAML**: `eventSinks:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `chroniclePath` | string | ✔ |  | _format: path_<br>Chronicle Queue directory to append to. |

### MulticastEventSource  <small>`connector-multicast` · source</small>

Receive events over UDP multicast.

- **Class**: `com.telamin.mongoose.plugin.connector.multicast.MulticastEventSource`
- **YAML**: `eventFeeds:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `cacheEventLog` | boolean |  | `false` |  |
| `multicastGroup` | string | ✔ | `224.0.0.1` | Multicast group address (e.g. 224.0.0.1). |
| `multicastPort` | int | ✔ | `4446` | _format: port_<br>Multicast port. |
| `networkInterfaceName` | string |  |  | NIC to bind; blank = default route. |
| `useLoopbackInterface` | boolean |  | `false` |  |

### MulticastMessageSink  <small>`connector-multicast` · sink</small>

Send messages over UDP multicast.

- **Class**: `com.telamin.mongoose.plugin.connector.multicast.MulticastMessageSink`
- **YAML**: `eventSinks:` → `instance:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `multicastGroup` | string | ✔ | `224.0.0.1` | Multicast group address. |
| `multicastPort` | int | ✔ | `4446` | _format: port_<br>Multicast port. |
| `networkInterfaceName` | string |  |  | NIC to bind; blank = default route. |
| `useLoopbackInterface` | boolean |  | `false` |  |

## Services

### JsonFileCache  <small>`svc-cache` · service</small>

JSON-persistent key/value cache service.

- **Class**: `com.telamin.mongoose.plugin.svc.cache.JsonFileCache`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `asyncWrite` | boolean |  | `false` | Flush writes on a background thread. |
| `fileName` | string | ✔ |  | _format: path_<br>Backing JSON file path. |
| `maxSize` | int |  | `0` | 0 = unbounded; >0 enables LRU eviction. |

### JdbcConnectionLoaderService  <small>`svc-jdbc` · service</small>

Named JDBC connection registry (HikariCP-backed).

- **Class**: `com.telamin.mongoose.plugin.svc.jdbc.impl.JdbcConnectionLoaderService`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `connections` | map&lt;string,nested&gt; | ✔ | `{}` | Named JDBC connections (name → pool config: url, user, password, sizing).<br>_object — see below_ |
| `fastFail` | boolean |  | `false` | Fail boot if a startup connectivity test fails. |
| `testConnection` | boolean |  | `false` | Run a validation query for each pool at startup. |

*`connections` value object:*

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `connectionTimeoutMs` | long |  | `30000` |  |
| `idleTimeoutMs` | long |  | `600000` |  |
| `maxLifetimeMs` | long |  | `1800000` |  |
| `maximumPoolSize` | int |  | `10` |  |
| `minimumIdle` | int |  | `0` |  |
| `password` | string |  |  |  |
| `poolName` | string |  |  |  |
| `pooled` | boolean |  | `true` |  |
| `url` | string |  |  |  |
| `username` | string |  |  |  |
| `validationQuery` | string |  |  |  |

### MicrometerBridge  <small>`svc-micrometer` · service</small>

Bridge Mongoose counters to a Micrometer registry.

- **Class**: `com.telamin.mongoose.plugin.svc.micrometer.MicrometerBridge`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `counterPrefix` | string |  | `mongoose` |  |
| `latencyPrefix` | string |  | `mongoose.latency` |  |
| `sampleIntervalMs` | long |  | `1000` | _format: millis_<br>How often counters are pushed to the registry. |

### TelnetAdminCommandProcessor  <small>`svc-admin-telnet` · service</small>

Telnet admin endpoint (JLine completion + history).

- **Class**: `com.telamin.mongoose.plugin.svc.admintelnet.TelnetAdminCommandProcessor`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `interfaceName` | string |  | `127.0.0.1` | _format: host_<br>Bind address; loopback by default. |
| `listenPort` | int |  | `2019` | _format: port_<br>Telnet listen port. |

### JavalinAdminCommandService  <small>`svc-admin-rest` · service</small>

Javalin REST admin endpoint.

- **Class**: `com.telamin.mongoose.plugin.svc.adminrest.JavalinAdminCommandService`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `authMode` | enum |  | `NONE` | `NONE` \| `BASIC` \| `BEARER`<br>NONE \| BASIC \| BEARER. |
| `bearerToken` | string |  |  | BEARER credential ($ENV. resolvable). |
| `host` | string |  | `0.0.0.0` | _format: host_<br>Bind address (0.0.0.0 = all interfaces — front with TLS). |
| `listenPort` | int |  | `8080` | _format: port_ |
| `password` | string |  |  | BASIC credential ($ENV. resolvable). |
| `realm` | string |  | `mongoose-admin` |  |
| `staticDir` | string |  |  | _format: path_<br>Optional static SPA directory to host. |
| `username` | string |  |  | BASIC credential ($ENV. resolvable). |

### WebAdminService  <small>`svc-admin-web` · service</small>

Browser admin & monitoring console (REST + WebSocket + SPA).

- **Class**: `com.telamin.mongoose.plugin.svc.adminweb.WebAdminService`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `authMode` | enum |  | `NONE` | `NONE` \| `BASIC` \| `BEARER`<br>NONE \| BASIC \| BEARER. |
| `basePath` | string |  |  |  |
| `bearerToken` | string |  |  | BEARER credential ($ENV. resolvable). |
| `graphmlRoots` | list&lt;string&gt; |  | `[]` | Dirs the processor-graph panel reads .graphml from. |
| `host` | string |  | `127.0.0.1` | _format: host_<br>Bind address; loopback by default — front with TLS for multi-host. |
| `listenPort` | int |  | `8181` | _format: port_ |
| `loaderBaseDir` | string |  |  | _format: path_<br>Root for the loader file picker; unset disables it. |
| `logTailBuffer` | int |  | `500` | Max retained log records for the log tail. |
| `metricsIntervalMs` | int |  | `1000` | _format: millis_<br>JVM/throughput sampler period. |
| `password` | string |  |  | BASIC credential ($ENV. resolvable). |
| `realm` | string |  | `mongoose-admin` |  |
| `sessionMinutes` | int |  | `60` | Session cookie lifetime. |
| `sessionSecret` | string |  |  | HMAC key for session cookies ($ENV. resolvable); pin to survive restarts. |
| `sourceRoots` | list&lt;string&gt; |  | `[]` | Dirs the node-source viewer reads .java from. |
| `username` | string |  |  | BASIC credential ($ENV. resolvable). |

### EventHandlerLoader  <small>`svc-loader-yaml` · service</small>

Load/reload processors at runtime from YAML or Java source.

- **Class**: `com.telamin.mongoose.plugin.loader.yaml.EventHandlerLoader`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `generatedResourcesDir` | string |  |  | _format: path_ |
| `generatedSourceDir` | string |  |  | _format: path_ |
| `loadAtStartup` | list&lt;nested&gt; |  | `[]` | Descriptors to compile + load at boot.<br>_object — see below_ |
| `packageName` | string |  | `com.telamin.mongoose.runtime.loaded.yaml` |  |
| `persistentConfigDir` | string |  |  | _format: path_ |

*`loadAtStartup` element object:*

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `addEventAuditor` | boolean |  | `false` |  |
| `compile` | boolean |  | `true` |  |
| `group` | string |  | `yamlLoader` |  |
| `yamlFile` | string |  |  |  |

### SpringEventHandlerLoader  <small>`svc-loader-spring` · service</small>

Load/reload processors at runtime from Spring XML.

- **Class**: `com.telamin.mongoose.plugin.loader.spring.SpringEventHandlerLoader`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `generatedResourcesDir` | string |  |  | _format: path_ |
| `generatedSourceDir` | string |  |  | _format: path_ |
| `loadAtStartup` | list&lt;nested&gt; |  | `[]` | Spring files to load at boot.<br>_object — see below_ |
| `packageName` | string |  | `com.telamin.mongoose.runtime.loaded.spring` |  |
| `persistentConfigDir` | string |  |  | _format: path_ |

*`loadAtStartup` element object:*

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `addEventAuditor` | boolean |  | `true` |  |
| `compile` | boolean |  | `true` |  |
| `group` | string |  | `springBeanLoader` |  |
| `springFile` | string |  |  |  |

### FeedLoader  <small>`svc-loader-feed` · service</small>

Add/remove event feeds at runtime.

- **Class**: `com.telamin.mongoose.plugin.loader.feed.FeedLoader`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `persistentConfigDir` | string |  |  | _format: path_<br>Where runtime-added feed configs are persisted. |

### SinkLoader  <small>`svc-loader-sink` · service</small>

Add/remove event sinks at runtime.

- **Class**: `com.telamin.mongoose.plugin.loader.sink.SinkLoader`
- **YAML**: `services:` → `service:`

| Field | Type | Required | Default | Allowed / notes |
|---|---|:--:|---|---|
| `persistentConfigDir` | string |  |  | _format: path_<br>Where runtime-added sink configs are persisted. |


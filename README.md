# Mongoose Plugins

A curated collection of plugins for the [Mongoose](https://github.com/telaminai/mongoose) deterministic event-processing server. Each plugin is an independent Maven module; pick and mix the ones you need rather than pulling in the whole set.

Mongoose itself ships with the runtime, the dispatch core, and an in-memory event-source/sink pair. Everything else — file tailing, Kafka, Spring config, JDBC, REST admin — lives here.

## Plugin catalogue

### Connectors (event sources + sinks)

| Plugin | Pattern | Read it for | Maven artifact |
|---|---|---|---|
| [connector-file](connector/connector-file/) | File tailing (source) + append-only file (sink) | Replayable single-process pipelines, JSONL logs, replay capture. Auto-creates parent dirs; durable read pointer for `COMMITED` strategy. | `connector-file` |
| [connector-chronicle](connector/connector-chronicle/) | Chronicle Queue + Chronicle Map | Microsecond-latency persistent log on disk; binary, memory-mapped, indexed. Better than file for high-throughput single-host. | `connector-chronicle` |
| [connector-kafka](connector/connector-kafka/) | Apache Kafka producer + consumer | Cross-process / cross-host messaging via a real broker. Use when you've outgrown single-host. | `connector-kafka` |
| [connector-multicast](connector/connector-multicast/) | UDP multicast source + sink | LAN-scoped pubsub without a broker. Discovery, heartbeats, test rigs. No persistence. | `connector-multicast` |
| [connector-aeron](connector/connector-aeron/) | Aeron live + archive replay source, Aeron sink | Sub-microsecond IPC and UDP transport. Cold-start replay from Aeron Archive. Single-host or LAN. | `connector-aeron` |

### Services (cross-cutting capabilities)

| Plugin | Pattern | Read it for | Maven artifact |
|---|---|---|---|
| [svc-cache](service/svc-cache/) | Key/value lookup (in-memory + JSON-backed) | Reference data sharing across processors, optionally persistent. JsonFileCache auto-creates parent dirs and handles bare-basename paths. | `svc-cache` |
| [svc-jdbc](service/svc-jdbc/) | Named JDBC connection registry | Pluggable DB access from any processor via `@ServiceRegistered`. Optional startup connectivity test. | `svc-jdbc` |
| [svc-loader-yaml](service/svc-loader-yaml/) | Load processors at runtime from YAML or Java source | Reload graphs without restarting the server. Compiled or interpreted modes. | `svc-loader-yaml` |
| [svc-loader-spring](service/svc-loader-spring/) | Load processors at runtime from Spring XML | Same as svc-loader-yaml but Spring XML as the source-of-truth. | `svc-loader-spring` |
| [svc-admin-telnet](service/svc-admin-telnet/) | Telnet admin endpoint with JLine completion + history | Interactive admin from a shell; pair with loader / cache plugins. | `svc-admin-telnet` |
| [svc-admin-rest](service/svc-admin-rest/) | Javalin-based REST admin endpoint | Curl-from-CI, browser dashboards, same admin surface as telnet over HTTP. Can host static SPA files. | `svc-admin-rest` |
| [svc-admin-web](service/svc-admin-web/) | Browser admin & monitoring SPA | Same admin surface as telnet/rest plus a dashboard, live JVM monitor + log tail over WebSocket, and conditional cache/loader panels. | `svc-admin-web` |

### Libraries (shared utilities)

| Plugin | Pattern | Read it for | Maven artifact |
|---|---|---|---|
| [lib-jsonserialiser](library/lib-jsonserialiser/) | Type-discriminated JSONL deserialiser | Drop-in `valueMapper` for file or socket feeds carrying mixed event types. | `lib-jsonserialiser` |
| [mongoose-test-support](test-support/mongoose-test-support/) | `MongooseTestHarness` for integration tests | Boot a real server in 5 lines, await with timeouts, `AutoCloseable` cleanup. | `mongoose-test-support` |

## Common usage shape

All plugins target Mongoose's standard config model — either programmatic (`MongooseServerConfig.builder()`) or YAML loaded by `MongooseServer.bootServer(...)`. Each per-plugin README has a config snippet; the canonical pattern is:

```yaml
eventFeeds:
  - name: my-feed
    instance: !!com.telamin.mongoose.plugin.connector.file.FileEventSource
      filename: ./data-in/events.jsonl
      readStrategy: COMMITED
    broadcast: true
    valueMapper: !!com.telamin.mongoose.plugin.lib.json.TypeSerialiser {}
    agentName: file-source-agent
    idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}

eventSinks:
  - name: my-sink
    instance: !!com.telamin.mongoose.plugin.connector.file.FileMessageSink
      filename: ./data-out/out.jsonl

services:
  - name: state-cache
    instance: !!com.telamin.mongoose.plugin.svc.cache.JsonFileCache
      fileName: ./data-out/state.json

  - name: adminTelnet
    instance: !!com.telamin.mongoose.plugin.svc.admintelnet.TelnetAdminCommandProcessor
      listenPort: 2024
```

## Operational notes that apply across plugins

These are lessons baked into every plugin in this repo as of this revision — the connector and cache plugins all enforce them, and tests exist to prevent regressions.

1. **`SleepingMillisIdleStrategy`, not `BackoffIdleStrategy`, in examples.** `BackoffIdleStrategy` spins on idle and can flood the agent's `doWork()` with per-call lambda allocations. The cure was applied upstream in mongoose (see `DeadWheelScheduler` field-cached `TimerHandler`), but the recommendation still holds for any plugin you author: don't put `BackoffIdleStrategy` in a sample config someone might paste.
2. **Null / empty file paths throw early.** Every plugin that takes a file path raises `IllegalStateException` from its lifecycle method (`start()` / `init()`) rather than NPE'ing deeper in. Defensive validation at the boundary, not silent stubs.
3. **Parent directories are auto-created.** `mkdirs()` is called before any file is opened or memory-mapped. Bare basename file names (no parent path) are handled — no NPE on `getParentFile()`.
4. **JDK 21+ needs `--add-opens java.base/jdk.internal.misc=ALL-UNNAMED`.** Plugins that pull in agrona (most of them, transitively via mongoose) need this surefire arg. The parent pom already wires it; if you launch with a custom command line, copy from `pom.xml`'s `<argLine>`.

## Module structure

```
mongoose-plugins/
├── pom.xml                     ← parent, version + properties + shared deps
├── connector/
│   ├── connector-aeron/
│   ├── connector-file/
│   ├── connector-chronicle/
│   ├── connector-kafka/
│   └── connector-multicast/
├── service/
│   ├── svc-cache/
│   ├── svc-jdbc/
│   ├── svc-loader-yaml/
│   ├── svc-loader-spring/
│   ├── svc-admin-telnet/
│   ├── svc-admin-rest/
│   └── svc-admin-web/
├── library/
│   └── lib-jsonserialiser/
└── test-support/
    └── mongoose-test-support/
```

## Building locally

```bash
mvn clean install           # all modules
mvn clean install -pl connector/connector-file  # one module
mvn -pl service/svc-cache -am test              # one module + deps, tests only
```

Build status: all 14 modules compile and tests pass on JDK 21+ as of mongoose `1.0.8` / fluxtion `0.9.33`.

## Versioning

This repo tracks the Mongoose minor cycle. Currently `0.2.8-SNAPSHOT`, depending on `com.telamin:mongoose:1.0.8` and `com.telamin.fluxtion:fluxtion-builder:0.9.33`. Bump these centrally in the parent `pom.xml` when you update Mongoose.

## License

AGPL-3.0-only. Each plugin file carries an SPDX header.

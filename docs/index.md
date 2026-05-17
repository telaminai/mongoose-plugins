---
hide:
  - navigation
  - toc
---

<div class="plugin-hero" markdown>
<div markdown>

# Mongoose Plugins

<p class="lede">
A curated catalogue of plugins for the <a href="https://github.com/telaminai/mongoose">Telamin Mongoose</a>
deterministic event-processing server. Connectors, services, and libraries —
each an independent Maven module, each pluggable into a Mongoose deployment via
YAML, Spring XML, or plain Java config.
</p>

[Get started :material-arrow-right:](getting-started.md){ .md-button .md-button--primary }
[Browse architecture](architecture.md){ .md-button }

</div>
<dl class="hero-stat">
  <dt>Plugins</dt>
  <dd>13</dd>
  <dt>Categories</dt>
  <dd>Connectors · Services · Libraries</dd>
  <dt>License</dt>
  <dd>AGPL-3.0-only</dd>
  <dt>Latest</dt>
  <dd>0.2.8-SNAPSHOT</dd>
</dl>
</div>

---

## Connectors

Event sources, message sinks, and bridges to the outside world.

<div class="grid cards" markdown>

-   :material-flash: **[connector-aeron](connectors/aeron.md)**

    <span class="plugin-tags"><span class="plugin-tag source">source</span><span class="plugin-tag sink">sink</span><span class="plugin-tag lowlat">low-latency</span><span class="plugin-tag replay">archive replay</span></span>

    Sub-microsecond IPC + UDP transport. Live subscribe or archive replay. Single-host or LAN. Drop-in pair: `AeronArchiveEventSource` + `AeronMessageSink`.

-   :material-file-document-outline: **[connector-file](connectors/file.md)**

    <span class="plugin-tags"><span class="plugin-tag source">source</span><span class="plugin-tag sink">sink</span><span class="plugin-tag replay">replayable</span></span>

    File tail (`FileEventSource`) + append-only sink (`FileMessageSink`) with size/time-based rotation. Replayable single-process pipelines, JSONL logs.

-   :material-database-clock-outline: **[connector-chronicle](connectors/chronicle.md)**

    <span class="plugin-tags"><span class="plugin-tag source">source</span><span class="plugin-tag sink">sink</span><span class="plugin-tag lowlat">low-latency</span><span class="plugin-tag persist">persistent</span></span>

    [Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue) + [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map): microsecond-latency persistent log + off-heap key/value, memory-mapped on disk.

-   :material-arrow-decision-outline: **[connector-kafka](connectors/kafka.md)**

    <span class="plugin-tags"><span class="plugin-tag source">source</span><span class="plugin-tag sink">sink</span><span class="plugin-tag broker">broker</span></span>

    Cross-process / cross-host messaging via a real Kafka broker. Producer flushes on shutdown via JVM hook; consumer wakes from poll cleanly.

-   :material-radio-tower: **[connector-multicast](connectors/multicast.md)**

    <span class="plugin-tags"><span class="plugin-tag source">source</span><span class="plugin-tag sink">sink</span></span>

    UDP multicast source + sink. LAN-scoped pubsub without a broker — discovery, heartbeats, test rigs. No persistence.

</div>

## Services

Cross-cutting capabilities shared across processors.

<div class="grid cards" markdown>

-   :material-database-cog-outline: **[svc-jdbc](services/jdbc.md)**

    <span class="plugin-tags"><span class="plugin-tag service">service</span><span class="plugin-tag persist">pooled</span></span>

    Named JDBC connection registry backed by HikariCP. Per-entry pool sizing, validation query, leak-test proven. Inject via `JdbcConnectionLoader`.

-   :material-cached: **[svc-cache](services/cache.md)**

    <span class="plugin-tags"><span class="plugin-tag service">service</span><span class="plugin-tag persist">optional persist</span></span>

    In-memory + JSON-persistent caches. Optional LRU `maxSize` with eviction metric. Use for shared reference data warmed at startup.

-   :material-console-network-outline: **[svc-admin-telnet](services/admin-telnet.md)**

    <span class="plugin-tags"><span class="plugin-tag service">service</span></span>

    JLine-backed telnet admin endpoint. Loopback bind by default. Tab-complete, history. Pair with the loader / cache plugins.

-   :material-web: **[svc-admin-rest](services/admin-rest.md)**

    <span class="plugin-tags"><span class="plugin-tag service">service</span></span>

    Javalin REST admin endpoint with BASIC / BEARER auth (constant-time compare). Hostable static SPA directory.

-   :material-file-code-outline: **[svc-loader-yaml](services/loader-yaml.md)**

    <span class="plugin-tags"><span class="plugin-tag service">service</span></span>

    Load and reload processors at runtime from YAML or Java source. Compiled or interpreted modes.

-   :material-spring: **[svc-loader-spring](services/loader-spring.md)**

    <span class="plugin-tags"><span class="plugin-tag service">service</span></span>

    Load and reload processors at runtime from Spring XML. Same surface as svc-loader-yaml but with Spring XML as source-of-truth.

</div>

## Libraries

Shared utilities used across plugins and applications.

<div class="grid cards" markdown>

-   :material-code-json: **[lib-jsonserialiser](libraries/jsonserialiser.md)**

    <span class="plugin-tags"><span class="plugin-tag library">library</span></span>

    Type-discriminated JSONL deserialiser. Drop in as `valueMapper` on a file or socket feed carrying mixed event types.

</div>

---

## Quick start

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-file</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

```yaml
eventFeeds:
  - name: trades
    instance: !!com.fluxtion.dataflow.serverplugin.connector.file.FileEventSource
      filename: ./data-in/trades.jsonl
      readStrategy: COMMITED
    broadcast: true
    valueMapper: !!com.fluxtion.dataflow.serverplugin.lib.json.TypeSerialiser {}
```

See [Getting started](getting-started.md) for the full setup, including how to wire plugins into a `MongooseServerConfig`.

## Conventions baked into every plugin

<div class="callout-row" markdown>
<div class="callout" markdown>

#### Fail-fast config

Empty/null paths, ports, and credentials throw `IllegalStateException` from `init()` — not deeper at first use.

</div>
<div class="callout" markdown>

#### Idempotent teardown

Every `tearDown()` is safe to call twice. Resources are nulled after close.

</div>
<div class="callout" markdown>

#### Parent dirs auto-created

File-based plugins call `mkdirs()` before opening. Bare basenames work — no `getParentFile()` NPE.

</div>
<div class="callout" markdown>

#### JDK 21+, agrona-aware

`--add-opens java.base/jdk.internal.misc=ALL-UNNAMED` wired in the parent POM. Plugins target Java 21 and run on JDK 25.

</div>
</div>

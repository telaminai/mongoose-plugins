# svc-micrometer

Micrometer bridge for Mongoose's `MongooseCountersService` and
`MongooseLatencyService`. Mounts a poll-driven snapshotter that publishes
Mongoose runtime metrics into a user-supplied `MeterRegistry`, so any
Micrometer backend (Prometheus, Datadog, StatsD, OTLP, CloudWatch, …)
sees Mongoose counters + latencies alongside the rest of the JVM's
application + infrastructure metrics.

## Why this exists

Mongoose ships counters + HDR-histogram latency data via its own admin
console (svc-admin-web). Production ops teams live in Prometheus /
Grafana / Datadog dashboards — they don't open a per-service admin UI
to read counters. This module bridges the gap: drop the jar on the
classpath, wire it in YAML, and the same data you see on the admin
console's dashboard appears in your existing observability stack with
the rest of your fleet's metrics.

## Wiring

```yaml
services:
  - name: micrometerBridge
    service: !!com.telamin.mongoose.plugin.svc.micrometer.MicrometerBridge
      sampleIntervalMs: 1000       # snapshot cadence, default 1000ms
      counterPrefix:   mongoose    # name prefix on counter meters
      latencyPrefix:   mongoose.latency
```

The bridge picks up a `MeterRegistry` via `@ServiceRegistered`. Spring
Boot autoconfig, Quarkus, or any container that exposes one will have
it injected automatically. Standalone Mongoose deployments without a
container can register one manually:

```yaml
services:
  - name: meterRegistry
    serviceClass: io.micrometer.core.instrument.MeterRegistry
    service: !!io.micrometer.core.instrument.simple.SimpleMeterRegistry {}
```

If no registry is injected, the bridge falls back to a
`SimpleMeterRegistry` so gauges are queryable in-process — useful for
smoke tests and small embedded deployments, but operators in real
production should wire a backend-specific registry.

## Backends

The module imports `micrometer-core` only — no specific backend. Add
the Micrometer registry artefact for your stack on the runtime
classpath:

```xml
<!-- Prometheus -->
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
  <version>1.13.6</version>
</dependency>

<!-- Datadog, StatsD, OTLP, CloudWatch, etc — see Micrometer docs. -->
```

Then wire that registry as the `meterRegistry` service in YAML.

## Name + tag mapping

Counter labels in Mongoose are flat dot-separated strings:

```
feed.prices.published
processor.pnl-processor.invocations
node.pnl-processor.pnlSummaryCalc_3.invocations
app.trade.count
```

The bridge passes these through verbatim, prefixed with
`counterPrefix`:

| Mongoose label                            | Micrometer meter name                                   | Prometheus exposition                                   |
|-------------------------------------------|---------------------------------------------------------|---------------------------------------------------------|
| `feed.prices.published`                   | `mongoose.feed.prices.published`                        | `mongoose_feed_prices_published`                        |
| `processor.pnl-processor.invocations`     | `mongoose.processor.pnl-processor.invocations`          | `mongoose_processor_pnl_processor_invocations`          |
| `app.trade.count`                         | `mongoose.app.trade.count`                              | `mongoose_app_trade_count`                              |

Latency snapshots register five gauges per `(processor, node)` row,
tagged with `processor` and `node`:

```
mongoose.latency.p50  {processor="pnl-processor", node="pnlSummaryCalc_3"}
mongoose.latency.p99  {processor="pnl-processor", node="pnlSummaryCalc_3"}
mongoose.latency.p999 {processor="pnl-processor", node="pnlSummaryCalc_3"}
mongoose.latency.max  {processor="pnl-processor", node="pnlSummaryCalc_3"}
mongoose.latency.count{processor="pnl-processor", node="pnlSummaryCalc_3"}
```

## Hot-path cost

The hot path (counter increment, latency record) is untouched. The
bridge runs on a single daemon thread (`mongoose-micrometer-bridge`)
and snapshots once per `sampleIntervalMs`. Each snapshot is O(N)
where N is the number of counters + latency rows — both small in
practice. Micrometer gauges read from in-memory `AtomicLong`s on
scrape, so the export path (Prometheus pull / Datadog push / …) is
also non-blocking.

## Limitations (V1)

- Counter names are pass-through; no automatic tag extraction from
  embedded names like `processor.<name>.invocations`. Tag-based
  cardinality control is a follow-up.
- Latency histograms surface as four percentile gauges +
  count — Micrometer `Timer` would require per-sample recording,
  which doesn't match Mongoose's pre-aggregated snapshot model.
- No Mongoose-specific Micrometer `MeterFilter` shipped. Operators
  can register their own filters against the bridge's registry.

## Maven coordinates

```xml
<dependency>
  <groupId>com.telamin</groupId>
  <artifactId>svc-micrometer</artifactId>
  <version>1.0.21-SNAPSHOT</version>
</dependency>
```

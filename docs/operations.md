# Operational guide

Things that bite in production. The plugins are wired for these defaults already; this page explains the "why" so you can deviate when you genuinely need to.

## JVM flags

Plugins that touch agrona (most, transitively via mongoose) need `--add-opens` on JDK 21+:

```
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED
```

The plugin parent POM sets this for Surefire — copy the line into your application launcher script.

`io.aeron:aeron-client` 1.48 specifically requires this on JDK 25, because `Unsafe.arrayBaseOffset(Class)` changed return type from `int` to `long`.

## Why not BackoffIdleStrategy?

`BackoffIdleStrategy` busy-spins on idle and re-evaluates the agent's `doWork()` ~10⁶ times per second. Anything that allocates a bound method reference per call (e.g. `this::onTick`) generates a `Method$$Lambda` per invocation, swamping the young generation and triggering frequent GC.

Use `SleepingMillisIdleStrategy(1)` in samples and production. The sub-millisecond loss in tick latency is invisible compared to a GC pause.

Fix recipe if you see `OutOfMemoryError: Java heap space` from a tight agent loop:

1. Find any `this::method` or capturing lambda passed into `doWork`-adjacent paths.
2. Cache it as a `private final` field on the holder.
3. Pass the field, not the method-reference expression.

This pattern is enforced by `DeadWheelScheduler.onTimerExpiryHandler` upstream in mongoose `1.0.8`.

## Idle strategies and CPU usage

| Strategy                          | CPU usage | When |
|-----------------------------------|-----------|------|
| `BusySpinIdleStrategy`            | 100%      | Lowest-latency trading paths only. Pin to dedicated cores. |
| `BackoffIdleStrategy`             | High      | **Avoid.** See above. |
| `YieldingIdleStrategy`            | High      | Latency-sensitive but co-located with other agents. |
| `SleepingMillisIdleStrategy(1)`   | Low       | **Default.** Sub-ms tick, well-behaved CPU. |
| `SleepingMillisIdleStrategy(10)`  | Very low  | Admin endpoints, low-rate feeds. |

## Shutdown sequencing

Mongoose calls `tearDown()` in reverse-startup order. Plugins must:

1. Flush any buffered state (`producer.flush()`, `printStream.flush()`).
2. Close external handles (sockets, files, DataSources).
3. Null fields so a re-init (e.g. in tests) reopens cleanly.
4. Return without throwing.

The Kafka publisher additionally installs a **JVM shutdown hook** by default — if the JVM aborts before mongoose teardown runs, buffered records still flush within `closeTimeoutMs` (default 5 s).

## Auth and binding

By default:

- `svc-admin-telnet` binds to `127.0.0.1`. Override only if you're behind a TLS proxy.
- `svc-admin-rest` binds to `0.0.0.0`. **Set `host: 127.0.0.1` for production**, and enable `authMode: BASIC` or `BEARER`.
- `svc-admin-web` binds to `127.0.0.1` by default. For multi-host access, change explicitly and front with TLS. Enable `authMode: BASIC`/`BEARER` and pin `sessionSecret` via env so cookies survive restarts. WS upgrades enforce an `Origin` allow-list — set this when fronting with a reverse proxy.
- Secrets resolve `$ENV.NAME` — environment first, system property second.

Constant-time string comparison is used for auth — credential timing leaks are off the table.

## File-based plugins

- All file paths auto-create their parent directories (`mkdirs()`) before opening.
- Bare basenames (no `/`) work — no `getParentFile()` NPE.
- `FileMessageSink` supports rotation by size and/or wall-clock interval. Backups beyond `maxBackupFiles` are deleted oldest-first.

## Database pooling

`svc-jdbc` defaults to HikariCP with `maximumPoolSize=10`, `minimumIdle=0`. Tune per environment:

- Web workloads: `maximumPoolSize ≈ 2 × cores`.
- Batch / ETL: lower (e.g. 2–4), longer `maxLifetimeMs`.
- Pair with a `validationQuery: "SELECT 1"` if your DB closes idle connections silently (cloud-hosted Postgres sometimes does).

`pooled: false` falls back to `DriverManager.getConnection` per call — handy for tests, never for production.

## Replay-safe sources

The following sources are designed to replay deterministically across restarts:

- `connector-file` — `readStrategy: COMMITED` uses a durable offset file.
- `connector-chronicle` — replay-from-index is built into Chronicle Queue.
- `connector-aeron` (archive mode) — `AeronArchiveEventSource.Mode.ARCHIVE` replays from the latest recording.

Pair them with file or chronicle **sinks** that record the dispatcher's outputs. The combination gives you full deterministic re-runs for audit and back-test.

## Observability

Plugins expose simple counters via `getXxxCount()` methods. Wire them into your metrics layer (Micrometer, Dropwizard, OpenTelemetry) — none of the plugins emit metrics themselves to keep dependencies thin.

Typical counters:

- `KafkaMessagePublisher`: `getSendCount()`, `getSendErrors()`, `getPublishedCount()` _(Aeron sink)_, `getNotConnectedRetryCount()` _(Aeron sink)_.
- `InMemoryCache` / `JsonFileCache`: `getEvictedCount()`.

## Bootstrap-time validation

Every plugin's `init()` throws `IllegalStateException` for missing/invalid config — paths, ports, credentials, pool sizes. The server refuses to start rather than NPE'ing on first request.

Trade-off: if you launch with a half-finished config it fails immediately. That's the intent.

## License

All plugins are AGPL-3.0-only. Each file carries an SPDX header. Confirm your project's license obligations before shipping a fork.

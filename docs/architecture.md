# Architecture

Mongoose is a deterministic event-processing server. Plugins are how external systems plug into the dispatch pipeline — and how cross-cutting capabilities (admin, caching, DB access) are shared across processors.

## Where plugins fit

```
              ┌─────────────────────────────────────────────────┐
              │                  Mongoose server                │
              │                                                 │
   ┌──────┐   │   ┌────────────┐    ┌──────────┐    ┌───────┐   │   ┌──────┐
   │ feed │──►│──►│ EventSource│───►│Dispatcher│───►│Sinks  │──►│──►│ out  │
   └──────┘   │   └────────────┘    └────┬─────┘    └───────┘   │   └──────┘
              │                          │                      │
              │                          ▼                      │
              │                    ┌──────────┐                 │
              │                    │Processors│                 │
              │                    └────┬─────┘                 │
              │           ┌─────────────┴────────────┐          │
              │           ▼                          ▼          │
              │      ┌─────────┐                ┌─────────┐     │
              │      │ Service │                │ Service │     │
              │      │ (cache) │                │ (jdbc)  │     │
              │      └─────────┘                └─────────┘     │
              └─────────────────────────────────────────────────┘
                              ▲
                              │
                       ┌──────┴───────┐
                       │ Admin REST/  │
                       │ telnet, etc. │
                       └──────────────┘
```

The three plugin categories map onto distinct insertion points:

- **Connectors** (`EventSource` / `MessageSink` implementations) plug into the dispatcher's input and output sides.
- **Services** are injected into processors via `@ServiceRegistered`. They live alongside the dispatcher and are visible from any handler.
- **Libraries** provide value-mapping, serialisation, and other utility code used by connectors and processors.

## Lifecycle contract

Every plugin implements one or both of `com.telamin.fluxtion.runtime.lifecycle.Lifecycle` and the agrona `Agent` interface.

| Hook          | When           | Plugins do                                    |
|---------------|----------------|-----------------------------------------------|
| `init()`      | Server boot    | Validate config, allocate buffers/handles. Throw `IllegalStateException` for bad config — fail fast. |
| `start()`     | After init     | Open external resources (sockets, file handles, pools). |
| `doWork()`    | Agent tick     | Poll inputs, flush outputs, advance internal state machines. |
| `onStart()`   | Source ready   | Begin publishing events to the queue (after the queue is wired). |
| `startComplete()` | Dispatch on | Switch from cache mode to publish-direct mode. |
| `tearDown()`  | Shutdown       | Close resources. Must be idempotent. |

The Mongoose server runs each plugin on a configured idle strategy (`SleepingMillisIdleStrategy` is the recommended default — see the [operational guide](operations.md#why-not-backoffidlestrategy)).

## Configuration models

Plugins are POJOs with setters; the actual wiring is done in one of three formats:

- **YAML** (via `svc-loader-yaml` or `MongooseServer.bootServer(yaml)`) — most common in production.
- **Java** (via `MongooseServerConfig.builder()`) — typed, refactor-safe, used in tests and embedded apps.
- **Spring XML** (via `svc-loader-spring`) — bridges from existing Spring-XML stacks; carrier for the regulated-industry "external authoring" pattern.

All three reach the same internal `MongooseServerConfig` shape, so a plugin authored against one is automatically usable from the others.

## Threading model

- Each connector and service runs on its **own agent thread** (Agrona's `AgentRunner`). One thread per plugin instance.
- Sinks are **single-writer**. `sendToSink` is serialised by the dispatcher; plugin authors don't need locks on output paths.
- Sources publish into queues that the dispatcher drains. The agent thread polls the source's `doWork()` between dispatch cycles.
- Services are **multi-reader, single-writer** by convention — invoked from the dispatch thread when responding to handler hooks.

## Source-to-sink event flow

1. A connector's `doWork()` polls the external system (file tail, Kafka, Aeron channel).
2. New events are mapped via the configured `valueMapper` (e.g. `TypeSerialiser` from `lib-jsonserialiser`).
3. The dispatcher routes the event to subscribed processors based on type or feed name.
4. Processors run `@OnEvent` / `@OnTrigger` handlers, calling out to services as needed.
5. Output is written to one or more sinks via `EventToInvokeStrategy` calls.
6. The sink's agent thread flushes the queue (`producer.flush()`, `printStream.flush()`, etc).

## Plugin authoring rules

If you're writing a new plugin, model it on the existing set:

1. **Extend the right base class** — `AbstractAgentHostedEventSourceService` for sources, `AbstractMessageSink<T>` for sinks, plain `Service` interface (with `@ServiceRegistered`) for services.
2. **Validate config in `init()`**. Throw `IllegalStateException` with a clear message. No NPEs.
3. **Auto-create parent directories** for any file path before opening.
4. **Make `tearDown()` idempotent.** Null the field after close; check for null on entry.
5. **Expose metrics** as `getXxxCount()` getters returning `long`. Counters are AtomicLong fields; expose via the getter, not the field, to keep the API typed.
6. **Use `SleepingMillisIdleStrategy` in sample configs** — never `BackoffIdleStrategy`. The latter spins and allocates per-call lambdas.

## Related reading

- [Operational guide](operations.md) for production checklist.
- The [Mongoose architecture docs](https://github.com/telaminai/mongoose/tree/main/mongoose/docs/architecture) cover the dispatcher and processor internals.

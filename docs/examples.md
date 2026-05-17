# Examples

Runnable demonstrations live in the companion repo: [telaminai/mongoose-examples](https://github.com/telaminai/mongoose-examples).

Each example is a self-contained Maven module. Clone, run `mvn package`, then launch the `Main` class — or, for YAML-driven examples, run the bundled fat-jar with `-DmongooseServer.config.file=...`.

## Tutorial examples

These walk through the canonical setup end-to-end. Best starting point if you're new.

| Example | What it shows | Plugins demonstrated |
|---|---|---|
| [getting-started/five-minute-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/five-minute-tutorial) | Programmatic `MongooseServerConfig` boot, in-memory feeds, sink, and a filter handler. | `InMemoryEventSource` (core) + `InMemoryMessageSink` (core) |
| [getting-started/five-minute-yaml-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/five-minute-yaml-tutorial) | Same flow, but driven from `appConfig.yml`. Demonstrates the YAML config shape used by `svc-loader-yaml`. | [`connector-file`](connectors/file.md) (source) |
| [getting-started/app-integration-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/app-integration-tutorial) | PnL calculator + data-generator across two servers with full file I/O. Includes both Java and YAML config variants. | [`connector-file`](connectors/file.md) (source + sink) |
| [getting-started/stream-programming-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/stream-programming-tutorial) | DSL-style stream graph composition. | core in-memory feeds |

## How-to recipes

Short focused recipes for specific patterns:

| Recipe | Plugin / topic |
|---|---|
| [how-to/data-mapping](https://github.com/telaminai/mongoose-examples/tree/main/how-to/data-mapping) | `valueMapper` chains — natural home for [`lib-jsonserialiser`](libraries/jsonserialiser.md). |
| [how-to/subscribing-to-named-event-feeds](https://github.com/telaminai/mongoose-examples/tree/main/how-to/subscribing-to-named-event-feeds) | Multi-feed dispatch — applies to every connector source. |
| [how-to/writing-an-admin-command](https://github.com/telaminai/mongoose-examples/tree/main/how-to/writing-an-admin-command) | Register a command and invoke it from [`svc-admin-rest`](services/admin-rest.md) or [`svc-admin-telnet`](services/admin-telnet.md). |
| [how-to/replay](https://github.com/telaminai/mongoose-examples/tree/main/how-to/replay) | Cold-start replay flow — pairs with [`connector-file`](connectors/file.md) and [`connector-aeron`](connectors/aeron.md) (archive mode). |
| [how-to/using-the-scheduler-service](https://github.com/telaminai/mongoose-examples/tree/main/how-to/using-the-scheduler-service) | Time-based triggers in processors. |
| [how-to/injecting-config-into-a-processor](https://github.com/telaminai/mongoose-examples/tree/main/how-to/injecting-config-into-a-processor) | Per-processor config injection. |

## Connector examples

End-to-end runnable demos of specific connectors:

| Example | Plugin |
|---|---|
| [plugins/connector-aeron-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/connector-aeron-example) | [`connector-aeron`](connectors/aeron.md) — in-memory → AeronMessageSink → embedded IPC → AeronArchiveEventSource → capture sink, asserted via `MongooseTestHarness`. |

## Authoring your own plugin

If you want to write a new connector, sink, or service that fits the catalogue, the `plugins/` examples are the templates:

| Template | Use as a starting point for |
|---|---|
| [plugins/event-source-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-example) | New source — agent-hosted, polls an external system in `doWork()`. |
| [plugins/event-source-nonagent-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-nonagent-example) | New source — push-based, no agent loop. |
| [plugins/message-sink-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/message-sink-example) | New sink — extends `AbstractMessageSink<T>`. |
| [plugins/service-plugin-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/service-plugin-example) | New service — injected via `@ServiceRegistered`. |

See the [authoring rules in Architecture](architecture.md#plugin-authoring-rules) before writing one for production.

## Per-plugin example index

The catalogue's per-plugin pages also list the relevant examples at the bottom. If an example you'd find useful is missing, [open an issue](https://github.com/telaminai/mongoose-plugins/issues) — those gaps are the highest-priority docs work.

# Migration & namespace plan

The Mongoose ecosystem currently spans three Java package namespaces.  This page
documents why, and the planned consolidation timeline.

## Current state

| Namespace                                       | Where                          | Status        |
|-------------------------------------------------|--------------------------------|---------------|
| `com.telamin.mongoose.*`                        | mongoose core runtime          | **Canonical** |
| `com.telamin.mongoose.connector.file.*`         | mongoose core (built-in file connector) | Duplicate of plugin |
| `com.telamin.mongoose.connector.memory.*`       | mongoose core (in-memory feed / sink)   | **Canonical** |
| `com.fluxtion.dataflow.serverplugin.*`          | most of `mongoose-plugins`     | Legacy        |
| `com.fluxtion.server.plugin.connector.aeron.*`  | `connector-aeron` (recently ported) | Legacy variant |

The split reflects history, not design:

- mongoose-core has historically shipped a built-in file connector to keep the
  five-minute tutorial single-jar.
- mongoose-plugins originated as `gregv12/fluxtion-server-plugins` and inherited
  the `com.fluxtion.dataflow.serverplugin.*` package tree from that lineage.
- `connector-aeron` was ported recently from the same upstream and inherited
  a slightly different sub-namespace (`com.fluxtion.server.plugin.*`).

## Why it matters

- A new user landing on Maven Central sees three apparent "Fluxtion" packages
  and doesn't immediately know which to depend on.
- The `mongoose-examples` repo references the core file connector
  (`com.telamin.mongoose.connector.file.FileEventSource`); this catalogue
  documents the plugin form
  (`com.fluxtion.dataflow.serverplugin.connector.file.FileEventSource`).
  Both work; the catalogue is the documented-going-forward shape.
- YAML configs in production today reference whichever was current at
  install time. Renames break those configs.

## Consolidation plan

### Phase 1 — pre-HN (current)

**Document the situation, don't rename.** The connectors all work today.
Renaming pre-launch risks breaking the active POC deployments and the
mongoose-examples repo. This page (and per-page notes on the catalogue)
makes the situation explicit.

### Phase 2 — mongoose 1.1.0

- Promote the `mongoose-plugins` namespace to `com.telamin.mongoose.plugin.*`
  alongside the legacy package. Both work, the legacy package emits a
  `@Deprecated` notice with a one-version sunset window.
- Rename `connector-aeron` from `com.fluxtion.server.plugin.connector.aeron`
  to `com.telamin.mongoose.plugin.connector.aeron`. Same dual-export pattern.
- Annotate the core file connector
  (`com.telamin.mongoose.connector.file.*`) `@Deprecated` and direct
  users to `connector-file`. Same source; one published artefact.

### Phase 3 — mongoose 2.0.0

- Remove the `com.fluxtion.dataflow.serverplugin.*` and
  `com.fluxtion.server.plugin.*` packages from `mongoose-plugins`.
- Remove the duplicated file connector from `mongoose-core`. Users now have
  one canonical path: `connector-file`.
- YAML configs need a single-line update; provide a sed recipe in the
  release notes.

## What you can do today

If you're starting fresh and want to be on the right side of the future:

- Pick the **plugin** form of any connector (`com.fluxtion.dataflow.serverplugin.connector.file.FileEventSource`),
  not the core form. The plugin is the published-catalogue target.
- For `connector-aeron`, use `com.fluxtion.server.plugin.connector.aeron.*`
  — this is the form documented today.
- Pin to `mongoose-plugins:0.2.8-SNAPSHOT` (or whatever the next release is)
  alongside `mongoose:1.0.8`.

## What stays stable

- `com.telamin.mongoose.MongooseServer` — the boot entry point. Unchanged
  across all phases.
- `com.telamin.mongoose.config.*` — `MongooseServerConfig`, `EventFeedConfig`,
  `EventSinkConfig`, `ServiceConfig`. Unchanged.
- `com.telamin.mongoose.connector.memory.*` — `InMemoryEventSource`,
  `InMemoryMessageSink`. Unchanged.
- `com.telamin.fluxtion.runtime.*` — the Fluxtion runtime annotations
  (`@ServiceRegistered`, `ObjectEventHandlerNode`). Unchanged.

The unstable surface is purely the plugin-namespace prefix on connector and
service implementations. The lifecycle contract, the config model, and the
injection API are all already on the canonical namespace.

## Open question: dual-export bridge

A bridge class (legacy package extends canonical-package, empty body) would
let both YAML strings resolve to the same code during phase 2. It's the
cheapest migration path. The cost is a doubled class hierarchy that some
classpath tools find confusing. Decision deferred to phase-2 planning.

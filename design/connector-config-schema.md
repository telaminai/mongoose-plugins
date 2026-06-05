# Connector / service config schema — generation & publishing

**Status**: Draft spec
**Repo**: `mongoose-plugins` (the source of truth for the plugin classes)
**Consumers**: the Fluxtion **Project Starter** (`fluxtion-web/docs/project-starter`,
§11.1) for its feed/sink/service config editors; the **mongoose-plugins docs site**
(`https://telaminai.github.io/mongoose-plugins/`) as a human-readable config
reference; potentially `svc-admin-web` (which already does a runtime variant).
**Owners**: Greg

---

## 1. Why

The Project Starter (and anything that scaffolds a Mongoose descriptor) needs to
know, **at build/author time with no server running**, the config surface of each
connector and service: what fields a `FileEventSource` / `connector-kafka` /
`svc-cache` accepts, their types, defaults, allowed values. Today that knowledge
lives only in the Java classes (and scattered example YAML). There is no
machine-readable, versioned description.

`svc-admin-web` already reflects a *running* service's config for its detail view
(`GET /api/services/{name}/config`), but that needs a live server — useless for a
client-side generator or for static docs.

This spec defines a **build-time generator in `mongoose-plugins`** that emits a
versioned `schema.json` describing every connector/service config surface, and
**publishes it to the docs site** as both raw JSON (machine consumers) and a
rendered config-reference page (humans). One artefact, two audiences, can't drift
from the code because it's generated from it.

---

## 2. What it produces — the `schema.json` contract

A single JSON document, versioned to the `mongoose-plugins` release it describes.
This is the exact contract the Project Starter consumes (fluxtion-web §11.1).

```jsonc
{
  "schemaVersion": 1,
  "pluginsVersion": "1.0.30",          // == mongoose-plugins release
  "plugins": [
    {
      "artifactId": "connector-file",
      "kind": "source",                // source | sink | service | library
      "yamlKey": "eventFeeds",         // eventFeeds | eventSinks | services
      "yamlBindKey": "instance",       // "instance" for feeds/sinks, "service" for services
      "instanceFqn": "com.telamin.mongoose.connector.file.FileEventSource",
      "doc": "Tail a file as an event source.",
      "fields": [
        { "name": "filename",     "type": "string",  "required": true,  "default": null,
          "doc": "Path to the file to tail.", "format": "path" },
        { "name": "readStrategy", "type": "enum",    "required": false, "default": "COMMITED",
          "enumValues": ["EARLIEST","LATEST","COMMITED","ONCE_EARLIEST","ONCE_LATEST"] },
        { "name": "cacheEventLog","type": "boolean", "required": false, "default": false }
      ]
    },
    {
      "artifactId": "svc-cache",
      "kind": "service",
      "yamlKey": "services",
      "yamlBindKey": "service",
      "instanceFqn": "com.telamin.mongoose.plugin.svc.cache.JsonFileCache",
      "fields": [
        { "name": "fileName", "type": "string", "required": true, "default": null, "format": "path" },
        { "name": "maxSize",  "type": "int",    "required": false, "default": 0,
          "doc": "0 = unbounded; >0 enables LRU eviction." }
      ]
    }
    // … one entry per connector / service / sink
  ]
}
```

Field `type` ∈ `string | int | long | double | boolean | enum | duration | nested`.
`nested` carries a `fields[]` of its own (e.g. `performanceMonitoring.auditCapture`).
`format` is an optional hint for the editor (`path`, `host`, `port`, `url`,
`millis`). `default` is the value a freshly no-arg-constructed instance reports.

---

## 3. How the schema is derived — the config-surface problem

A connector class has **many** fields, most of which are internal state, not config.
`FileEventSource` declares `filename, readStrategy, cacheEventLog, tail, commitRead`
(config) alongside `reader, buffer, offset, commitPointer, infoEnabled, …`
(runtime state). The generator must emit only the **config surface** — the
properties SnakeYAML actually binds when it reads a descriptor.

The bindable surface is the set of **JavaBean writable properties** (a public
`setX(...)`), because that's exactly what SnakeYAML uses. So:

1. **Discover** each plugin's instance class (see §4 for how the set is enumerated).
2. **Instantiate** via the no-arg constructor. (Vendor plugins honour the two-ctor
   contract — a no-arg ctor plus a field-matching one — so a defaults instance is
   always constructible. Classes without a usable no-arg ctor are reported, not
   silently dropped.)
3. **Walk writable bean properties** (`Introspector.getBeanInfo` →
   `PropertyDescriptor.getWriteMethod() != null`). For each:
   - `name` = property name.
   - `type` = mapped from the property type (`String`→string, primitives→their
     names, `enum`→`enum` + `enumValues` from the constants, a nested config
     POJO → `nested` recursing into its bean props, `Duration`/`*Ms`→`duration`).
   - `default` = read back via the getter on the fresh instance (null/0/false as
     applicable).
   - `required` = heuristic: no default (null) **and** no `@Nullable`/optional
     marker ⇒ required; refined by curated overrides (§3.1).
   - `doc` = from a `@ConfigDoc`/Javadoc source if available (§3.2), else absent.

This is the same reflection `svc-admin-web` does at runtime, lifted to build time
and run over a *fresh* instance instead of a live one.

### 3.1 Curated overrides — the escape hatch

Pure reflection over-reports (a setter that's really internal) and under-documents.
Ship a small hand-maintained `schema-overrides.json` in `mongoose-plugins` keyed by
`artifactId` → property, able to: hide a property, mark required/optional, add a
`doc`/`format`, pin enum subsets, or add a property reflection can't see. The
generator merges reflection output with overrides; the override file is the only
hand-maintained surface and is tiny.

### 3.2 Optional `@ConfigField` annotation (future, cleanest)

The durable answer is an opt-in annotation in mongoose-core, e.g.
`@ConfigField(doc = "...", required = true, format = PATH)` on the setter or field.
When present it's authoritative; absent, reflection + overrides apply. This also
lets the **substrate-lint** family flag config classes that ship undocumented
setters. Not required for v1 (overrides cover it) but the migration target.

---

## 4. Enumerating the plugin set

The generator needs the list of (artifactId → instance class, kind, yamlKey). Two
options:

- **(a) Convention scan** — each plugin module declares its connector/service
  classes; a `services`/`connectors` index per module (a `META-INF` service file or
  a generated `plugin-descriptor.json`) lists them. The generator aggregates across
  modules in a reactor build.
- **(b) Central manifest** — one `plugin-index.json` in the repo root mapping
  artifactId → {instanceFqn, kind, yamlKey, yamlBindKey}. Hand-maintained but small
  and rarely changes.

**Proposed: (b) for v1** (a dozen entries, explicit, reviewable), migrating to (a)
if/when plugins grow. The `kind`/`yamlKey`/`yamlBindKey` mapping is intrinsic and
belongs with the plugin anyway:

| kind | yamlKey | yamlBindKey | examples |
|---|---|---|---|
| source | `eventFeeds` | `instance` | connector-file/kafka/aeron/chronicle/multicast (source side) |
| sink | `eventSinks` | `instance` | …(sink side) |
| service | `services` | `service` | svc-cache, svc-jdbc, svc-admin-web, svc-loader-* |

> **`instance:` vs `service:` is load-bearing** — event feeds/sinks bind under
> `instance:`, services bind under `service:`; using the wrong key fails the boot
> ("Cannot create property=services"). The schema carries `yamlBindKey` so
> generators emit the right one.

---

## 5. Where it runs

A new module/goal in `mongoose-plugins` — proposed `tooling/config-schema-gen` (or a
profile on the parent pom) that:

1. runs after the plugins are built (needs them on the classpath to reflect),
2. reads `plugin-index.json` + `schema-overrides.json`,
3. reflects each instance class, merges overrides,
4. writes `target/schema/schema.json` (+ a `schema-<version>.json` copy),
5. (docs step) renders per-plugin Markdown config-reference pages.

Runs as part of the release pipeline so every `mongoose-plugins` release ships a
matching schema. JDK-only, no server boot, no network.

---

## 6. Publishing to the docs site

The site is the existing MkDocs/GitHub-Pages project (`mkdocs.yml`, `docs/`). Add:

- **Raw artefact**: `docs/schema/schema.json` (latest) and
  `docs/schema/schema-<version>.json` (pinned), so consumers can fetch
  `https://telaminai.github.io/mongoose-plugins/schema/schema.json` or a versioned
  URL — mirroring how `fluxtion-playground-libs` serves `catalog.json` from raw
  GitHub.
- **Human pages**: a "Config reference" section, one page per plugin (generated
  from the same schema), slotting into the existing `docs/connectors/` and
  `docs/services/` nav. Each renders the field table (name, type, default,
  required, allowed values, doc). This is the "load into the site for inspection"
  ask — the schema becomes browsable documentation.
- **Versioned index** so an older starter can fetch the schema it was pinned to.

Generation of the Markdown pages is part of the §5 goal (or a thin mkdocs-macros
step), so docs can't drift from the JSON.

---

## 7. Consumption (recap — defined fully in the starter spec)

The Project Starter fetches `schema.json` client-side (IndexedDB-cached, keyed by
the `mongoose-plugins.version` it targets), renders an editor per selected
feed/sink/service from the field list, validates input against types/enums/required,
and emits the values into `server-config.yml` under the correct `yamlKey` +
`yamlBindKey`. fluxtion-web bundles a fallback copy for offline / site-down.

---

## 8. Open questions

1. **Annotation now or later?** Ship `@ConfigField` in mongoose-core for v1, or
   rely on reflection + `schema-overrides.json`? **Proposed: overrides for v1**,
   annotation as a follow-up tied to substrate-lint.
2. **Nested/complex types** — `valueMapper`, `idleStrategy`, custom codec types are
   not scalar config. Represent as `type: "ref"` with a hint (e.g. "a
   `valueMapper` class FQN") rather than trying to schematise them? **Proposed: yes,
   `ref` with a doc hint; the starter offers a generated-shell or FQN entry.**
3. **Per-module vs central index** (§4) — start central, migrate to per-module
   `META-INF` discovery later. Confirm.
4. **Defaults that require construction side-effects** — a few setters compute
   derived state; reading the getter on a fresh instance is correct for "default",
   but watch for setters that throw without prerequisites. Generator must catch +
   report per-property rather than fail the whole run.
5. **Schema for mongoose-core built-ins** — `FileEventSource`/`FileMessageSink`/
   in-memory live in **mongoose-core**, not mongoose-plugins. The index should
   include them (they're the most common feeds/sinks) even though the generator
   runs in the plugins repo — add mongoose-core as a provided dep for reflection,
   or generate a core slice in the mongoose repo and merge. **Proposed: include
   core built-ins via the index, reflecting against the mongoose-core dep.**

---

## 9. Phasing

1. **P1** — `plugin-index.json` (central) + reflection generator + `schema.json`
   for the file connector + svc-cache (proof). Golden-file test asserting the
   emitted schema.
2. **P2** — cover all connectors (incl. mongoose-core file/in-memory) + all
   services; `schema-overrides.json`; required/enum/nested handling
   (`performanceMonitoring.auditCapture`).
3. **P3** — publish step: raw `schema.json` (latest + versioned) to the docs site +
   generated per-plugin config-reference pages in MkDocs nav.
4. **P4** — wire into the release pipeline (schema regenerated + published every
   `mongoose-plugins` release); fluxtion-web starter consumes it.
5. **P5 (optional)** — `@ConfigField` annotation in mongoose-core + substrate-lint
   rule for undocumented config setters.

---

## 10. Related

- **`fluxtion-web/docs/project-starter/README.md`** §11.1 — the consumer contract.
- **`design/svc-admin-web.md`** — the runtime `/api/services/{name}/config`
  reflection this lifts to build time.
- **Substrate Lint** (fluxtion compile-time annotation-processor family) — natural
  home for a "config setter must be documented" rule (§3.2 / P5).
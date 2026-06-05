# Connector / service config schema — generation & publishing

**Status**: P1 shipped (see §11 Progress) · spec otherwise draft
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

Field `type` ∈ `string | int | long | double | boolean | enum | duration | list | map | ref | nested`.
`nested` carries a `fields[]` of its own (e.g. `performanceMonitoring.auditCapture`).
`list`/`map` cover the collection config Kafka/Aeron carry (`List<String> topics`,
`Map<String,String> properties`) — add an `elementType` (and `keyType` for maps)
hint so the editor can render them; `ref` is a non-scalar config object the editor
can't schematise (a codec/strategy) — surfaced as an FQN entry.
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
     applicable), **sanitised** — see "environment-specific defaults" below.
   - `required` = **false by default.** A null/absent default does NOT imply
     required (most optional fields default to null — inferring required there
     over-reports badly). Required is asserted by the curated override (§3.1) or a
     future `@ConfigField(required=true)` (§3.2), never inferred.
   - `doc` = from a `@ConfigDoc`/Javadoc source if available (§3.2), else absent.

This is the same reflection `svc-admin-web` does at runtime, lifted to build time
and run over a *fresh* instance instead of a live one.

**Environment-specific defaults must not be baked in.** A default read off a fresh
instance can capture machine state — an absolute/temp path, the working dir, a
hostname, the clock. That value must never enter a versioned, "deterministic"
schema or the docs site (it breaks the determinism the starter relies on, and
publishes a per-machine value as if it were canonical). The generator nulls any
String default that is an absolute path or equals a JVM/system property
(`user.dir`/`java.io.tmpdir`/`user.home`/`user.name`); the field stays, only its
default drops to null. Implemented in `SchemaGenerator.sanitizeDefault`.

**SnakeYAML binding caveat.** "bindable = JavaBean writable properties" is the
right approximation but not the whole story: SnakeYAML also binds public fields
without setters, and collection/map binding depends on generic element types it
infers from the field signature. The generator covers the setter surface (the
common case); public-field-only config and deep generic element typing are P2/P3
refinements tracked in §8.

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
5. **Schema for mongoose-core built-ins + the version-axis conflation** —
   `com.telamin.mongoose.connector.file.*` (core) and in-memory live in
   **mongoose-core**, on the **`mongoose`** version axis, while plugin connectors
   are on the independent **`mongoose-plugins`** axis. The schema currently stamps a
   single `pluginsVersion`, so a core-built-in entry would be mislabelled. Fix:
   either (a) carry a **per-entry `sourceVersion`** + which axis it came from, or
   (b) **assert+gate that `mongoose` and `mongoose-plugins` move in lockstep** for
   released schemas. **Proposed: (a) per-entry source version** — the starter has two
   independent knobs (`mongoose`, `mongoose-plugins`) and must not describe the
   wrong release. (Review: HIGH.) NB: P1 uses the **plugin** file connector
   (`plugin.connector.file.*`), which is on the plugins axis — so P1 is unaffected;
   this bites when core built-ins are added in P2.
6. **One artifact → multiple instances.** `connector-file` ships both a source
   (`FileEventSource`/`eventFeeds`) and a sink (`FileMessageSink`/`eventSinks`) with
   different surfaces. Modelled as **two `plugins[]` entries sharing `artifactId`**,
   disambiguated by `kind` (override key is `artifactId:kind`). Chosen over an
   `instances[]`-per-plugin nesting for a flatter consumer contract. (Review: MED —
   resolved this way in P1.)

---

## 9. Phasing

1. **P1** ✅ **— shipped** (see §11). `plugin-index.json` (central) + reflection
   generator + `schema.json` for the file connector + svc-cache (proof). Golden-file
   test asserting the emitted schema.
2. **P2** ◐ **— connectors + model refinements done** (see §11); **services +
   mongoose-core built-ins + nested still to do.** All 5 connectors covered;
   `list`/`map` element types, `sourceVersion`, no-init loading shipped.
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

---

## 11. Progress log

### P1 — shipped (2026-06-05)

**Module**: `tooling/config-schema-gen` (registered in the parent `pom.xml`
`<modules>`). JDK-only tooling, not a runtime artifact.

**Files**:
- `pom.xml` — deps: `connector-file`, `svc-cache` (the P1 proof plugins, reflected
  over), `jackson-databind`; junit inherited from parent.
- `src/main/resources/plugin-index.json` — central manifest, 3 entries
  (connector-file source + sink, svc-cache service).
- `src/main/resources/schema-overrides.json` — curated hide/required/doc/format.
- `src/main/java/.../schemagen/SchemaModel.java` — output records (`Schema`,
  `PluginSchema`, `FieldSchema`), Jackson `NON_NULL`/`NON_EMPTY`, `@JsonProperty("default")`.
- `.../SchemaGenerator.java` — reflection core: walks `Introspector` writable bean
  properties; filters framework setters (param-type prefixes
  `com.telamin.mongoose.{dispatch,service,internal}.`, `com.telamin.fluxtion.runtime.`,
  `java.io.`, `java.util.concurrent.`; plus prop names `eventToQueuePublisher`,
  `eventFlowManager`, `eventToInvokeStrategy`, `name`, `serviceName`, `dataMapper`,
  `valueMapper`); maps types incl. `enum`(+values)/`list`/`map`/`ref`; reads defaults
  off a fresh no-arg instance; `sanitizeDefault` nulls environment-specific defaults;
  merges overrides; sorts fields by name (deterministic).
- `.../SchemaGenMain.java` — CLI: `SchemaGenMain <pluginsVersion> <outDir>` → writes
  `schema.json` + `schema-<version>.json`. `buildSchema(...)` shared with tests.
- `src/test/.../SchemaGeneratorTest.java` — 5 tests (all green): plugin count,
  file-source surface (filename required, readStrategy enum + default COMMITED),
  service `service:` bind key, framework-setter filtering, **golden-file** match.
- `src/test/resources/expected-schema.json` — the locked golden (regenerate via
  `SchemaGenMain` and review the diff when output intentionally changes).

**Verified**: `mvn -pl tooling/config-schema-gen test` → BUILD SUCCESS, 5/5.
Emitted surfaces (clean, after overrides):
- connector-file source: `cacheEventLog`, `filename` (required, path), `readStrategy`
  (enum, default COMMITED).
- connector-file sink: `filename` (required), `maxBackupFiles`, `rotateOnIntervalMillis`
  (millis), `rotateOnSizeBytes`.
- svc-cache service: `asyncWrite`, `fileName` (required, path), `maxSize`.

**Deltas from the original spec (folded in from the LLM review, all applied to the
code, not just docs)**:
- `required` is **false by default**, asserted via overrides — not inferred from a
  null default (was the over-reporting heuristic). §3.
- `list`/`map`/`ref` added to the type taxonomy (`mapType`). §2.
- `sanitizeDefault` guards against environment-specific captured defaults. §3.
- one-artifact→multiple-instances modelled as entries sharing `artifactId`,
  disambiguated by `kind`; override key is `artifactId:kind`. §8.6.

**How to run / extend** (for the next agent):
- Generate locally: `mvn -pl tooling/config-schema-gen org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=com.telamin.mongoose.tooling.schemagen.SchemaGenMain -Dexec.args="<version> /tmp/out"`.
- After an intentional output change, copy `/tmp/out/schema.json` over
  `src/test/resources/expected-schema.json` and review the diff.
- To add a plugin (P2): add its dep to the module pom, an entry to
  `plugin-index.json`, hide/annotate noise in `schema-overrides.json`, regenerate the
  golden, extend the test.

### P2 — connectors + model refinements shipped (2026-06-05)

**Done in this slice:**
- **All 5 connectors** (source + sink each) now in `plugin-index.json` + the module
  pom: connector-file, -kafka, -aeron, -chronicle, -multicast. Plus svc-cache. = 11
  `plugins[]` entries. Each reflects cleanly with curated overrides
  (required/format/doc; a couple of internal props hidden).
- **`list` / `map` element types** (review HIGH): `String[] topics` → `list`
  (`elementType: string`); `Properties` → `map` (`keyType`/`valueType: string`);
  generic `List<X>`/`Map<K,V>` read off the setter signature; arrays via component
  type; `Properties` special-cased. Verified by `kafkaCollectionsCarryElementTypes`.
- **`sourceVersion`** field added to `PluginSchema` (per-entry; null ⇒ schema's
  `pluginsVersion`) — ready for core built-ins on the `mongoose` axis (§8.5). Set via
  the index `sourceVersion` key; none used yet (all P2-slice classes are plugins).
- **No-init class loading** — classes loaded with `initialize=false` so native-backed
  connectors (aeron/chronicle/kafka) don't run static initialisers during
  introspection; `newInstance` for defaults stays wrapped (null defaults if it fails).
- 6 tests green (added the kafka list/map test); golden re-locked (11 plugins).

**P2 remaining (next agent):**
- **Services sweep** — svc-jdbc, svc-admin-rest/-telnet/-web, svc-loader-yaml/-spring/
  -feed/-sink, svc-micrometer. Some have non-uniform primary-class names; find each
  instance FQN (a few don't match the `*EventSource`/`*Sink` pattern), add dep + index
  entry + curation. (svc-admin-web reflects a *large* surface — heavy curation.)
- **mongoose-core built-ins** — add `com.telamin.mongoose.connector.file.*` (core) +
  in-memory with a per-entry `sourceVersion` = the `mongoose` version (§8.5). Needs
  mongoose-core as a (provided) dep; the index entry sets `sourceVersion`.
- **Nested config** — `type: nested` recursion (e.g. a config POJO property). Model
  field not yet added; `mapType` returns `ref` for POJOs today.
- **SnakeYAML public-field binding** (§3) — properties bound without a setter.
- **`@ConfigField`** (§3.2 / P5) — replace most of `schema-overrides.json`.

### Original P2 watch-items (still relevant)

Cover all connectors + services. Watch-items surfaced by P1/review:
- **Core built-ins + version axis** (§8.5): when adding `com.telamin.mongoose.connector.file.*`
  (core), attach a per-entry `sourceVersion` — they're on the `mongoose` axis, not
  `mongoose-plugins`.
- **Kafka/Aeron `list`/`map` config** (§2): topics/properties need element-type hints
  and must *not* be blanket-filtered the way internal containers are — likely needs
  the `@ConfigField` allow-marker (§3.2) or per-field overrides to distinguish config
  collections from internal state (e.g. svc-cache `cacheMap`, hidden by override in P1).
- **Nested config** (`performanceMonitoring.auditCapture`) → `type: nested` recursion
  (stubbed in the type list, not yet implemented in `mapType`).
- **SnakeYAML public-field binding** (§3) — properties bound without a setter.
- **`@ConfigField` annotation** (§3.2 / P5) — the durable replacement for most of
  `schema-overrides.json` and the framework-prop denylist.
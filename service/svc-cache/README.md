# svc-cache

Key/value lookup service for Mongoose with two implementations, plus `PersistentNode` and `Seed` for node state that
survives a restart:

| Class | Storage | Use case |
|---|---|---|
| [`InMemoryCache`](src/main/java/com/telamin/mongoose/plugin/svc/cache/InMemoryCache.java) | `ConcurrentHashMap` | Reference data shared across processors; lost on restart. |
| [`JsonFileCache`](src/main/java/com/telamin/mongoose/plugin/svc/cache/JsonFileCache.java) | `ConcurrentHashMap` mirrored to a JSON file on disk | Reference data that needs to survive restarts; survives via Jackson serialisation. Type information is preserved per-entry. |

Both implement the [`Cache`](src/main/java/com/telamin/mongoose/plugin/svc/cache/Cache.java) interface:

```java
Collection<String> keys();
void put(String key, Object value);
<T> T get(String key);
default <T> T getOrDefault(String key, T defaultValue);
void remove(String key);
```

`JsonFileCache` is also an Agrona `Agent` and a Mongoose `EventFlowService`, so the server can host it on an agent thread and inject it into processors via `@ServiceRegistered`.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-cache</artifactId>
    <version>${mongoose-plugins.version}</version>   <!-- 1.1.2 or later for the classes below -->
</dependency>
```

Gradle: `implementation "com.telamin:svc-cache:$mongoosePluginsVersion"`. jbang: `//DEPS com.telamin:svc-cache:<version>`.
svc-cache brings `lib-jsonserialiser` (for `StateJson`) and Jackson.

## Usage — Java config

```java
JsonFileCache cache = new JsonFileCache();
cache.setFileName("./data-out/state.json");

ServiceConfig<Cache> cacheConfig = ServiceConfig.<Cache>builder()
        .instance(cache)
        .name("state-cache")
        .serviceClass(Cache.class)
        .build();

// inject into a processor
public class MyHandler extends ObjectEventHandlerNode {
    private Cache cache;

    @ServiceRegistered
    public void useCache(Cache cache, String name) {
        this.cache = cache;
    }

    @Override
    protected boolean handleEvent(Object event) {
        cache.put("last-event", event);
        return true;
    }
}
```

## Crash-safe writes

`JsonFileCache` never writes its file in place. Each write serialises the whole cache as compact JSON to its own
sibling temporary file and moves it over `fileName` with `ATOMIC_MOVE`, so a process killed mid-write (or a write that
fails part way) leaves the previous complete file. For a file `<name>` the temporary file is `.<name>.<16 hex
digits>.jfc.tmp` (hidden, owner-only where POSIX permissions exist): no other cache's file or temporary file can have
that name, so `init()` deletes exactly this file's stale writes and never another cache's data or in-flight write (a
`fileName` of that form is refused). A failed write keeps the cache dirty so the next `doWork()` retries. `tearDown()` writes the same
way, and does not write at all if `init()` failed (a corrupt file is left as it is). This survives a process kill;
surviving power loss would also need an fsync, which is not done.

A `put` or `remove` changes the map before it marks the cache dirty, and with `asyncWrite=false` it returns only after
a write that includes its change is on disk, even when another thread's put or the agent's `doWork` overlaps it.

**One instance per file.** Two `JsonFileCache` instances on the same file each replace it with their own map, and one's
`init()` deletes the other's in-flight temporary files. Instances on distinct files, including names that extend one
another (`state.json`, `state.json.archive.tmp`, `state.json.backup.json`), never touch each other's files.

## PersistentNode and Seed: node state that survives a restart

`PersistentNode<S>` is a Fluxtion node base class whose state is one record (or any Jackson-serialisable object),
saved to a `Cache` service after every event (`@AfterEvent`) and handed back to `restore` when the cache registers on
the next start. JSON is `StateJson` from lib-jsonserialiser: records, collections, enums, `java.time` as ISO strings.

```java
public class Basket extends PersistentNode<Basket.State> {
    public record State(Map<String, Integer> items) {}
    private transient Map<String, Integer> items = new HashMap<>();

    @ServiceRegistered("appState") @Override public void cacheRegistered(Cache c) { super.cacheRegistered(c); }
    @Override protected String stateKey() { return "basket"; }
    @Override protected State state() { return new State(items); }
    @Override protected void restore(State s) { items = new HashMap<>(s.items()); }
    @Override protected void onCache(Cache cache) { Seed.properties(cache, "config/basket.properties", "basket."); }

    @OnEventHandler
    public boolean onAdd(AddItem e) {
        if (replayed()) return false;   // re-delivered after a crash: already in the saved state
        items.merge(e.sku(), e.qty(), Integer::sum);
        return true;
    }
}
```

**One PersistentNode per processor, or independent nodes only.** Each PersistentNode writes its own record, so a
processor with several writes the file once per node per event, and a crash between two nodes' writes leaves them at
different saved positions. After the restart the re-delivered event is `replayed()` for the node that saved it and new
for the other; if the first is upstream and returns early, the downstream node never sees an event it still needs.
Keep the replay guard to one PersistentNode per processor, or to nodes that do not trigger one another. (Writes are not
batched into one per event cycle.)

**No reentrant events during a positioned delivery: they are refused.** A handler, or a sink it publishes to, that
sends the processor another event (`onEvent`, `EventDispatcher.queueReentrantEvent` or `processReentrantEvent`)
while the processor handles an event that has a source position makes it run a second event cycle under the same
delivery. The generated processor runs `@AfterEvent` (each PersistentNode's save, with the delivery's position) at the
end of the first cycle, before it drains the queued event, and neither Fluxtion nor Mongoose tells a node when the
delivery as a whole, queued events included, is complete. The second cycle can be neither skipped (it is new work, not
a replay) nor applied safely (after a crash before its save, the restarted node skips the re-delivered event as
already applied, so nothing queues the second event again and its effect is lost). So `replayed()` and `saveState()`
throw `IllegalStateException` in any further cycle under a delivery the node already saved in this run (`saveState`
writes first, so the cache matches the node). On a server the dispatch fails, is retried and refused again by the
feed's retry policy, and the event is dropped and reported. The same applies to any other second cycle under one
delivery: a synthetic clock installed for a `ReplayRecord` on a positioned feed, or a server retry of a delivery that
failed after the node saved it (its effect is already saved). Do the follow-up work in the same cycle (call the method
rather than re-dispatching an event), or publish it to a feed of its own. Events without a source position (no
`DeliveryContext`, timers, admin commands, fixture runs) are never refused. Lifting this needs a delivery-complete
hook upstream (a Fluxtion callback after the processor drains its queued events, or a Mongoose callback after a
processor's dispatch of a positioned item), so the position is committed once per delivery.

The saved record is `{"_v":3,"state":{...},"positions":{"<feed>":{"epoch":e,"position":p}}}`; older records (the bare
state, or `_v:2`) still restore, without trusting their positions.

**Replay guard.** With a Mongoose that provides `com.telamin.mongoose.service.delivery.DeliveryContext` (at-least-once
file feed delivery), the node stores each feed's source epoch and position in the same write as its state, and
`replayed()` is true for an event whose effect is already saved; a skipped event is audited as `replayedSkip`. Use a
`JsonFileCache` with `asyncWrite: false` and no agent group, so the state is written in the event cycle that changed it.
`DeliveryContext` is looked up once through method handles: on a Mongoose without it (1.1.2 and earlier) the guard is
off and `replayed()` is always false; nothing else changes.

`Seed` writes configuration into a cache only where a key is absent, so a restart keeps what is stored:
`Seed.properties(cache, file, prefix)`, `Seed.file(cache, file, key)` (the file's text), `Seed.value(cache, key, v)`.

## Migration from `com.telamin:fluxtion-app-support`

| Old | New (artifact `com.telamin:svc-cache`) |
|---|---|
| `com.telamin.support.PersistentNode` | `com.telamin.mongoose.plugin.svc.cache.PersistentNode` |
| `com.telamin.support.Seed` | `com.telamin.mongoose.plugin.svc.cache.Seed` |
| `com.telamin.support.AtomicJsonFileCache` | `com.telamin.mongoose.plugin.svc.cache.JsonFileCache` (now atomic itself) |
| `com.telamin.support.StateJson` (package-private) | `com.telamin.mongoose.plugin.lib.json.StateJson` (lib-jsonserialiser) |

Saved state records are unchanged, so an existing cache file restores as before.

## Behaviour changes in 1.1.2 (JsonFileCache)

- **Logging.** The INFO line per write (`cache updated:<file> keys:[...]`, with every key) is now DEBUG and carries the
  key count, not the keys.
- **File format.** The file is compact, one line of JSON; before it was pretty-printed. Both load.
- **Atomic replace.** The file is replaced by a new one on every write: a `fileName` that is a symlink becomes a regular
  file (the link's target is no longer written), and the file's permissions and owner are not kept; the new file gets
  the temporary file's (owner-only where POSIX permissions exist).
- **Windows.** Only `AtomicMoveNotSupportedException` falls back to a plain replacing move. If the file system refuses
  the replace, e.g. `AccessDeniedException` while another process has the file open, the write fails, the exception
  surfaces from `put`/`doWork`, and the cache stays dirty for the next attempt. Not tested on Windows.
- **Temporary files.** One per write, `.<name>.<16 hex digits>.jfc.tmp` beside `<name>`, instead of none; `init()` deletes
  only this file's stale ones (an exact-name match, never a prefix match), and a `fileName` of that form is refused.
- **tearDown** no longer writes after a failed `init()`, so it cannot overwrite an unreadable file with an empty map.

## Admin commands

When a `JsonFileCache` is wired alongside an `AdminCommandRegistry`, it auto-registers two commands keyed by its service name (`{serviceName}` below):

- `cache.{serviceName}.get <key>` — print the value for a single key
- `cache.{serviceName}.keys` — list all cached keys

## Operational notes / gotchas

- **Empty / null fileName throws.** `JsonFileCache.init()` now raises `IllegalStateException` with a clear message rather than NPE'ing later.
- **Missing parent directories are auto-created.** Both writes-on-init and subsequent `mapper.writeValue(...)` flushes survive a path like `./data-out/missing/nested/state.json`.
- **Bare-basename file names work.** Configure `state.json` (relative to CWD) without a parent path.
- **Type preservation.** Each entry is stored alongside a `type` field so reloads on restart reconstruct the right concrete Java type. Custom types must be on the classpath.
- **Synchronous vs async flush.** `asyncWrite=false` (default) writes to disk on each `put`. Set `asyncWrite=true` to defer flushes to the agent thread.
- **Compact file.** The file is one line of JSON (it was pretty-printed before 1.1.2); either form loads.

## Tests

- [`InMemoryCacheTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/InMemoryCacheTest.java) — put/get/keys/remove + `getOrDefault`.
- [`JsonFileCacheTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/JsonFileCacheTest.java) — missing-parent-dir creation, bare-basename, null/empty-filename guards.
- [`JsonFileCacheAtomicWriteTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/JsonFileCacheAtomicWriteTest.java) — compact JSON, a write failing part way leaves the previous file byte-identical and retries, stale temp file ignored, async and tearDown writes, eviction.
- [`JsonFileCacheConcurrencyTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/JsonFileCacheConcurrencyTest.java) — a put overlapped by the agent's doWork or by another thread's synchronous put is never left unsaved (deterministic interleavings and thread races).
- [`JsonFileCacheTempAndTearDownTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/JsonFileCacheTempAndTearDownTest.java) — per-write temporary files, only this file's stale ones removed (a distinct cache's data file or live write whose name extends this one's is kept), temporary-form file names refused, two instances on one file, tearDown dirty-flag and failed-init guards.
- [`JsonFileCacheKillTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/JsonFileCacheKillTest.java) — a child writer process is SIGKILLed mid-write six times; the file always loads with one whole value.
- [`PersistentNodeReentrantTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/PersistentNodeReentrantTest.java) — an event queued during a positioned delivery is refused, not skipped; a crash at the first cycle's save ends at the same state as the refusing live run; separate deliveries and position-less events are not refused.
- [`PersistentNodeTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/PersistentNodeTest.java) — save/restore, older records, seeding; replay guard off without `DeliveryContext`, on with a test double of it, and on with Mongoose's own when built against a Mongoose that has it (`-Dmongoose.version=...`).
- [`SeedTest`](src/test/java/com/telamin/mongoose/plugin/svc/cache/SeedTest.java).

## Related

- [`connector-file`](../../connector/connector-file/) — file-tailing event source with the same filename-handling discipline.

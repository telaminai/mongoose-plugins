# svc-cache

Key/value lookup service for Mongoose with two implementations:

| Class | Storage | Use case |
|---|---|---|
| [`InMemoryCache`](src/main/java/com/fluxtion/dataflow/serverplugin/svc/cache/InMemoryCache.java) | `ConcurrentHashMap` | Reference data shared across processors; lost on restart. |
| [`JsonFileCache`](src/main/java/com/fluxtion/dataflow/serverplugin/svc/cache/JsonFileCache.java) | `ConcurrentHashMap` mirrored to a JSON file on disk | Reference data that needs to survive restarts; survives via Jackson serialisation. Type information is preserved per-entry. |

Both implement the [`Cache`](src/main/java/com/fluxtion/dataflow/serverplugin/svc/cache/Cache.java) interface:

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
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

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

## Tests

- [`InMemoryCacheTest`](src/test/java/com/fluxtion/dataflow/serverplugin/svc/cache/InMemoryCacheTest.java) — put/get/keys/remove + `getOrDefault`.
- [`JsonFileCacheTest`](src/test/java/com/fluxtion/dataflow/serverplugin/svc/cache/JsonFileCacheTest.java) — missing-parent-dir creation, bare-basename, null/empty-filename guards.

## Related

- [`connector-file`](../../connector/connector-file/) — file-tailing event source with the same filename-handling discipline.

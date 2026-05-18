# svc-cache

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
</span>

Key/value cache for reference data shared across processors. Two implementations: in-memory and JSON-backed.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-cache</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Implementations

| Class             | Persistence            | LRU       |
|-------------------|------------------------|-----------|
| `InMemoryCache`   | None                   | Optional via `maxSize` |
| `JsonFileCache`   | JSON file on disk      | Optional via `maxSize` |

Both implement the same `Cache` interface so the consuming processor is implementation-agnostic.

## Sample

```yaml
services:
  - name: state-cache
    serviceClass: com.telamin.mongoose.plugin.svc.cache.Cache
    instance: !!com.telamin.mongoose.plugin.svc.cache.JsonFileCache
      fileName: ./data-out/state.json
      maxSize: 10000            # 0 = unbounded
      asyncWrite: false         # write on every put if false

  - name: hot-prices
    serviceClass: com.telamin.mongoose.plugin.svc.cache.Cache
    instance: !!com.telamin.mongoose.plugin.svc.cache.InMemoryCache
      maxSize: 5000
```

!!! tip "Registering as `Cache`, not the concrete impl"
    `serviceClass: com.telamin.mongoose.plugin.svc.cache.Cache` registers the
    service under the **interface** so processors that inject `@ServiceRegistered
    Cache cache` find it regardless of which implementation is wired. Drop in
    `JsonFileCache` for persistence or `InMemoryCache` for pure in-memory —
    consumers don't care.

    Omit `serviceClass` to register under the concrete class instead.

Inject:

```java
public class PriceMonitor extends ObjectEventHandlerNode {
    private Cache prices;

    @ServiceRegistered
    public void useCache(Cache prices, String name) {
        this.prices = prices;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof Tick t) {
            prices.put(t.symbol(), t.price());
        }
        return true;
    }
}
```

## LRU eviction

When `maxSize > 0`, the cache switches to access-order `LinkedHashMap` semantics. Each `get` or `put` marks the entry as most-recently-used. When `size > maxSize`, the **eldest entry is evicted** and `evictedCount` is incremented.

```java
InMemoryCache cache = new InMemoryCache();
cache.setMaxSize(2);

cache.put("a", 1);
cache.put("b", 2);
cache.put("c", 3); // evicts "a"

cache.get("b");    // touches "b" → b is now MRU
cache.put("d", 4); // evicts "c", not "b"

cache.getEvictedCount(); // 2
```

For `JsonFileCache`, eviction also flags the file as dirty so the next `doWork()` (or sync put) writes the trimmed state to disk.

## Configuration reference

### InMemoryCache

| Field      | Default | Notes                              |
|------------|---------|------------------------------------|
| `maxSize`  | `0`     | `0` = unbounded `ConcurrentHashMap`. |

### JsonFileCache

| Field          | Default | Notes                                            |
|----------------|---------|--------------------------------------------------|
| `fileName`     | _required_ | Throws `IllegalStateException` if unset.       |
| `maxSize`      | `0`     | `0` = unbounded `ConcurrentHashMap`.             |
| `asyncWrite`   | `false` | If `true`, only `doWork()` writes to disk.       |

## Operational notes

- Parent directories are auto-created.
- Bare basenames (no `/`) work — no `getParentFile()` NPE.
- `tearDown()` writes the cache to disk and is safe to call without an `init()`.
- The cache also registers admin commands `cache.<name>.keys` and `cache.<name>.get <key>` with the admin registry — wire `svc-admin-telnet`, `svc-admin-rest`, or `svc-admin-web` to inspect cache contents at runtime. (`svc-admin-web` auto-renders these as a dedicated **Cache** panel.)

## Examples

- **[plugins/service-plugin-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/service-plugin-example)** — `@ServiceRegistered` template.
- **[how-to/data-mapping](https://github.com/telaminai/mongoose-examples/tree/main/how-to/data-mapping)** — the natural place to wire a cache for warmed reference data.

## Source

[`mongoose-plugins/service/svc-cache`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-cache)

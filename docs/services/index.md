# Services

Cross-cutting capabilities that processors inject via `@ServiceRegistered`. One thread, multi-reader by convention.

<div class="grid cards" markdown>

-   :material-database-cog-outline:{ .lg .middle } __[svc-jdbc](jdbc.md)__

    Named JDBC connection registry backed by HikariCP. Per-entry pool sizing, validation query.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
      <span class="plugin-tag persist">pooled</span>
    </span>

-   :material-cached:{ .lg .middle } __[svc-cache](cache.md)__

    In-memory + JSON-persistent caches. Optional LRU `maxSize` with eviction metric.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
    </span>

-   :material-console-network-outline:{ .lg .middle } __[svc-admin-telnet](admin-telnet.md)__

    JLine-backed telnet admin. Loopback default. Tab-complete + history.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
    </span>

-   :material-web:{ .lg .middle } __[svc-admin-rest](admin-rest.md)__

    Javalin REST admin. BASIC / BEARER auth. Optional SPA hosting.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
    </span>

-   :material-monitor-dashboard:{ .lg .middle } __[svc-admin-web](admin-web.md)__

    Browser admin + monitoring SPA. Dashboard with throttleable JVM stream, command terminal, log tail, dispatcher topology DAG, per-processor graphml viewer (filter / scaffold / selection cycle), reflective service config, conditional cache + loader panels.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
    </span>

-   :material-file-code-outline:{ .lg .middle } __[svc-loader-yaml](loader-yaml.md)__

    Hot-reload processors from YAML or Java source.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
    </span>

-   :simple-spring:{ .lg .middle } __[svc-loader-spring](loader-spring.md)__

    Hot-reload processors from Spring XML.

    <span class="plugin-tags">
      <span class="plugin-tag service">service</span>
    </span>

</div>

## How services are injected

Services declare their public API as an interface. Your processor injects with `@ServiceRegistered`:

```java
public class MyHandler extends ObjectEventHandlerNode {
    private Cache cache;
    private JdbcConnectionLoader jdbc;

    @ServiceRegistered
    public void useCache(Cache cache, String name) {
        this.cache = cache;
    }

    @ServiceRegistered
    public void useJdbc(JdbcConnectionLoader jdbc, String name) {
        this.jdbc = jdbc;
    }
}
```

The `name` argument is the service's registered name in the server config; processors can use it to disambiguate when multiple instances of the same interface are wired (e.g. two JDBC connection registries).

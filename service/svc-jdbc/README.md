# svc-jdbc

JDBC connection registry + pool service for Mongoose. Holds a named map of `JdbcConnectionConfig` entries and hands out `java.sql.Connection` instances on demand. Each entry is served from a [HikariCP](https://github.com/brettwooldridge/HikariCP) pool by default, or as fresh `DriverManager.getConnection` calls if you set `pooled: false`.

The configuration interface is intentionally thin — wire a connection per name in the server config, then inject the `JdbcConnectionLoader` into any processor that needs DB access.

| Class | Role |
|---|---|
| [`JdbcConnectionLoader`](src/main/java/com/fluxtion/dataflow/serverplugin/svc/jdbc/JdbcConnectionLoader.java) | The injection-facing interface: `Connection getConnection(String name)`. |
| [`JdbcConnectionConfig`](src/main/java/com/fluxtion/dataflow/serverplugin/svc/jdbc/impl/JdbcConnectionConfig.java) | POJO holding `url` / `username` / `password` (Lombok `@Data`). |
| [`JdbcConnectionLoaderService`](src/main/java/com/fluxtion/dataflow/serverplugin/svc/jdbc/impl/JdbcConnectionLoaderService.java) | Default implementation. Map of named configs + optional startup connectivity test (`testConnection`, `fastFail`). |

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-jdbc</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — YAML config

```yaml
services:
  - name: jdbcConnectionLoader
    service: !!com.telamin.mongoose.plugin.svc.jdbc.impl.JdbcConnectionLoaderService
      testConnection: true
      fastFail: false
      connections:
        marketdata: !!com.telamin.mongoose.plugin.svc.jdbc.impl.JdbcConnectionConfig
          url: jdbc:postgresql://localhost:5432/marketdata
          username: $ENV.MARKETDATA_USER
          password: $ENV.MARKETDATA_PASSWORD
          maximumPoolSize: 20
          minimumIdle: 2
          connectionTimeoutMs: 10000
          validationQuery: "SELECT 1"
        reference: !!com.telamin.mongoose.plugin.svc.jdbc.impl.JdbcConnectionConfig
          url: jdbc:h2:./data/refdata
          username: sa
          password:
          pooled: false   # raw DriverManager — no pool
```

Then in a processor:

```java
public class MyHandler extends ObjectEventHandlerNode {
    private JdbcConnectionLoader jdbc;

    @ServiceRegistered
    public void useJdbc(JdbcConnectionLoader jdbc, String name) {
        this.jdbc = jdbc;
    }

    @Override
    protected boolean handleEvent(Object event) {
        try (Connection c = jdbc.getConnection("marketdata")) {
            // ...
        }
        return true;
    }
}
```

## Pool settings

Per-entry, on `JdbcConnectionConfig`:

| Field                  | Default     | Notes                                                                |
|------------------------|-------------|----------------------------------------------------------------------|
| `pooled`               | `true`      | If false, every `getConnection` is a fresh `DriverManager` call.     |
| `maximumPoolSize`      | `10`        | Hard cap on simultaneous live connections.                            |
| `minimumIdle`          | `0`         | Kept warm even when idle.                                             |
| `connectionTimeoutMs`  | `30000`     | Wait for a connection from an exhausted pool before throwing.         |
| `idleTimeoutMs`        | `600000`    | Trim idle connections after this.                                     |
| `maxLifetimeMs`        | `1800000`   | Rotate connections after this (matches Postgres default `idle_session_timeout`). |
| `poolName`             | _generated_ | Defaults to `mongoose-jdbc-<entry-name>`.                             |
| `validationQuery`      | _unset_     | If set, used as both `connectionInitSql` and `connectionTestQuery`.   |

## Secret resolution

`$ENV.NAME` resolves from environment variable `NAME` first, then JVM system property `$ENV.NAME` (legacy compat). Applies to `username` and `password`.

## Operational notes

- **Unknown name → null.** `getConnection("does-not-exist")` logs a WARNING and returns `null` rather than throwing — defend against null on the calling side.
- **Pool created lazily.** First `getConnection(name)` allocates the HikariDataSource. No connections are opened until the first borrow.
- **`tearDown()` closes all pools.** Idempotent — safe to call twice during shutdown sequencing.
- **`testConnection=true`** with `fastFail=true` makes the server refuse to start if any configured connection can't connect (probes via the pool, so it also smoke-tests pool wiring).
- **JDBC driver on classpath.** Each connection's URL implies a driver — bundle the driver jar(s) with your deployment; this module only pulls in HikariCP.

## Tests

- `pooled_connection_returns_working_h2_connection` — pool round-trip
- `unknown_name_returns_null`
- `unpooled_returns_fresh_driver_manager_connection`
- `close_returns_connection_to_pool_no_leak` — 50× borrow+release on `maxPoolSize=2`, then asserts `activeConnections == 0`
- `max_pool_size_is_a_hard_cap` — exhausts a 2-conn pool, third borrow times out
- `tear_down_is_idempotent`
- `test_connection_with_fast_fail_throws_on_bad_url`

## Related

- [`svc-cache`](../svc-cache/) — small key/value cache, often paired with svc-jdbc for warmed reference data.

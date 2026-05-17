# svc-jdbc

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
  <span class="plugin-tag persist">pooled</span>
</span>

JDBC connection registry + pool. Each entry is served from a [HikariCP](https://github.com/brettwooldridge/HikariCP) pool by default, or as fresh `DriverManager.getConnection` calls if you opt out.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-jdbc</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Sample

```yaml
services:
  - name: jdbcConnectionLoader
    serviceClass: com.fluxtion.dataflow.serverplugin.svc.jdbc.JdbcConnectionLoader
    instance: !!com.fluxtion.dataflow.serverplugin.svc.jdbc.impl.JdbcConnectionLoaderService
      testConnection: true
      fastFail: false
      connections:
        marketdata: !!com.fluxtion.dataflow.serverplugin.svc.jdbc.impl.JdbcConnectionConfig
          url: jdbc:postgresql://localhost:5432/marketdata
          username: $ENV.MARKETDATA_USER
          password: $ENV.MARKETDATA_PASSWORD
          maximumPoolSize: 20
          minimumIdle: 2
          connectionTimeoutMs: 10000
          validationQuery: "SELECT 1"
        reference: !!com.fluxtion.dataflow.serverplugin.svc.jdbc.impl.JdbcConnectionConfig
          url: jdbc:h2:./data/refdata
          username: sa
          password:
          pooled: false   # raw DriverManager — no pool
```

!!! tip "Registering as an interface"
    `serviceClass` is the fully qualified type the service is registered under in
    Mongoose's service registry. `@ServiceRegistered` matches by parameter type, so
    if you want `@ServiceRegistered JdbcConnectionLoader jdbc` injection to find this
    service, register it under the interface, not the concrete `JdbcConnectionLoaderService`.

    Omit `serviceClass` and Mongoose falls back to the concrete class —
    useful when the consumer injects the concrete impl directly.

    See [`com.telamin.mongoose.config.ServiceConfig#serviceClass`](https://github.com/telaminai/mongoose/blob/main/mongoose/src/main/java/com/telamin/mongoose/config/ServiceConfig.java).

Inject into a processor:

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

## Per-entry pool settings

| Field                  | Default     | Notes                                                                |
|------------------------|-------------|----------------------------------------------------------------------|
| `pooled`               | `true`      | `false` falls back to `DriverManager` per call.                       |
| `maximumPoolSize`      | `10`        | Hard cap on simultaneous live connections.                            |
| `minimumIdle`          | `0`         | Kept warm even when idle.                                             |
| `connectionTimeoutMs`  | `30000`     | Wait for a connection from an exhausted pool before throwing.         |
| `idleTimeoutMs`        | `600000`    | Trim idle connections after this.                                     |
| `maxLifetimeMs`        | `1800000`   | Rotate connections after this.                                        |
| `poolName`             | _generated_ | Defaults to `mongoose-jdbc-<entry-name>`.                             |
| `validationQuery`      | _unset_     | If set, used as both `connectionInitSql` and `connectionTestQuery`.   |

## Secret resolution

`$ENV.NAME` resolves environment variable `NAME` first, then JVM system property `$ENV.NAME` as a fallback.

## Operational notes

- **Unknown name → null.** `getConnection("does-not-exist")` logs WARNING and returns `null`. Defend against null on the calling side.
- **Pool created lazily.** First `getConnection(name)` allocates the HikariDataSource. No connections are opened until then.
- **`tearDown()` closes all pools.** Idempotent.
- **`testConnection=true` + `fastFail=true`** makes the server refuse to start if any configured connection can't connect.
- **JDBC driver on classpath.** Each connection's URL implies a driver — bundle the driver jar(s) with your deployment.

## Tests

7 tests cover pool round-trip, max-size enforcement (exhaust + timeout), leak (50× borrow+release on max=2 then assert `activeConnections=0`), unpooled fallback, idempotent teardown, and fastFail bad-URL.

## Examples

- **[plugins/service-plugin-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/service-plugin-example)** — `@ServiceRegistered` wiring template; `JdbcConnectionLoader` is injected the same way.

## Source

[`mongoose-plugins/service/svc-jdbc`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-jdbc)

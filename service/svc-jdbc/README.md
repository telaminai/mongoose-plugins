# svc-jdbc

JDBC connection-pool service for Mongoose. Holds a named map of `JdbcConnectionConfig` (url + user + password) and hands out `java.sql.Connection` instances on demand, looked up by name.

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
    instance: !!com.fluxtion.dataflow.serverplugin.svc.jdbc.impl.JdbcConnectionLoaderService
      testConnection: true
      fastFail: false
      connections:
        marketdata: !!com.fluxtion.dataflow.serverplugin.svc.jdbc.impl.JdbcConnectionConfig
          url: jdbc:postgresql://localhost:5432/marketdata
          username: reader
          password: ${MARKETDATA_PASSWORD}
        reference: !!com.fluxtion.dataflow.serverplugin.svc.jdbc.impl.JdbcConnectionConfig
          url: jdbc:h2:./data/refdata
          username: sa
          password:
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

## Operational notes

- **Unknown name → null.** `getConnection("does-not-exist")` logs a WARNING and returns `null` rather than throwing — defend against null on the calling side. Verified by [`JdbcConnectionLoaderServiceTest`](src/test/java/com/fluxtion/dataflow/serverplugin/svc/jdbc/impl/JdbcConnectionLoaderServiceTest.java).
- **Failed connect → null + SEVERE.** Same shape on `SQLException` inside `getConnection`; this can mask real driver failures. Set `testConnection=true` to surface connection problems at startup instead of first use.
- **Set `fastFail=true`** with `testConnection=true` to make the server refuse to start if any configured connection can't connect.
- **No pool.** Each `getConnection(name)` calls `DriverManager.getConnection(...)` afresh. For real workloads, wrap with HikariCP or similar; this service is the registry, not the pool.
- **JDBC driver on classpath.** Each connection's URL implies a driver — bundle the driver jar(s) with your deployment, this module pulls in none transitively.

## Tests

- [`JdbcConnectionLoaderServiceTest`](src/test/java/com/fluxtion/dataflow/serverplugin/svc/jdbc/impl/JdbcConnectionLoaderServiceTest.java) — H2 in-memory: round-trips a `SELECT 1` and confirms unknown-name behaviour.

## Related

- [`svc-cache`](../svc-cache/) — small key/value cache, often paired with svc-jdbc for warmed reference data.

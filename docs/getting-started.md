# Getting started

This guide walks through adding a plugin to a Mongoose deployment from scratch — Maven dependency, server config, and a smoke test.

## 1. Prerequisites

- JDK 21 or newer (tested through JDK 25). On JDK 21+ you'll need:
  ```
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
  ```
  The plugin POMs already set this for Surefire; copy the line into your runtime launcher.
- Maven 3.9+ or Gradle 8+.
- A working [Mongoose](https://github.com/telaminai/mongoose) server (`com.telamin:mongoose:1.0.8` or newer).

## 2. Add a plugin to your project

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-file</artifactId>
    <version>{{ plugin_version }}</version>
</dependency>
```

Each plugin pulls in only its specific dependencies — `connector-file` is plain JDK, `connector-kafka` pulls Kafka clients, `svc-jdbc` pulls HikariCP. Mix-and-match as needed.

!!! tip
    The `mongoose` dependency itself is declared on the plugin parent POM (`provided` scope in your project). You don't need it as a transitive — your application brings the runtime.

## 3. Wire the plugin into your server config

Three idiomatic styles:

=== "YAML"

    ```yaml
    eventFeeds:
      - name: trades
        instance: !!com.telamin.mongoose.plugin.connector.file.FileEventSource
          filename: ./data-in/trades.jsonl
          readStrategy: COMMITED
        broadcast: true
        valueMapper: !!com.telamin.mongoose.plugin.lib.json.TypeSerialiser {}
        agentName: file-source-agent
        idleStrategy: !!org.agrona.concurrent.SleepingMillisIdleStrategy {}

    eventSinks:
      - name: enriched-out
        instance: !!com.telamin.mongoose.plugin.connector.file.FileMessageSink
          filename: ./data-out/enriched.jsonl
          rotateOnSizeBytes: 10485760     # 10 MB
          maxBackupFiles: 5

    services:
      - name: state-cache
        serviceClass: com.telamin.mongoose.plugin.svc.cache.Cache
        instance: !!com.telamin.mongoose.plugin.svc.cache.JsonFileCache
          fileName: ./data-out/state.json
          maxSize: 10000
    ```

    !!! tip "`serviceClass` registers a service under a specific type"
        `services[].serviceClass` is the fully qualified type the service is
        published under in Mongoose's service registry. `@ServiceRegistered`
        matches by parameter type, so registering as an interface lets multiple
        implementations be swapped without touching the consumer.

        Omit `serviceClass` to register under the concrete class. Both are valid
        — pick by whether your processor injects the interface or the impl. See
        [`ServiceConfig#serviceClass`](https://github.com/telaminai/mongoose/blob/main/mongoose/src/main/java/com/telamin/mongoose/config/ServiceConfig.java).

    Boot with:
    ```java
    MongooseServer.bootServer(new File("./config/server.yml"));
    ```

=== "Java"

    {% raw %}
    ```java
    var cfg = MongooseServerConfig.builder()
        .addEventFeed(EventFeedConfig.builder()
            .name("trades")
            .instance(new FileEventSource().filename("./data-in/trades.jsonl"))
            .valueMapper(new TypeSerialiser())
            .broadcast(true)
            .idleStrategy(new SleepingMillisIdleStrategy())
            .build())
        .addEventSink(EventSinkConfig.builder()
            .name("enriched-out")
            .instance(configureSink(new FileMessageSink()))
            .build())
        .addService(new JsonFileCache() {{
            setFileName("./data-out/state.json");
            setMaxSize(10_000);
        }})
        .build();

    MongooseServer.bootServer(cfg);
    ```
    {% endraw %}

=== "Spring XML"

    ```xml
    <bean id="tradesFeed"
          class="com.telamin.mongoose.plugin.connector.file.FileEventSource">
      <property name="filename" value="./data-in/trades.jsonl"/>
      <property name="readStrategy" value="COMMITED"/>
    </bean>

    <bean id="stateCache"
          class="com.telamin.mongoose.plugin.svc.cache.JsonFileCache">
      <property name="fileName" value="./data-out/state.json"/>
      <property name="maxSize" value="10000"/>
    </bean>
    ```

    Then load via `svc-loader-spring`.

## 4. Inject a service into your processor

Plugins like `svc-jdbc` and `svc-cache` expose interfaces (`JdbcConnectionLoader`, `Cache`) that you inject with `@ServiceRegistered`:

```java
public class TradeEnricher extends ObjectEventHandlerNode {
    private JdbcConnectionLoader jdbc;
    private Cache cache;

    @ServiceRegistered
    public void useJdbc(JdbcConnectionLoader jdbc, String name) {
        this.jdbc = jdbc;
    }

    @ServiceRegistered
    public void useCache(Cache cache, String name) {
        this.cache = cache;
    }

    @Override
    protected boolean handleEvent(Object event) {
        if (event instanceof Trade t) {
            Reference ref = cache.get("ref:" + t.symbol());
            if (ref == null) {
                ref = loadFromDb(t.symbol());
                cache.put("ref:" + t.symbol(), ref);
            }
            return true;
        }
        return false;
    }
}
```

## 5. Smoke-test the deployment

Build, then write a single event to the input file. The file source picks it up; your processor handles it; the file sink appends to the output:

```bash
mvn package
java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
     -jar target/your-app.jar config/server.yml &

echo '{"@type":"Trade","symbol":"AAPL","qty":100}' >> ./data-in/trades.jsonl
tail -F ./data-out/enriched.jsonl
```

## What plugin do I want?

Skim the [Connectors](connectors/index.md), [Services](services/index.md), and [Libraries](libraries/index.md) sections; each plugin page lists exactly the scenarios it's a good fit for. The big-picture decision tree:

- **Need a source/sink** → connectors. Pick by where the data lives (file, Kafka, Aeron channel, …).
- **Need to share data between processors** → svc-cache (in-memory or persistent).
- **Need to call a database** → svc-jdbc (pooled by default).
- **Need to inspect or drive the server at runtime** → svc-admin-telnet (shell), svc-admin-rest (scripts/CI), or svc-admin-web (browser dashboard + live monitor + log tail).
- **Need to load processors dynamically** → svc-loader-yaml or svc-loader-spring.

## Next

- [Architecture](architecture.md) — how a Mongoose server is wired and where plugins plug in.
- [Operational guide](operations.md) — production checklist: JDK flags, idle strategies, shutdown.

# Test harness

`mongoose-test-support` wraps a booted [`MongooseServer`](https://github.com/telaminai/mongoose) as an `AutoCloseable`, with a builder for the common feed/sink/processor wiring and a small suite of await helpers. Replaces ~30 lines of boot/teardown ceremony per integration test with ~10.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>mongoose-test-support</artifactId>
    <version>{{ plugin_version }}</version>
    <scope>test</scope>
</dependency>
```

## Quick start

```java
import com.telamin.mongoose.plugin.testsupport.MongooseTestHarness;
import com.telamin.mongoose.connector.memory.InMemoryEventSource;
import com.telamin.mongoose.connector.memory.InMemoryMessageSink;

@Test
void echo() {
    InMemoryEventSource<String> feed = new InMemoryEventSource<>();
    InMemoryMessageSink captured = new InMemoryMessageSink();

    try (MongooseTestHarness h = MongooseTestHarness.builder()
            .feed("in", feed, "feed-agent")
            .sink("out", captured)
            .processor("processor-agent", "echo", new EchoHandler())
            .start()) {

        feed.offer("alpha");
        feed.offer("beta");

        h.awaitCondition(() -> captured.getMessages().size() >= 2);

        assertEquals(List.of("echo:alpha", "echo:beta"), captured.getMessages());
    }
}
```

## What it replaces

A typical pre-harness integration test:

```java
// build sources/sinks/processors
FileEventSource source = new FileEventSource();
source.setFilename(input.toString());
source.setReadStrategy(ReadStrategy.EARLIEST);

FileMessageSink sink = new FileMessageSink();
sink.setFilename(output.toString());

var feed = EventFeedConfig.builder()
        .instance(source).name("file-feed").broadcast(true)
        .agent("file-source-agent", new SleepingMillisIdleStrategy(1))
        .build();

var sinkCfg = EventSinkConfig.builder()
        .instance(sink).name("file-sink").build();

var processor = EventProcessorConfig.builder()
        .customHandler(new ForwardingHandler())
        .name("forwarder").build();

var app = MongooseServerConfig.builder()
        .addProcessor("processor-agent", processor)
        .addEventFeed(feed)
        .addEventSink(sinkCfg)
        .build();

MongooseServer server = MongooseServer.bootServer(app);
try {
    long deadline = System.currentTimeMillis() + 3_000;
    while (System.currentTimeMillis() < deadline) {
        if (Files.exists(output)
                && Files.readAllLines(output).size() >= 3) break;
        Thread.sleep(20);
    }
    assertEquals(expected, Files.readAllLines(output));
} finally {
    server.stop();
}
```

The same test with the harness:

```java
FileEventSource source = new FileEventSource();
source.setFilename(input.toString());
source.setReadStrategy(ReadStrategy.EARLIEST);

FileMessageSink sink = new FileMessageSink();
sink.setFilename(output.toString());

try (MongooseTestHarness h = MongooseTestHarness.builder()
        .feed("file-feed", source, "file-source-agent")
        .sink("file-sink", sink)
        .processor("processor-agent", "forwarder", new ForwardingHandler())
        .start()) {

    h.awaitFileLines(output, 3);
    assertEquals(expected, Files.readAllLines(output));
}
```

## API surface

### Builder

| Method                                                              | Purpose                                              |
|---------------------------------------------------------------------|------------------------------------------------------|
| `feed(name, source, agentName)`                                     | Add an event feed                                    |
| `feed(name, source, agentName, idleStrategy)`                       | Same with explicit idle strategy                     |
| `sink(name, sink)`                                                  | Add a message sink                                   |
| `service(name, instance)`                                           | Register service under the concrete class            |
| `service(name, instance, serviceClass)`                             | Register under an interface — see [serviceClass](architecture.md#service-registration-and-the-serviceclass-field) |
| `processor(agentName, handlerName, ObjectEventHandlerNode)`         | Typed processor                                      |
| `processor(agentName, handlerName, Consumer<Object>)`               | Function-style processor                             |
| `defaultIdleStrategy(idleStrategy)`                                 | Override the default (`SleepingMillisIdleStrategy(1)`) |
| `customise(fn)`                                                     | Escape hatch onto the raw `MongooseServerConfig.Builder` |
| `start()`                                                           | Build, boot, return harness                          |

### Harness

| Method                                            | Purpose                                              |
|---------------------------------------------------|------------------------------------------------------|
| `awaitCondition(BooleanSupplier)`                 | Poll until true; default 3 s timeout                 |
| `awaitCondition(BooleanSupplier, Duration)`       | Poll until true or `AssertionError`                  |
| `awaitFileLines(Path, int)`                       | Poll until file has N lines                          |
| `awaitFileLines(Path, int, Duration)`             | Same with explicit timeout                           |
| `readLines(Path)`                                 | Read file as UTF-8 lines (unchecked I/O)             |
| `server()`                                        | The underlying `MongooseServer`                      |
| `close()`                                         | Stop server; idempotent                              |
| `MongooseTestHarness.wrap(server)`                | Adopt an externally-booted server                    |

## Conventions

1. **Always `try-with-resources`** — `start()` returns `AutoCloseable`; don't manage `server.stop()` manually.
2. **Use `awaitCondition`, not `Thread.sleep`** — sleeps are flaky on CI; condition polls succeed as soon as the predicate holds.
3. **One harness per test** — fresh server each time. Shared servers create ordering coupling.
4. **`@TempDir` for any file paths** — auto-cleanup and isolation.

## When to drop down

If you need something the builder doesn't expose:

```java
try (MongooseTestHarness h = MongooseTestHarness.builder()
        .feed("in", feed, "feed-agent")
        .customise(b -> b
            .addThread(new ThreadConfig("custom-thread", true))
            .eventInvokeStrategy(CallBackType.ON_TRIGGER, MyStrategy::new))
        .start()) {
    // ...
}
```

`customise(...)` hands you the underlying `MongooseServerConfig.Builder` — every config method is available.

## Source

[`mongoose-plugins/test-support/mongoose-test-support`](https://github.com/telaminai/mongoose-plugins/tree/main/test-support/mongoose-test-support)

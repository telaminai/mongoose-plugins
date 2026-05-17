# mongoose-test-support

Test harness that wraps a booted [`MongooseServer`](https://github.com/telaminai/mongoose) as an `AutoCloseable`, with a builder for the common feed/sink/processor wiring and a small suite of await helpers. Replaces ~30 lines of boot/teardown ceremony per test with ~10.

## Maven

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>mongoose-test-support</artifactId>
    <version>0.2.8-SNAPSHOT</version>
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

## API surface

### Builder

| Method                                                              | Purpose                                              |
|---------------------------------------------------------------------|------------------------------------------------------|
| `feed(name, source, agentName)`                                     | Add an event feed (uses default idle strategy)       |
| `feed(name, source, agentName, idleStrategy)`                       | Same with explicit idle strategy                     |
| `sink(name, sink)`                                                  | Add a message sink                                   |
| `service(name, instance)`                                           | Register a service under the concrete class           |
| `service(name, instance, serviceClass)`                             | Register under an interface or alternative class      |
| `processor(agentName, handlerName, ObjectEventHandlerNode)`         | Add a typed processor                                 |
| `processor(agentName, handlerName, Consumer<Object>)`               | Add a function-style processor                        |
| `defaultIdleStrategy(idleStrategy)`                                 | Override the default (`SleepingMillisIdleStrategy(1)`) |
| `customise(fn)`                                                     | Escape hatch — operate on the raw `MongooseServerConfig.Builder` |
| `start()`                                                           | Build config, boot, return `MongooseTestHarness`     |

### Harness

| Method                                            | Purpose                                              |
|---------------------------------------------------|------------------------------------------------------|
| `awaitCondition(BooleanSupplier)`                 | Poll until true; default 3 s timeout                 |
| `awaitCondition(BooleanSupplier, Duration)`       | Poll until true or timeout (`AssertionError` on miss) |
| `awaitFileLines(Path, int)`                       | Poll until file exists and has N lines                |
| `awaitFileLines(Path, int, Duration)`             | Same with explicit timeout                            |
| `readLines(Path)`                                 | Read file as UTF-8 lines (unchecked I/O)              |
| `server()`                                        | Access the underlying `MongooseServer`                |
| `close()`                                         | Stop server; idempotent                               |
| `MongooseTestHarness.wrap(server)`                | Adopt an externally-booted server                     |

## Defaults

- Idle strategy: `SleepingMillisIdleStrategy(1)` — the catalogue's recommended default. Override with `.defaultIdleStrategy(...)`.
- Await timeout: 3 seconds, polled every 20 ms. Tuned so a 1-ms-idle server has plenty of cycles to make progress.
- Cleanup: `close()` calls `server.stop()` and swallows any teardown exceptions so they don't fail the test.

## Conventions

1. **`try-with-resources` always.** `start()` returns an `AutoCloseable`; don't manage `server.stop()` manually.
2. **Use `awaitCondition`, not `Thread.sleep`.** Sleeps make tests flaky on slow CI runners; condition polls succeed as soon as the condition holds.
3. **One harness per test.** Each test boots a fresh server. Sharing servers across tests creates ordering coupling.
4. **Use `@TempDir` for any file paths.** Cleans up automatically and avoids cross-test interference.

## When to drop down to the raw API

The harness covers the common case. If you need a feature it doesn't expose:

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

`customise(...)` hands you the underlying `MongooseServerConfig.Builder`; you can call anything on it. Use this for `addThread`, custom `eventInvokeStrategy`, multiple processor groups, etc.

## License

AGPL-3.0-only.

# mongoose-test-support

Two tools: `MongooseTestHarness`, a booted server for integration tests, and `FixtureRunner`, an in-process run of a
generated processor over feed files with no server (see [FixtureRunner](#fixturerunner-in-process-fixtures)).

Test harness that wraps a booted [`MongooseServer`](https://github.com/telaminai/mongoose) as an `AutoCloseable`, with a builder for the common feed/sink/processor wiring and a small suite of await helpers. Replaces ~30 lines of boot/teardown ceremony per test with ~10.

## Maven

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>mongoose-test-support</artifactId>
    <version>${mongoose-plugins.version}</version>   <!-- FixtureRunner: 1.1.2 or later -->
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

## FixtureRunner: in-process fixtures

`FixtureRunner` runs a generated processor over feed lines in process, with no server, and collects what it publishes
to one sink. Each feed runs in a fresh processor instance through the server's lifecycle: init, services, start,
`startComplete` (anything it publishes is collected, before the first line's output), the feed lines, stop, tearDown.
A failure anywhere, `startComplete` included, still stops and tears the processor down. A restart feed
runs a new instance on the same cache files over its lines only, as a server restart that resumes the feed does. Each
`--cache` is a `JsonFileCache` (svc-cache) registered as a `Cache` service; its file is deleted before the first run.
Sink values are formatted as connector-file's `JsonLineSink` writes them (a String as is, anything else as one JSON
line). A feed line `#admin <command> [args...]` invokes a signal-routed admin command in the processor's event cycle
(as `registerSignalCommand` does on a server) and collects its replies; other lines starting with `#` are comments.

No feed source runs in a fixture, so events carry no Mongoose `DeliveryContext`: a `PersistentNode`'s `replayed()` is
always false here and crash re-delivery cannot be exercised with FixtureRunner (use a server and a killed process).
The processor is always stopped and torn down, then the caches; if several steps fail, the first failure is thrown with
the others attached as suppressed. Class names (`processor(String)`, `mapper(String)`) are loaded when `run()` starts,
through `classLoader(...)` if set.

### From Java (JUnit or anything else)

```java
FixtureRunner.Result result = FixtureRunner.builder()
        .processor(MarketProcessor::new)                  // or .processor("com.example.generated.MarketProcessor")
        .mapper(new AppLineMapper())                      // the feed's valueMapper; identity if not set
        .sink("alerts")
        .cache("appState", tempDir.resolve("appState.json"))
        .feed(Path.of("fixtures/day1.txt"))
        .restart(Path.of("fixtures/day2.txt"))            // optional: a server restart, then these lines
        .audit(Path.of("logs/fixture-audit.yaml"))        // optional: every node's audit records
        .run();

assertEquals(Files.readAllLines(Path.of("fixtures/expected-all.txt")), result.sinkLines());
assertEquals(List.of("count=3"), result.adminReplies());
FixtureRunner.Comparison cmp = result.compare(Path.of("fixtures/expected-all.txt"));
assertTrue(cmp.pass(), String.join("\n", cmp.differences(8)));
```

`feedLines(List<String>)` / `restartLines(...)` take lines instead of files, `actual(path)` also writes the sink lines,
`protect(path)` names a file the run must not write (the expected baseline), `onAdminReply(consumer)` sees each admin
reply as it happens.

**No run writes over its own inputs.** The files a run writes or deletes (each cache, `actual`, `audit`) must differ
from each other and from the files it reads (feed and restart files, protected files). The same file under two names
counts as the same: a symbolic or hard link (including a symbolic link to a file not yet created, which writing would
create), `a/../b`, or a case-insensitive name of an existing file. `run()` refuses a collision, or a symbolic link
loop, with `IllegalArgumentException` before anything is deleted or run. (Two names for a file not yet created
that differ only in case are not detected on a case-insensitive file system.)

### Command line

The caller provides the class path: the application's classes, its dependencies and this artifact. No fat jar or
build layout is assumed.

```
java -cp <classpath> com.telamin.mongoose.plugin.testsupport.FixtureRunner \
    --processor com.example.generated.MarketProcessor --mapper com.example.mapper.AppLineMapper --sink alerts \
    [--cache name=data/run/state.json[,name2=file2]] --feed scenario.txt [--restart restart-B.txt] \
    [--expect expected.txt] [--actual logs/fixture-actual.txt] [--audit logs/fixture-audit.yaml]
```

`--expect` is read before the run and protected from it, so `--actual` (or a cache, or `--audit`) on the expected file
is refused (exit 2) rather than overwriting the baseline and passing; so is any other collision above, and an expected
file that cannot be read.

Prints `admin> ` replies, then `PASS: n lines match ...` (exit 0) or `FAIL` with the first differing lines (exit 1);
without `--expect` it prints the sink lines. A run that throws prints `FIXTURE RUN FAILED: <root cause>` and the first
frame outside the runner (exit 2). `main` always calls `System.exit`, so an application's non-daemon threads cannot
keep the JVM alive; `FixtureRunner.run(PrintStream, String...)` is the same without exiting. `--processor` names a
`DataFlow` class or a `Supplier<DataFlow>` class (no-arg constructor), `--mapper` a `Function<Object, Object>` class.

### Maven

Depend on it with test scope (above). Two ways to run the command line, both from the project's test class path:

```bash
# a: let the build write the class path (bind once in the pom), then run java
#    <plugin> maven-dependency-plugin, goal build-classpath, phase generate-sources,
#             includeScope test, outputFile ${project.build.directory}/fixture.classpath </plugin>
./mvnw -q package -DskipTests
java -cp "target/classes:$(cat target/fixture.classpath)" com.telamin.mongoose.plugin.testsupport.FixtureRunner --processor ...

# b: exec-maven-plugin, forked (exec:exec, not exec:java: main calls System.exit)
./mvnw -q test-compile exec:exec -Dexec.executable=java -Dexec.classpathScope=test \
    -Dexec.args="-cp %classpath com.telamin.mongoose.plugin.testsupport.FixtureRunner --processor ... --expect expected.txt"
```

From surefire, call the Java API in a test (above): `./mvnw test -Dtest=MyFixtureTest`.

### Gradle

```groovy
dependencies {
    testImplementation "com.telamin:mongoose-test-support:$mongoosePluginsVersion"
}
tasks.register('fixture', JavaExec) {
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'com.telamin.mongoose.plugin.testsupport.FixtureRunner'
    args '--processor', 'com.example.generated.MarketProcessor', '--mapper', 'com.example.mapper.AppLineMapper',
         '--sink', 'alerts', '--cache', 'appState=build/fixture/appState.json',
         '--feed', 'fixtures/day1.txt', '--expect', 'fixtures/expected-day1.txt'
}
```

`gradle fixture`, or call the Java API from a JUnit test run by `gradle test`.

### jbang

FixtureRunner can be run straight from its coordinates with the application's compiled classes added:

```bash
jbang --deps com.telamin:mongoose:<mongoose version>,com.telamin:connector-file:<version> --cp target/classes \
      --main com.telamin.mongoose.plugin.testsupport.FixtureRunner \
      com.telamin:mongoose-test-support:<version> \
      --processor com.example.generated.MarketProcessor --mapper com.example.mapper.AppLineMapper --sink alerts \
      --cache appState=data/run/fx-appState.json --feed fixtures/day1.txt --expect fixtures/expected-day1.txt
```

or from a script, `//DEPS com.telamin:mongoose-test-support:<version>` plus the application's own `//DEPS`, calling
`FixtureRunner.builder()...run()` or `FixtureRunner.main(args)`. List each further dependency of the application
(`--deps` or `//DEPS`; above, connector-file for the application's `LineMapper`); `--deps com.telamin:mongoose:...` pins the Mongoose the application was built with over the one
this artifact brings.

### Migration from `com.telamin:fluxtion-app-support`

| Old | New (artifact `com.telamin:mongoose-test-support`) |
|---|---|
| `com.telamin.support.FixtureRunner` (CLI, run from the application's fat jar) | `com.telamin.mongoose.plugin.testsupport.FixtureRunner` (CLI with the same options and output, plus the Java API); put it on the class path yourself |

## License

AGPL-3.0-only.

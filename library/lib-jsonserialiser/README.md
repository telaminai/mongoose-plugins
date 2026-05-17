# lib-jsonserialiser

A `Function<Object, Object>` that converts JSONL (single-line JSON) records into typed Java objects, using a `"type"` discriminator field for dispatch. Designed to drop into a Mongoose `EventFeedConfig.valueMapper(...)` slot so file or socket feeds of mixed-type JSONL events can be deserialised into the right concrete Java types.

## What it does

Each call to `apply(input)` accepts either:

- a single JSON string → returns the deserialised Java object
- a `List` of one or more JSON strings → if length 1, the single object; if length > 1, a `BatchDto` wrapping all the deserialised items

The serialiser inspects each JSON record for a top-level `"type"` field. If present, it loads that class via reflection and deserialises into it. If absent, it falls back to a `Map<String, Object>`.

Unknown properties are tolerated (Jackson `FAIL_ON_UNKNOWN_PROPERTIES` is disabled), so adding fields downstream won't break older producers.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>lib-jsonserialiser</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage

Wire it as the value mapper on a file or socket event feed:

```java
EventFeedConfig.<String>builder()
    .instance(fileSource)
    .valueMapper(new TypeSerialiser())
    .name("trade-feed")
    .agent("file-source-agent", new SleepingMillisIdleStrategy())
    .build();
```

Sample JSONL payload:

```jsonl
{"type": "com.example.Trade", "id": 1, "symbol": "EURUSD", "volume": 1000000}
{"type": "com.example.MidPrice", "symbol": "EURUSD", "rate": 1.0832}
```

Each line is dispatched to the named class. `Trade` and `MidPrice` must be on the runtime classpath of the Mongoose server.

## When to reach for it

- You have a single feed carrying multiple event types and want type-safe dispatch driven by the payload itself, not the feed config.
- You want a batched `BatchDto` wrapper for free when more than one line is delivered in a single read.

If your feed is always one type, prefer a dedicated mapper (e.g. a lambda calling `objectMapper.readValue(json, Trade.class)`) — it avoids the per-record `Class.forName(...)` overhead.

## Limitations

- `Class.forName(...)` is called per record, with no caching. Acceptable for typical mongoose throughput; not suitable for hot inner loops.
- Failures (unknown class, malformed JSON) are logged at error level and the record is mapped to `null`. Downstream handlers should defend against null events.

## See also

- [TypeSerialiser source](src/main/java/com/fluxtion/dataflow/serverplugin/lib/json/TypeSerialiser.java)
- [JsonlTest](src/test/java/com/fluxtion/dataflow/serverplugin/lib/json/JsonlTest.java)

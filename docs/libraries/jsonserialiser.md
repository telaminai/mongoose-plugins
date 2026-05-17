# lib-jsonserialiser

<span class="plugin-tags">
  <span class="plugin-tag library">library</span>
</span>

Type-discriminated JSONL deserialiser. Each line is a JSON object with a `@type` field that names the target class; the deserialiser instantiates the right type from a registered class lookup table.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>lib-jsonserialiser</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## When to use

- A file or socket feed carries multiple event types as JSONL.
- You want strongly-typed events in your processor without a custom mapper per feed.

## Sample

Input file `trades.jsonl`:

```
{"@type":"Trade","symbol":"AAPL","qty":100,"price":150.25}
{"@type":"Quote","symbol":"AAPL","bid":150.20,"ask":150.30}
{"@type":"Trade","symbol":"GOOG","qty":50,"price":2700.50}
```

Wire it into a file feed:

```yaml
eventFeeds:
  - name: trades
    instance: !!com.fluxtion.dataflow.serverplugin.connector.file.FileEventSource
      filename: ./data-in/trades.jsonl
    valueMapper: !!com.fluxtion.dataflow.serverplugin.lib.json.TypeSerialiser
      typeMap:
        Trade: com.example.Trade
        Quote: com.example.Quote
```

Now your processor receives instances of `Trade` and `Quote` directly:

```java
public class TradeHandler extends ObjectEventHandlerNode {
    @Override
    protected boolean handleEvent(Object event) {
        return switch (event) {
            case Trade t -> { handleTrade(t); yield true; }
            case Quote q -> { handleQuote(q); yield true; }
            default -> false;
        };
    }
}
```

## Configuration reference

| Field      | Default | Notes                                                    |
|------------|---------|----------------------------------------------------------|
| `typeMap`  | empty   | `Map<String, Class<?>>` mapping `@type` values to classes. |

## Operational notes

- Backed by Jackson — extend with custom deserializers / modules by subclassing.
- Unknown `@type` values are logged and the line is skipped (does not throw).
- Each entry in `typeMap` must be a real class on the classpath; misspellings throw at startup.

## Source

[`mongoose-plugins/library/lib-jsonserialiser`](https://github.com/telaminai/mongoose-plugins/tree/main/library/lib-jsonserialiser)

# connector-kafka

<span class="plugin-tags">
  <span class="plugin-tag source">source</span>
  <span class="plugin-tag sink">sink</span>
  <span class="plugin-tag broker">broker</span>
</span>

Apache Kafka producer (`KafkaMessagePublisher`) and consumer (`KafkaMessageConsumer`) for cross-process / cross-host messaging.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-kafka</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## When to use

- Producer/consumer pattern that crosses process or host boundaries.
- You already operate a Kafka cluster.
- You need broker-side fan-out, retention, or consumer-group semantics.

For single-host pipelines, prefer `connector-file` or `connector-chronicle` — they have no broker dependency.

## Producer

```yaml
eventSinks:
  - name: enriched-out
    instance: !!com.telamin.mongoose.plugin.connector.kafka.KafkaMessagePublisher
      topic: enriched-trades
      flushEveryMessage: true
      registerShutdownHook: true
      closeTimeoutMs: 5000
      properties:
        bootstrap.servers: kafka-1:9092,kafka-2:9092
        key.serializer: org.apache.kafka.common.serialization.StringSerializer
        value.serializer: org.apache.kafka.common.serialization.StringSerializer
        acks: all
```

Async send callback tracks success / failure:

- `getSendCount()` — successful sends
- `getSendErrors()` — async failures (logged at WARN)

### Why a shutdown hook?

`registerShutdownHook=true` (default) installs a JVM shutdown hook that flushes + closes the producer on abrupt VM exit. Combined with `closeTimeoutMs` (default 5 s), buffered records have a bounded window to drain before the JVM dies. Disable for tests.

## Consumer

```yaml
eventFeeds:
  - name: orders-feed
    instance: !!com.telamin.mongoose.plugin.connector.kafka.KafkaMessageConsumer
      topics: ["orders"]
      pollTimeoutMs: 100
      wakeupOnTearDown: true
      properties:
        bootstrap.servers: kafka-1:9092
        group.id: mongoose-orders
        key.deserializer: org.apache.kafka.common.serialization.StringDeserializer
        value.deserializer: org.apache.kafka.common.serialization.StringDeserializer
        auto.offset.reset: latest
```

`tearDown()` calls `consumer.wakeup()` to unblock an in-flight `poll()` before `close()` — the agent thread exits within `pollTimeoutMs`.

## Configuration reference

### KafkaMessagePublisher

| Property               | Default | Notes                                                |
|------------------------|---------|------------------------------------------------------|
| `topic`                | _required_ | Throws `IllegalStateException` if unset.          |
| `properties`           | empty   | Standard Kafka producer config.                       |
| `flushEveryMessage`    | `true`  | Synchronous flush after every send. Durable but slow. |
| `registerShutdownHook` | `true`  | JVM hook flushes buffered records on abrupt exit.    |
| `closeTimeoutMs`       | `5000`  | Bound on `producer.close()` during teardown.          |

### KafkaMessageConsumer

| Property            | Default | Notes                                            |
|---------------------|---------|--------------------------------------------------|
| `properties`        | _required_ | Standard Kafka consumer config.                |
| `topics`            | _required_ | One or more topic names.                       |
| `pollTimeoutMs`     | `100`   | Per-`doWork` poll timeout.                       |
| `wakeupOnTearDown`  | `true`  | Wake an in-flight poll to unblock teardown.      |

## Operational notes

- Bring your own Kafka client config — `bootstrap.servers`, serializers, and ACL credentials live in the `properties` map.
- The publisher uses an async send callback; if your `value.serializer` blows up the failure is logged at WARN and counted in `getSendErrors()`. Set `flushEveryMessage=true` and check `getSendErrors()` after a critical batch.
- For at-least-once on the consumer side, configure `enable.auto.commit=false` in `properties` and commit manually from your processor.

## Examples

- **[plugins/event-source-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-example)** — agent-hosted source template; mirrors how `KafkaMessageConsumer` integrates.
- **[plugins/message-sink-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/message-sink-example)** — sink template; same shape as `KafkaMessagePublisher`.

A docker-compose-based round-trip example is on the [Examples](../examples.md) roadmap.

## Source

[`mongoose-plugins/connector/connector-kafka`](https://github.com/telaminai/mongoose-plugins/tree/main/connector/connector-kafka)

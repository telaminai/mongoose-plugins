# connector-kafka

Apache Kafka producer + consumer integration for Mongoose.

| Class | Role |
|---|---|
| [`KafkaMessagePublisher`](src/main/java/com/fluxtion/dataflow/serverplugin/connector/kafka/KafkaMessagePublisher.java) | `MessageSink<Object>` that publishes each delivered event to a Kafka topic. Optional per-message flush (default on) or batched flush via an agent's `doWork()`. |
| [`KafkaMessageConsumer`](src/main/java/com/fluxtion/dataflow/serverplugin/connector/kafka/KafkaMessageConsumer.java) | `EventSource` that polls a Kafka topic and publishes each record into the Mongoose event pipeline. |

Built against `kafka-clients` 3.9.0.

## When to use

- You want Mongoose processors to consume from / publish to an existing Kafka topology.
- You need ordered, partitioned, replayable transport between services rather than file tailing or multicast.

For local-only message passing prefer `connector-multicast` (no external broker) or `connector-file` (replayable, durable, single-process).

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-kafka</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — Java config

```java
KafkaMessagePublisher publisher = new KafkaMessagePublisher();
publisher.setTopic("trades");
Properties props = new Properties();
props.setProperty("bootstrap.servers", "localhost:9092");
props.setProperty("key.serializer",   "org.apache.kafka.common.serialization.StringSerializer");
props.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
publisher.setProperties(props);
publisher.setFlushEveryMessage(false);          // batch flushes via doWork()

EventSinkConfig<MessageSink<?>> tradeSink = EventSinkConfig.<MessageSink<?>>builder()
        .instance(publisher)
        .name("trades-kafka-sink")
        .build();
```

## Operational notes

- `setProperties(...)` is required before `init()`; the test suite confirms construction with the standard `bootstrap.servers` + serializer keys.
- With `flushEveryMessage=true` (default) every send is followed by a synchronous `producer.flush()` — durable but slow.
- With `flushEveryMessage=false` the publisher batches and only flushes on the agent's `doWork()` tick. Pair with a sensible idle strategy (e.g. `SleepingMillisIdleStrategy(1)`).
- `tearDown()` flushes and closes the producer cleanly.

## Tests

- [`KafkaMessagePublisherTest`](src/test/java/com/fluxtion/dataflow/serverplugin/connector/kafka/KafkaMessagePublisherTest.java) — broker-free construction + properties handling.

End-to-end tests against a real broker require Docker; see [`src/test/environment/docker-compose.yml`](src/test/environment/docker-compose.yml) for the broker setup.

## Related

- [`connector-multicast`](../connector-multicast/) — in-process / single-host alternative without an external broker.
- [`connector-file`](../connector-file/) — replayable, durable, single-process alternative.

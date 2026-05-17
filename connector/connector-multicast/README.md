# connector-multicast

UDP multicast event source and message sink for Mongoose. Lets multiple processes on the same network exchange events without a broker.

| Class | Role |
|---|---|
| [`MulticastEventSource`](src/main/java/com/fluxtion/server/plugin/connector/multicast/MulticastEventSource.java) | Joins a multicast group, decodes incoming UDP datagrams as UTF-8 strings, publishes each as an event. |
| [`MulticastMessageSink`](src/main/java/com/fluxtion/server/plugin/connector/multicast/MulticastMessageSink.java) | Encodes each delivered event as a UTF-8 datagram and sends to the multicast group. |
| [`NetworkHelper`](src/main/java/com/fluxtion/server/plugin/connector/multicast/NetworkHelper.java) | Utility for picking a sensible network interface (including loopback support for local-only testing). |

## When to use

- Local- or LAN-scoped event distribution between multiple JVMs without an external broker.
- Test rigs that need a multi-process pubsub channel that doesn't require Kafka.
- Discovery / heartbeat channels alongside your main data pipeline.

For cross-DC or persistent transport prefer `connector-kafka`. For single-process durability prefer `connector-chronicle` or `connector-file`.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-multicast</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — Java config

```java
MulticastEventSource source = new MulticastEventSource();
source.setMulticastGroup("239.10.10.1");
source.setPort(45678);
// optional: source.setInterfaceName("en0"); or use loopback for tests

EventFeedConfig<?> tradeFeed = EventFeedConfig.builder()
        .instance(source)
        .name("multicast-feed")
        .agent("multicast-source-agent", new SleepingMillisIdleStrategy())
        .build();

MulticastMessageSink sink = new MulticastMessageSink();
sink.setMulticastGroup("239.10.10.1");
sink.setPort(45678);

EventSinkConfig<MessageSink<?>> outSink = EventSinkConfig.<MessageSink<?>>builder()
        .instance(sink)
        .name("multicast-sink")
        .build();
```

## Notes

- **No persistence, no replay.** UDP multicast is fire-and-forget. Combine with `connector-file` or `connector-chronicle` if you need to recover after a process restart.
- **Multicast routing.** Many corporate / cloud networks drop multicast at the router. Verify your environment supports it (use `iperf -u -c <group>` or similar) before debugging in code.
- **TTL defaults to single-hop.** Adjust on the underlying socket if cross-subnet delivery is required.
- **Loopback testing.** Use `NetworkHelper.loopbackInterface()` when running multiple instances on the same host without a real NIC.

## Tests

- [`MulticastEventSourceTest`](src/test/java/com/fluxtion/server/plugin/connector/multicast/MulticastEventSourceTest.java) — round-trip publish/receive on loopback.
- [`MulticastMessageSinkTest`](src/test/java/com/fluxtion/server/plugin/connector/multicast/MulticastMessageSinkTest.java) — encoding, group/port handling.

## Related

- [`connector-kafka`](../connector-kafka/) — durable, partitioned, network-scoped alternative.
- [`connector-file`](../connector-file/) / [`connector-chronicle`](../connector-chronicle/) — single-host persistent alternatives.

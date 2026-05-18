# connector-multicast

<span class="plugin-tags">
  <span class="plugin-tag source">source</span>
  <span class="plugin-tag sink">sink</span>
</span>

UDP multicast source + sink. LAN-scoped pub-sub without a broker — discovery, heartbeats, test rigs.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>connector-multicast</artifactId>
    <version>{{ plugin_version }}</version>
</dependency>
```

## When to use

- LAN-scoped fan-out with no broker.
- Service discovery and heartbeats between Mongoose instances.
- Test rigs that need pub-sub semantics without external infrastructure.

Not suitable for cross-WAN messaging or any scenario requiring durability — UDP is fire-and-forget.

## Sample

```yaml
eventFeeds:
  - name: heartbeats
    instance: !!com.telamin.mongoose.plugin.connector.multicast.MulticastEventSource
      multicastGroup: 224.0.1.50
      port: 4446
      interfaceName: eth0

eventSinks:
  - name: heartbeat-out
    instance: !!com.telamin.mongoose.plugin.connector.multicast.MulticastMessageSink
      multicastGroup: 224.0.1.50
      port: 4446
      ttl: 1
```

## Operational notes

- `interfaceName` is supported on both sides for explicit NIC selection; loopback works for tests.
- TTL defaults to `1` (don't escape the local subnet). Bump only if you've thought about your network topology.
- No back-pressure — drops are silent at the UDP layer.

## Examples

- **[plugins/event-source-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/event-source-example)** — agent-hosted source template that maps directly onto `MulticastEventSource`.

## Source

[`mongoose-plugins/connector/connector-multicast`](https://github.com/telaminai/mongoose-plugins/tree/main/connector/connector-multicast)

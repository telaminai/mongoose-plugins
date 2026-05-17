# svc-loader-yaml

<span class="plugin-tags">
  <span class="plugin-tag service">service</span>
</span>

Load and reload processors at runtime from YAML or Java source. Hot-reload graphs without restarting the server.

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-loader-yaml</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## When to use

- You ship processor topologies as YAML config and want operators to swap them at runtime.
- You need a "compile + load" admin command that builds a new processor from source.
- You're prototyping topologies and want fast iteration without server restarts.

## Modes

| Mode           | Source              | Speed at runtime | Best for                              |
|----------------|---------------------|------------------|---------------------------------------|
| Compiled       | Java source         | Fast             | Production-grade dynamic loading      |
| Interpreted    | YAML                | Slower at first event, fast steady-state | Pure-config topologies            |

## Sample

Register the loader as a service and point it at one or more processor
topologies to instantiate at boot:

```yaml
services:
  - name: yamlLoaderService
    service: !!com.fluxtion.dataflow.serverplugin.loader.yaml.EventHandlerLoader
      loadAtStartup:
        - { yamlFile: "config/log-processor.yaml", compile: true }
```

Or, programmatically from Java:

```java
EventHandlerLoader.EventLoadAtStartup load = new EventHandlerLoader.EventLoadAtStartup();
load.setYamlFile("config/log-processor.yaml");
load.setCompile(true);

EventHandlerLoader loader = new EventHandlerLoader();
loader.setLoadAtStartup(Set.of(load));

MongooseServerConfig.builder()
    .addService(ServiceConfig.<EventHandlerLoader>builder()
        .service(loader)
        .serviceClass(EventHandlerLoader.class)
        .name("yamlLoaderService")
        .build())
    .build();
```

The processor YAML uses `namedNodes` or `nodes` plus SnakeYAML's `!!ClassName` tag:

```yaml
namedNodes:
  logHandler: !!com.example.MyHandler
    prefix: "INCOMING:"
enableAudit: true
```

When `svc-admin-rest` or `svc-admin-telnet` is on the classpath, the loader
also registers four admin commands:

- `yamlLoader.compileProcessor <yamlFile> [group]` — compile and add a YAML topology
- `yamlLoader.interpretProcessor <yamlFile> [group]` — same, via the Fluxtion interpreter (no codegen)
- `javaLoader.compileProcessor <javaFile> [group]` — compile and add a Java `FluxtionGraphBuilder` source file
- `javaLoader.interpretProcessor <javaFile> [group]` — same, via the interpreter

## Examples

- **[plugins/yaml-service-loader-example](https://github.com/telaminai/mongoose-examples/tree/main/plugins/yaml-service-loader-example)** — `EventHandlerLoader` registered as a service with `loadAtStartup`; instantiates a handler from `log-processor.yaml`, feeds it events from an in-memory source, prints them through the YAML-injected prefix.
- **[getting-started/five-minute-yaml-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/five-minute-yaml-tutorial)** — full YAML config flow into a booting server. Same config shape `svc-loader-yaml` consumes at runtime.
- **[getting-started/app-integration-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/app-integration-tutorial)** — multi-process YAML config across a data-generator + PnL calculator.

## Gotchas

- **SnakeYAML 2.x blocks `!!ClassName` tags by default.** Until the plugin
  ships a release that installs a permissive `TagInspector`, pin
  `org.yaml:snakeyaml:1.33` in the consumer pom.
- **`mongoose` >= 1.0.9 required.** Earlier versions miss event-processor
  agents that the loader registers during its own `start()` hook — the
  processor would init but never receive events. Fixed by a late-start pass
  in `MongooseServer.start()`.

## Source

[`mongoose-plugins/service/svc-loader-yaml`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-loader-yaml)

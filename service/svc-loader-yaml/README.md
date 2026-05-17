# svc-loader-yaml

Loads Fluxtion event-processor graphs at runtime from external YAML configuration files, then registers each compiled (or interpreted) processor with the running Mongoose server. Also supports loading a graph from a Java source file via `javaparser`.

This is the bridge that lets you ship a deployed Mongoose binary and add new processors via config without rebuilding.

## Capabilities

| Capability | Admin command |
|---|---|
| Compile a YAML config into a generated processor and register it | `yamlLoader.compileProcessor <yaml-file> [<group-name>]` |
| Interpret a YAML config (no codegen, slower, but no compile dependency at runtime) | `yamlLoader.interpretProcessor <yaml-file> [<group-name>]` |
| Compile a `.java` source file (a `FluxtionGraphBuilder` impl) into a processor | `javaLoader.compileProcessor <java-file> [<group-name>]` |
| Interpret a `.java` source file (no codegen) | `javaLoader.interpretProcessor <java-file> [<group-name>]` |

You can also configure a static list of YAML files to load at server startup via the `loadAtStartup` property.

## Maven coordinates

```xml
<dependency>
    <groupId>com.telamin</groupId>
    <artifactId>svc-loader-yaml</artifactId>
    <version>0.2.8-SNAPSHOT</version>
</dependency>
```

## Usage — register as a service

```yaml
services:
  - name: yamlEventHandlerLoader
    instance: !!com.telamin.mongoose.plugin.loader.yaml.EventHandlerLoader
      loadAtStartup:
        - yamlFile: ./config/pricing-processor.yaml
          group: pricing-group
          compile: true
          initialLogLevel: INFO
```

Wire alongside an `AdminCommandRegistry` (e.g. from `svc-admin-telnet` or `svc-admin-rest`) and the loader auto-registers its commands. The server controller is injected via `@ServiceRegistered` so the loader can `addEventProcessor(...)` into the running server.

## YAML graph schema (minimal)

```yaml
nodes:
  - name: ticker
    type: com.example.TickerNode
    constructorArgs:
      - "AAPL"
  - name: average
    type: com.example.MovingAverageNode
    constructorArgs:
      - !!ref ticker
      - 30
```

The loader hands this off to `Fluxtion.compile(...)` or `FluxtionInterpreter.interpret(...)` via the `YamlFactory` builder. Compiled mode generates Java source for the processor and JIT-loads it; interpreted mode reflects over the node graph at runtime.

## Notes

- The `addEventAuditor` flag wraps every event entering the processor with the Fluxtion event auditor — useful for testing, expensive for production.
- `initialLogLevel` is applied to the processor after `init()`. Use `traceLogLevel` to flip into verbose auditing on demand from an admin command.
- Compiled mode produces a regenerated `.java` artefact at runtime and requires a JDK on the classpath (javac available); interpreted mode does not.

## Related

- [`svc-loader-spring`](../svc-loader-spring/) — same idea but reads a Spring XML application context.
- [`svc-admin-telnet`](../svc-admin-telnet/) / [`svc-admin-rest`](../svc-admin-rest/) — admin endpoints that surface the loader's commands.

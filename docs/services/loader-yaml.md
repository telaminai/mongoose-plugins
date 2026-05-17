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

```yaml
services:
  - name: yamlLoader
    instance: !!com.fluxtion.dataflow.serverplugin.svc.loader.yaml.YamlProcessorLoaderService
      processorsDir: ./processors
```

At runtime, the admin command set is extended with:

- `loader.<name>.load <file>` — load a processor from YAML or Java source
- `loader.<name>.unload <id>` — remove a loaded processor

## Examples

- **[getting-started/five-minute-yaml-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/five-minute-yaml-tutorial)** — full YAML config flow into a booting server. Same config shape `svc-loader-yaml` consumes at runtime.
- **[getting-started/app-integration-tutorial](https://github.com/telaminai/mongoose-examples/tree/main/getting-started/app-integration-tutorial)** — multi-process YAML config across a data-generator + PnL calculator.

## Source

[`mongoose-plugins/service/svc-loader-yaml`](https://github.com/telaminai/mongoose-plugins/tree/main/service/svc-loader-yaml)
